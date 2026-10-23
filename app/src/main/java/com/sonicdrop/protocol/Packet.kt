package com.sonicdrop.protocol

/**
 * Binary Packet structure exchanged between sender and receiver.
 * 
 * Header layout:
 * - magic: [0x53, 0x44] ('S', 'D')
 * - version: 0x01
 * - packetType: 1 byte
 * - sessionId: 4 bytes
 * - sequenceNum: 2 bytes
 * - payloadLen: 2 bytes
 * Followed by payload and CRC16 checksum, protected by Reed-Solomon (N, K) ECC.
 */
data class Packet(
    val packetType: Int,
    val sessionId: Int,
    val sequenceNum: Int,
    val payload: ByteArray,
    val snrDb: Float = 0.0f,
    val correctedErrors: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Packet
        if (packetType != other.packetType) return false
        if (sessionId != other.sessionId) return false
        if (sequenceNum != other.sequenceNum) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = packetType
        result = 31 * result + sessionId
        result = 31 * result + sequenceNum
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

object PacketType {
    const val SYN = 0x01       // Session Initiation (contains sender ephemeral pubkey)
    const val SYN_ACK = 0x02   // Session Response (contains receiver ephemeral pubkey)
    const val AUTH = 0x03      // Explicit Mutual Authentication Confirmation
    const val METADATA = 0x04  // Encrypted File Metadata (name, total size, sha256)
    const val DATA = 0x05      // Encrypted Chunk Payload + AEAD Tag
    const val ACK = 0x06       // Cumulative / Selective Chunk Acknowledgement
    const val NACK = 0x07      // Chunk Retransmission Request
    const val FIN = 0x08       // Transfer Completed & Finalized
    const val ABORT = 0x09     // Session Cancelled or Security Violation
}

object ProtocolConstants {
    const val MAGIC_0: Byte = 0x53 // 'S'
    const val MAGIC_1: Byte = 0x44 // 'D'
    const val PROTOCOL_VERSION: Byte = 0x01

    const val HEADER_SIZE = 12
    const val CRC16_SIZE = 2
    const val FEC_PARITY_BYTES = 8
    const val MAX_PAYLOAD_SIZE = 128
    const val WIRE_OVERHEAD = HEADER_SIZE + CRC16_SIZE + FEC_PARITY_BYTES

    // Wire packet size for a full 128-byte data chunk: 12 + 128 + 2 + 8 = 150 bytes
    const val MAX_WIRE_PACKET_SIZE = MAX_PAYLOAD_SIZE + WIRE_OVERHEAD
}
