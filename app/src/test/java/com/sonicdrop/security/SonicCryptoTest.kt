package com.sonicdrop.security

import org.junit.Assert.*
import org.junit.Test

class SonicCryptoTest {

    @Test
    fun testKeyAgreementAndSharedSecret() {
        // Alice generates keypair
        val aliceKeys = SonicCrypto.generateEphemeralKeyPair()
        // Bob generates keypair
        val bobKeys = SonicCrypto.generateEphemeralKeyPair()

        // Alice computes shared secret using Bob's public key
        val aliceShared = SonicCrypto.computeSharedSecret(aliceKeys, bobKeys.public.encoded)
        // Bob computes shared secret using Alice's public key
        val bobShared = SonicCrypto.computeSharedSecret(bobKeys, aliceKeys.public.encoded)

        assertArrayEquals("Shared secrets must be identical", aliceShared, bobShared)

        // Derive session keys via HKDF-SHA256
        val aliceSessionKey = SonicCrypto.hkdfSha256(aliceShared, null, "SonicDropV1".toByteArray())
        val bobSessionKey = SonicCrypto.hkdfSha256(bobShared, null, "SonicDropV1".toByteArray())

        assertArrayEquals("Session keys must match", aliceSessionKey, bobSessionKey)

        // Verify Short Authentication String (SAS) matching
        val sessionId = 428192
        val aliceSas = SonicCrypto.generateSasCode(aliceSessionKey, sessionId)
        val bobSas = SonicCrypto.generateSasCode(bobSessionKey, sessionId)

        assertEquals("SAS code must match on both sides", aliceSas, bobSas)
        assertEquals(6, aliceSas.length)
    }

    @Test
    fun testAuthenticatedEncryptionDecryption() {
        val keys = SonicCrypto.generateEphemeralKeyPair()
        val keyBytes = keys.private.encoded.copyOfRange(0, 32)
        val nonce = SonicCrypto.constructNonce(sessionId = 12345, sequenceNum = 1, isSender = true)

        val plaintext = "Sonic-Drop Secret Air-gapped Payload".toByteArray()
        val ciphertext = SonicCrypto.encryptPayload(keyBytes, nonce, plaintext)

        assertFalse("Ciphertext must not equal plaintext", plaintext.contentEquals(ciphertext))

        val decrypted = SonicCrypto.decryptPayload(keyBytes, nonce, ciphertext)
        assertArrayEquals("Decrypted payload must match plaintext", plaintext, decrypted)
    }

    @Test
    fun testTamperedCiphertextRejection() {
        val keys = SonicCrypto.generateEphemeralKeyPair()
        val keyBytes = keys.private.encoded.copyOfRange(0, 32)
        val nonce = SonicCrypto.constructNonce(sessionId = 12345, sequenceNum = 1, isSender = true)

        val plaintext = "Tamper test".toByteArray()
        val ciphertext = SonicCrypto.encryptPayload(keyBytes, nonce, plaintext)

        // Tamper with one byte of ciphertext
        ciphertext[0] = (ciphertext[0].toInt() xor 0xFF).toByte()

        try {
            SonicCrypto.decryptPayload(keyBytes, nonce, ciphertext)
            fail("Decryption must fail on tampered ciphertext/auth tag")
        } catch (e: Exception) {
            // Expected cryptographic verification failure
            assertTrue(true)
        }
    }
}
