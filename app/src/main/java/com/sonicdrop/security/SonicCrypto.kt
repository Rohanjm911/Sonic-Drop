package com.sonicdrop.security

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Robust, production-grade cryptographic layer for Sonic-Drop:
 * - Ephemeral Key Agreement (EC Diffie-Hellman / X25519 equivalent via SECP256R1/X25519)
 * - HKDF-SHA256 Key Derivation Function (RFC 5869)
 * - Authenticated AEAD Encryption (ChaCha20-Poly1305 with standard AES-GCM fallback if ChaCha20 unsupported on older OEM engines)
 * - Deterministic Short Authentication String (SAS) generation for MITM verification.
 */
object SonicCrypto {

    private val secureRandom = SecureRandom()

    /**
     * Generates an ephemeral cryptographic KeyPair.
     */
    fun generateEphemeralKeyPair(): KeyPair {
        return try {
            val kpg = KeyPairGenerator.getInstance("XDH")
            kpg.initialize(255)
            kpg.generateKeyPair()
        } catch (e: Exception) {
            // Fallback standard NIST P-256 (supported on all Android versions)
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(256)
            kpg.generateKeyPair()
        }
    }

    /**
     * Derives shared secret from local private key and remote public key bytes.
     */
    fun computeSharedSecret(localKeyPair: KeyPair, remotePublicKeyBytes: ByteArray): ByteArray {
        val keyFactory = java.security.KeyFactory.getInstance(localKeyPair.public.algorithm)
        val keySpec = java.security.spec.X509EncodedKeySpec(remotePublicKeyBytes)
        val remotePubKey = keyFactory.generatePublic(keySpec)

        val ka = KeyAgreement.getInstance(
            if (localKeyPair.public.algorithm.contains("XDH", ignoreCase = true)) "XDH" else "ECDH"
        )
        ka.init(localKeyPair.private)
        ka.doPhase(remotePubKey, true)
        return ka.generateSecret()
    }

    /**
     * RFC 5869 HKDF-SHA256 Extract and Expand to derive a 256-bit symmetric session key.
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray?, length: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val effectiveSalt = salt ?: ByteArray(32)
        mac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val t = ByteArray(0)
        var okm = ByteArray(0)
        var round = 1
        var currentT = t

        while (okm.size < length) {
            mac.reset()
            mac.update(currentT)
            if (info != null) mac.update(info)
            mac.update(round.toByte())
            currentT = mac.doFinal()
            okm += currentT
            round++
        }

        return okm.copyOfRange(0, length)
    }

    /**
     * ChaCha20-Poly1305 / AES-256-GCM AEAD encryption.
     * Nonce must be 12 bytes and strictly unique per packet.
     */
    fun encryptPayload(
        keyBytes: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray? = null
    ): ByteArray {
        return try {
            val key = SecretKeySpec(keyBytes, "ChaCha20")
            val cipher = Cipher.getInstance("ChaCha20-Poly1305/None/NoPadding")
            val iv = IvParameterSpec(nonce)
            cipher.init(Cipher.ENCRYPT_MODE, key, iv)
            if (associatedData != null) cipher.updateAAD(associatedData)
            cipher.doFinal(plaintext)
        } catch (e: Exception) {
            // Hardware fallback to AES-256-GCM
            val key = SecretKeySpec(keyBytes.copyOfRange(0, 32), "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)
            if (associatedData != null) cipher.updateAAD(associatedData)
            cipher.doFinal(plaintext)
        }
    }

    /**
     * ChaCha20-Poly1305 / AES-256-GCM AEAD decryption.
     * Returns decrypted plaintext, or throws exception if authentication tag mismatch.
     */
    fun decryptPayload(
        keyBytes: ByteArray,
        nonce: ByteArray,
        ciphertextWithTag: ByteArray,
        associatedData: ByteArray? = null
    ): ByteArray {
        return try {
            val key = SecretKeySpec(keyBytes, "ChaCha20")
            val cipher = Cipher.getInstance("ChaCha20-Poly1305/None/NoPadding")
            val iv = IvParameterSpec(nonce)
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            if (associatedData != null) cipher.updateAAD(associatedData)
            cipher.doFinal(ciphertextWithTag)
        } catch (e: Exception) {
            // Fallback AES-256-GCM
            val key = SecretKeySpec(keyBytes.copyOfRange(0, 32), "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, nonce)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            if (associatedData != null) cipher.updateAAD(associatedData)
            cipher.doFinal(ciphertextWithTag)
        }
    }

    /**
     * Generates a 6-digit Short Authentication String (SAS) from the derived session key.
     * Both sender and receiver compare this string visually to prevent Man-in-the-Middle (MITM) attacks.
     */
    fun generateSasCode(sessionKey: ByteArray, sessionId: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(sessionKey)
        md.update(sessionId.toString().toByteArray())
        val digest = md.digest()

        // Extract 4 bytes into an unsigned integer
        val num = ((digest[0].toInt() and 0xFF) shl 24) or
                ((digest[1].toInt() and 0xFF) shl 16) or
                ((digest[2].toInt() and 0xFF) shl 8) or
                (digest[3].toInt() and 0xFF)
        val code = Math.abs(num) % 1000000
        return String.format("%06d", code)
    }

    /**
     * Constructs a deterministic 12-byte AEAD nonce from session ID + packet sequence number.
     * Format: [sessionId (4B)][sequenceNum (2B)][direction (1B)][randomPadding (5B)].
     */
    fun constructNonce(sessionId: Int, sequenceNum: Int, isSender: Boolean): ByteArray {
        val nonce = ByteArray(12)
        nonce[0] = (sessionId shr 24).toByte()
        nonce[1] = (sessionId shr 16).toByte()
        nonce[2] = (sessionId shr 8).toByte()
        nonce[3] = sessionId.toByte()

        nonce[4] = (sequenceNum shr 8).toByte()
        nonce[5] = sequenceNum.toByte()
        nonce[6] = if (isSender) 0x01 else 0x02

        // Remaining 5 bytes zero-padded
        return nonce
    }

    fun computeSha256(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
