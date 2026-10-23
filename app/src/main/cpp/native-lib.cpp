#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "dsp/fsk_modulator.h"
#include "dsp/fsk_demodulator.h"
#include "dsp/audio_buffer.h"
#include "fec/reed_solomon.h"
#include "protocol/packet_codec.h"

#define TAG "SonicDropNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static sonicdrop::dsp::FskModulator g_modulator;
static sonicdrop::dsp::FskDemodulator g_demodulator;
static sonicdrop::dsp::AudioRingBuffer g_rxRingBuffer;
static sonicdrop::protocol::PacketCodec g_packetCodec;
static sonicdrop::fec::ReedSolomon g_reedSolomon(8);

extern "C" {

JNIEXPORT void JNICALL
Java_com_sonicdrop_audio_NativeDspBridge_setFskConfig(
    JNIEnv* env,
    jobject /* thiz */,
    jint sampleRate,
    jfloat markFreq,
    jfloat spaceFreq,
    jfloat preamble1,
    jfloat preamble2,
    jfloat symbolDurationMs
) {
    sonicdrop::dsp::FskConfig config;
    config.sampleRate = sampleRate;
    config.markFrequency = markFreq;
    config.spaceFrequency = spaceFreq;
    config.preambleFreq1 = preamble1;
    config.preambleFreq2 = preamble2;
    config.symbolDurationMs = symbolDurationMs;

    g_modulator.setConfig(config);
    g_demodulator.setConfig(config);
    LOGD("FSK Config updated: Mark=%.1f Hz, Space=%.1f Hz, SymbolDuration=%.1f ms",
         markFreq, spaceFreq, symbolDurationMs);
}

JNIEXPORT jfloatArray JNICALL
Java_com_sonicdrop_audio_NativeDspBridge_generateTestTone(
    JNIEnv* env,
    jobject /* thiz */,
    jfloat frequency,
    jfloat durationSeconds,
    jfloat amplitude
) {
    std::vector<float> tone = g_modulator.generateTestTone(frequency, durationSeconds, amplitude);
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(tone.size()));
    if (result != nullptr && !tone.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(tone.size()), tone.data());
    }
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_sonicdrop_audio_NativeDspBridge_modulateRawBytes(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray data
) {
    if (data == nullptr) return nullptr;
    jsize len = env->GetArrayLength(data);
    std::vector<uint8_t> buffer(len);
    env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte*>(buffer.data()));

    std::vector<float> audio = g_modulator.modulatePacket(buffer.data(), buffer.size());

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(audio.size()));
    if (result != nullptr && !audio.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(audio.size()), audio.data());
    }
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_sonicdrop_audio_NativeDspBridge_encodePacket(
    JNIEnv* env,
    jobject /* thiz */,
    jint packetType,
    jint sessionId,
    jint sequenceNum,
    jbyteArray payload
) {
    const uint8_t* payloadPtr = nullptr;
    uint16_t payloadLen = 0;
    std::vector<uint8_t> payloadBuf;

    if (payload != nullptr) {
        payloadLen = static_cast<uint16_t>(env->GetArrayLength(payload));
        payloadBuf.resize(payloadLen);
        env->GetByteArrayRegion(payload, 0, payloadLen, reinterpret_cast<jbyte*>(payloadBuf.data()));
        payloadPtr = payloadBuf.data();
    }

    std::vector<uint8_t> encoded = g_packetCodec.encodePacket(
        static_cast<uint8_t>(packetType),
        static_cast<uint32_t>(sessionId),
        static_cast<uint16_t>(sequenceNum),
        payloadPtr,
        payloadLen
    );

    jbyteArray result = env->NewByteArray(static_cast<jsize>(encoded.size()));
    if (result != nullptr && !encoded.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(encoded.size()), reinterpret_cast<const jbyte*>(encoded.data()));
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_sonicdrop_audio_NativeDspBridge_pushAudioSamples(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray samples,
    jint count
) {
    if (samples == nullptr || count <= 0) return;
    std::vector<float> buffer(count);
    env->GetFloatArrayRegion(samples, 0, count, buffer.data());
    g_rxRingBuffer.write(buffer.data(), count);
}

JNIEXPORT void JNICALL
Java_com_sonicdrop_audio_NativeDspBridge_clearRxBuffer(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    g_rxRingBuffer.clear();
}

JNIEXPORT jobject JNICALL
Java_com_sonicdrop_audio_NativeDspBridge_pollDecodedPacket(
    JNIEnv* env,
    jobject /* thiz */,
    jint expectedWireBytes
) {
    sonicdrop::dsp::DemodulationResult demResult = g_demodulator.processBuffer(g_rxRingBuffer, expectedWireBytes);
    if (!demResult.success || demResult.rawPacketBytes.empty()) {
        return nullptr;
    }

    sonicdrop::protocol::PacketHeader header;
    std::vector<uint8_t> payload;
    int correctedErrors = 0;

    bool valid = g_packetCodec.decodePacket(
        demResult.rawPacketBytes.data(),
        demResult.rawPacketBytes.size(),
        header,
        payload,
        &correctedErrors
    );

    if (!valid) {
        LOGD("Packet demodulated but CRC/FEC validation failed");
        return nullptr;
    }

    jclass packetClass = env->FindClass("com/sonicdrop/protocol/Packet");
    if (packetClass == nullptr) return nullptr;

    jmethodID ctor = env->GetMethodID(packetClass, "<init>", "(III[BFI)V");
    if (ctor == nullptr) return nullptr;

    jbyteArray payloadArray = env->NewByteArray(static_cast<jsize>(payload.size()));
    if (!payload.empty()) {
        env->SetByteArrayRegion(payloadArray, 0, static_cast<jsize>(payload.size()), reinterpret_cast<const jbyte*>(payload.data()));
    }

    jobject packetObj = env->NewObject(
        packetClass,
        ctor,
        static_cast<jint>(header.packetType),
        static_cast<jint>(header.sessionId),
        static_cast<jint>(header.sequenceNum),
        payloadArray,
        demResult.detectedSnrDb,
        correctedErrors
    );

    return packetObj;
}

JNIEXPORT jfloat JNICALL
Java_com_sonicdrop_audio_NativeDspBridge_measureSnr(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray samples,
    jfloat centerFreq
) {
    if (samples == nullptr) return 0.0f;
    jsize len = env->GetArrayLength(samples);
    std::vector<float> buf(len);
    env->GetFloatArrayRegion(samples, 0, len, buf.data());

    sonicdrop::dsp::PreambleDetector detector(g_demodulator.getConfig());
    return detector.measureSignalToNoise(buf.data(), len, centerFreq);
}

JNIEXPORT jfloatArray JNICALL
Java_com_sonicdrop_audio_NativeDspBridge_computeSpectrum(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray samples,
    jint nfft
) {
    if (samples == nullptr || nfft <= 0) return nullptr;
    jsize len = env->GetArrayLength(samples);
    if (len < nfft) return nullptr;

    std::vector<float> buf(nfft);
    env->GetFloatArrayRegion(samples, 0, nfft, buf.data());

    std::vector<float> spectrum = g_demodulator.computeSpectrum(buf.data(), nfft);
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(spectrum.size()));
    if (result != nullptr && !spectrum.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(spectrum.size()), spectrum.data());
    }
    return result;
}

} // extern "C"
