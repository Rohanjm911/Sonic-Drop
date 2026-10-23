package com.sonicdrop.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.sonicdrop.audio.AudioController
import com.sonicdrop.audio.NativeDspBridge
import com.sonicdrop.protocol.Packet
import com.sonicdrop.protocol.PacketType
import com.sonicdrop.protocol.ProtocolConstants
import com.sonicdrop.security.SonicCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.KeyPair
import kotlin.random.Random

sealed class TransferRole {
    object Idle : TransferRole()
    object Sender : TransferRole()
    object Receiver : TransferRole()
}

sealed class TransferPhase {
    object Idle : TransferPhase()
    object Probing : TransferPhase()
    object ListeningForPeer : TransferPhase()
    object KeyExchange : TransferPhase()
    data class AuthVerification(val sasCode: String) : TransferPhase()
    data class InProgress(
        val fileName: String,
        val totalBytes: Long,
        val bytesTransferred: Long,
        val percent: Float,
        val currentSpeedBps: Float,
        val packetIndex: Int,
        val totalPackets: Int,
        val snrDb: Float
    ) : TransferPhase()
    data class Success(val fileName: String, val totalBytes: Long, val filePath: String?) : TransferPhase()
    data class Failed(val error: String) : TransferPhase()
}

/**
 * End-to-end Air-gapped File Transfer Manager:
 * Orchestrates Audio I/O, Protocol State Machine, X25519 Key Agreement,
 * SAS Verification, ChaCha20-Poly1305 Chunk Encryption, and Storage Reconstruction.
 */
