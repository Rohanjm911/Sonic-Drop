package com.sonicdrop.audio

import com.sonicdrop.protocol.Packet

/**
 * JNI Bridge to high-performance C++ DSP routines:
 * - FSK Modulation & Continuous Phase Tone Synthesis
 * - Preamble Detection & Goertzel Spectral Filtering
 * - Real-time Audio Ring Buffer Management
 * - KissFFT Spectral Analysis
 * - Reed-Solomon (N, K) Forward Error Correction Codec
 */
object NativeDspBridge {

    init {
        System.loadLibrary("sonicdrop_native")
    }

    /**
     * Updates FSK modulation and demodulation parameters.
     */
    external fun setFskConfig(
        sampleRate: Int,
        markFreq: Float,
        spaceFreq: Float,
        preamble1: Float,
        preamble2: Float,
        symbolDurationMs: Float
    )

    /**
     * Generates a pure test tone for audio hardware probing and frequency response diagnostics.
     */
    external fun generateTestTone(
        frequency: Float,
        durationSeconds: Float,
        amplitude: Float
    ): FloatArray

    /**
     * Modulates raw binary bytes into continuous-phase PCM float audio waveform.
     */
    external fun modulateRawBytes(data: ByteArray): FloatArray

    /**
     * Packages packet fields into binary wire frame with CRC16 and Reed-Solomon FEC.
     */
    external fun encodePacket(
        packetType: Int,
        sessionId: Int,
        sequenceNum: Int,
        payload: ByteArray?
    ): ByteArray

    /**
     * Feeds incoming microphone PCM float samples into the native ring buffer.
     */
    external fun pushAudioSamples(samples: FloatArray, count: Int)

    /**
     * Clears rx ring buffer on session restart/abort.
     */
    external fun clearRxBuffer()

    /**
     * Polls the native ring buffer for a preamble, demodulates bits, and validates
     * Reed-Solomon FEC and CRC16. Returns a verified Packet instance or null.
     */
    external fun pollDecodedPacket(expectedWireBytes: Int): Packet?

    /**
     * Computes measured SNR in dB around the target frequency relative to noise floor.
     */
    external fun measureSnr(samples: FloatArray, centerFreq: Float): Float

    /**
     * Computes Fast Fourier Transform magnitude spectrum using KissFFT.
     */
    external fun computeSpectrum(samples: FloatArray, nfft: Int): FloatArray?
}