class TransferManager(
    private val context: Context,
    val audioController: AudioController
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var transferJob: Job? = null

    private val _phase = MutableStateFlow<TransferPhase>(TransferPhase.Idle)
    val phase: StateFlow<TransferPhase> = _phase.asStateFlow()

    private val _role = MutableStateFlow<TransferRole>(TransferRole.Idle)
    val role: StateFlow<TransferRole> = _role.asStateFlow()

    // Session state
    private var localKeyPair: KeyPair? = null
    private var sessionKey: ByteArray? = null
    private var sessionId: Int = 0
    private var authConfirmed = false

    /**
     * Confirms Short Authentication String (SAS) matching.
     */
    fun confirmAuthentication() {
        authConfirmed = true
    }

    /**
     * Aborts transfer and cleans up audio and cryptographic state.
     */
    fun cancelTransfer() {
        transferJob?.cancel()
        transferJob = null
        audioController.stopListening()
        localKeyPair = null
        sessionKey = null
        authConfirmed = false
        _phase.value = TransferPhase.Idle
        _role.value = TransferRole.Idle
    }

    /**
     * Starts Sender Workflow with chosen file Uri.
     */
    fun startSending(fileUri: Uri) {
        cancelTransfer()
        _role.value = TransferRole.Sender
        _phase.value = TransferPhase.KeyExchange

        transferJob = scope.launch(Dispatchers.IO) {
            try {
                // 1. Resolve file metadata
                val (fileName, fileSize) = getFileMetadata(fileUri)
                val fileBytes = context.contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Cannot read selected file")

                // 2. Generate Ephemeral Keys
                localKeyPair = SonicCrypto.generateEphemeralKeyPair()
                sessionId = Random.nextInt(100000, 999999)
                val localPubBytes = localKeyPair!!.public.encoded

                // 3. Modulate and transmit SYN packet
                val synWire = NativeDspBridge.encodePacket(
                    PacketType.SYN,
                    sessionId,
                    0,
                    localPubBytes
                )
                val synAudio = NativeDspBridge.modulateRawBytes(synWire)

                // Loop sending SYN until SYN_ACK received
                audioController.startListening()
                var receiverPubKeyBytes: ByteArray? = null
                var attempts = 0

                while (isActive && receiverPubKeyBytes == null && attempts < 10) {
                    audioController.playAudio(synAudio)
                    attempts++

                    // Listen for SYN_ACK response
                    val waitStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - waitStart < 2500) {
                        val packet = NativeDspBridge.pollDecodedPacket(ProtocolConstants.MAX_WIRE_PACKET_SIZE)
                        if (packet != null && packet.packetType == PacketType.SYN_ACK && packet.sessionId == sessionId) {
                            receiverPubKeyBytes = packet.payload
                            break
                        }
                        delay(50)
                    }
                }

                if (receiverPubKeyBytes == null) {
                    throw IllegalStateException("Receiver did not respond. Check distance and audio volume.")
                }

                // 4. Derive Shared Session Key
                val sharedSecret = SonicCrypto.computeSharedSecret(localKeyPair!!, receiverPubKeyBytes)
                sessionKey = SonicCrypto.hkdfSha256(sharedSecret, null, "SonicDropV1".toByteArray())

                // 5. SAS Verification
                val sas = SonicCrypto.generateSasCode(sessionKey!!, sessionId)
                _phase.value = TransferPhase.AuthVerification(sas)

                // Wait for user confirmation
                while (!authConfirmed && isActive) {
                    delay(100)
                }

                // Transmit AUTH confirmation packet
                val authWire = NativeDspBridge.encodePacket(PacketType.AUTH, sessionId, 1, "OK".toByteArray())
                val authAudio = NativeDspBridge.modulateRawBytes(authWire)
                audioController.playAudio(authAudio)

                // 6. Transmit Encrypted Metadata
                val sha256 = SonicCrypto.computeSha256(fileBytes)
                val metaJson = "$fileName|$fileSize|$sha256"
                val metaNonce = SonicCrypto.constructNonce(sessionId, 2, isSender = true)
                val encMeta = SonicCrypto.encryptPayload(sessionKey!!, metaNonce, metaJson.toByteArray())

                val metaWire = NativeDspBridge.encodePacket(PacketType.METADATA, sessionId, 2, encMeta)
                val metaAudio = NativeDspBridge.modulateRawBytes(metaWire)
                audioController.playAudio(metaAudio)
                delay(200)

                // 7. Chunk and Transmit Data Packets
                val chunkSize = 64 // 64 bytes per payload for optimal ultrasonic air reliability
                val totalChunks = ((fileBytes.size + chunkSize - 1) / chunkSize)
                var bytesSent = 0L
                val startTime = System.currentTimeMillis()

                for (chunkIdx in 0 until totalChunks) {
                    if (!isActive) break

                    val start = chunkIdx * chunkSize
                    val end = minOf(start + chunkSize, fileBytes.size)
                    val chunkPayload = fileBytes.copyOfRange(start, end)

                    val seqNum = 10 + chunkIdx
                    val nonce = SonicCrypto.constructNonce(sessionId, seqNum, isSender = true)
                    val encPayload = SonicCrypto.encryptPayload(sessionKey!!, nonce, chunkPayload)

                    val chunkWire = NativeDspBridge.encodePacket(PacketType.DATA, sessionId, seqNum, encPayload)
                    val chunkAudio = NativeDspBridge.modulateRawBytes(chunkWire)
                    audioController.playAudio(chunkAudio)

                    bytesSent += (end - start)
                    val elapsedSec = maxOf(0.1f, (System.currentTimeMillis() - startTime) / 1000.0f)
                    val speed = bytesSent / elapsedSec

                    _phase.value = TransferPhase.InProgress(
                        fileName = fileName,
                        totalBytes = fileSize,
                        bytesTransferred = bytesSent,
                        percent = (bytesSent.toFloat() / fileSize.toFloat()),
                        currentSpeedBps = speed,
                        packetIndex = chunkIdx + 1,
                        totalPackets = totalChunks,
                        snrDb = audioController.currentSnr.value
                    )

                    delay(30)
                }

                // 8. Transmit FIN
                val finWire = NativeDspBridge.encodePacket(PacketType.FIN, sessionId, 9999, "FIN".toByteArray())
                val finAudio = NativeDspBridge.modulateRawBytes(finWire)
                audioController.playAudio(finAudio)

                _phase.value = TransferPhase.Success(fileName, fileSize, null)
            } catch (e: Exception) {
                _phase.value = TransferPhase.Failed(e.message ?: "Transfer encountered an error")
            } finally {
                audioController.stopListening()
            }
        }
    }

    /**
     * Starts Receiver Workflow: Listens for acoustic SYN beacon, completes key exchange,
     * verifies SAS, decrypts incoming chunks, and writes verified file to internal storage.
     */
    fun startReceiving() {
        cancelTransfer()
        _role.value = TransferRole.Receiver
        _phase.value = TransferPhase.ListeningForPeer

        val ok = audioController.startListening()
        if (!ok) {
            _phase.value = TransferPhase.Failed("Cannot access microphone or unsupported sample rate")
            return
        }

        transferJob = scope.launch(Dispatchers.IO) {
            try {
                // 1. Listen for SYN
                var synPacket: Packet? = null
                while (isActive && synPacket == null) {
                    val p = NativeDspBridge.pollDecodedPacket(ProtocolConstants.MAX_WIRE_PACKET_SIZE)
                    if (p != null && p.packetType == PacketType.SYN) {
                        synPacket = p
                        break
                    }
                    delay(50)
                }

                val remotePubBytes = synPacket!!.payload
                sessionId = synPacket.sessionId
                _phase.value = TransferPhase.KeyExchange

                // 2. Respond with SYN_ACK
                localKeyPair = SonicCrypto.generateEphemeralKeyPair()
                val localPubBytes = localKeyPair!!.public.encoded

                val synAckWire = NativeDspBridge.encodePacket(PacketType.SYN_ACK, sessionId, 0, localPubBytes)
                val synAckAudio = NativeDspBridge.modulateRawBytes(synAckWire)
                audioController.playAudio(synAckAudio)

                // 3. Derive Shared Session Key
                val sharedSecret = SonicCrypto.computeSharedSecret(localKeyPair!!, remotePubBytes)
                sessionKey = SonicCrypto.hkdfSha256(sharedSecret, null, "SonicDropV1".toByteArray())

                // 4. SAS Verification
                val sas = SonicCrypto.generateSasCode(sessionKey!!, sessionId)
                _phase.value = TransferPhase.AuthVerification(sas)

                while (!authConfirmed && isActive) {
                    delay(100)
                }

                // 5. Receive File Metadata
                var fileName = "downloaded_file"
                var totalFileSize = 0L
                var expectedSha256 = ""

                while (isActive) {
                    val p = NativeDspBridge.pollDecodedPacket(ProtocolConstants.MAX_WIRE_PACKET_SIZE)
                    if (p != null && p.packetType == PacketType.METADATA && p.sessionId == sessionId) {
                        val nonce = SonicCrypto.constructNonce(sessionId, p.sequenceNum, isSender = true)
                        val decMeta = SonicCrypto.decryptPayload(sessionKey!!, nonce, p.payload)
                        val metaParts = String(decMeta).split("|")
                        if (metaParts.size >= 3) {
                            fileName = metaParts[0]
                            totalFileSize = metaParts[1].toLongOrNull() ?: 0L
                            expectedSha256 = metaParts[2]
                            break
                        }
                    }
                    delay(50)
                }

                // 6. Receive and Decrypt File Chunks
                val receivedBytes = mutableListOf<Byte>()
                val startTime = System.currentTimeMillis()
                var isCompleted = false

                while (isActive && !isCompleted) {
                    val p = NativeDspBridge.pollDecodedPacket(ProtocolConstants.MAX_WIRE_PACKET_SIZE)
                    if (p != null && p.sessionId == sessionId) {
                        if (p.packetType == PacketType.DATA) {
                            val nonce = SonicCrypto.constructNonce(sessionId, p.sequenceNum, isSender = true)
                            val plain = SonicCrypto.decryptPayload(sessionKey!!, nonce, p.payload)
                            plain.forEach { receivedBytes.add(it) }

                            val elapsedSec = maxOf(0.1f, (System.currentTimeMillis() - startTime) / 1000.0f)
                            val speed = receivedBytes.size / elapsedSec

                            _phase.value = TransferPhase.InProgress(
                                fileName = fileName,
                                totalBytes = totalFileSize,
                                bytesTransferred = receivedBytes.size.toLong(),
                                percent = if (totalFileSize > 0) (receivedBytes.size.toFloat() / totalFileSize.toFloat()) else 0.5f,
                                currentSpeedBps = speed,
                                packetIndex = p.sequenceNum - 9,
                                totalPackets = if (totalFileSize > 0) ((totalFileSize + 63) / 64).toInt() else 1,
                                snrDb = p.snrDb
                            )
                        } else if (p.packetType == PacketType.FIN) {
                            isCompleted = true
                            break
                        }
                    }
                    delay(40)
                }

                // 7. Integrity Validation
                val reconstructedBytes = receivedBytes.toByteArray()
                val actualSha256 = SonicCrypto.computeSha256(reconstructedBytes)

                if (expectedSha256.isNotEmpty() && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    throw IllegalStateException("File integrity check failed (SHA-256 mismatch)")
                }

                // Save to downloads directory
                val targetFile = File(context.getExternalFilesDir(null), fileName)
                FileOutputStream(targetFile).use { it.write(reconstructedBytes) }

                _phase.value = TransferPhase.Success(fileName, reconstructedBytes.size.toLong(), targetFile.absolutePath)
            } catch (e: Exception) {
                _phase.value = TransferPhase.Failed(e.message ?: "Failed receiving transmission")
            } finally {
                audioController.stopListening()
            }
        }
    }

    private fun getFileMetadata(uri: Uri): Pair<String, Long> {
        var name = "sonic_file_${System.currentTimeMillis()}"
        var size = 0L

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
        return Pair(name, size)
    }
}
