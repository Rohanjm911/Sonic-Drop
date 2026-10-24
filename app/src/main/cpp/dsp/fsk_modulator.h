#ifndef SONICDROP_FSK_MODULATOR_H
#define SONICDROP_FSK_MODULATOR_H

#include <vector>
#include <cstdint>
#include <cstddef>
#include <cmath>

namespace sonicdrop {
namespace dsp {

/**
 * FSK Modulator configuration parameters.
 * Supports configurable ultrasonic band (e.g. 18.5kHz - 20.5kHz)
 * or audible fallback band (e.g. 1.5kHz - 3.5kHz) for testing/incompatible devices.
 */
struct FskConfig {
    int sampleRate = 48000;
    
    // Frequencies in Hz (17.4 kHz - 19.2 kHz, strictly within smartphone transducer response)
    float markFrequency = 18600.0f;    // Binary 1
    float spaceFrequency = 19200.0f;   // Binary 0
    float preambleFreq1 = 17400.0f;    // Preamble tone 1 (start tone)
    float preambleFreq2 = 18000.0f;    // Preamble tone 2 (sync tone)
    
    // Timing parameters
    float symbolDurationMs = 10.0f;    // 10ms per symbol = 100 baud
    float preambleToneDurationMs = 40.0f; // 40ms preamble sync tones
    float guardDurationMs = 5.0f;      // 5ms silence guard before/after packet
    
    // Windowing / envelope to eliminate audio clicks/splatter
    float rampDurationMs = 1.0f;       // 1ms cosine ramp at symbol boundaries
    
    int getSamplesPerSymbol() const {
        return static_cast<int>(sampleRate * (symbolDurationMs / 1000.0f));
    }
    
    int getSamplesPerPreambleTone() const {
        return static_cast<int>(sampleRate * (preambleToneDurationMs / 1000.0f));
    }
    
    int getSamplesPerGuard() const {
        return static_cast<int>(sampleRate * (guardDurationMs / 1000.0f));
    }
};

class FskModulator {
public:
    explicit FskModulator(const FskConfig& config = FskConfig());
    ~FskModulator() = default;

    void setConfig(const FskConfig& config);
    const FskConfig& getConfig() const { return config_; }

    /**
     * Modulates raw byte payload into continuous-phase PCM float audio samples.
     * Generates: Guard -> Preamble Tones -> Encoded Bits -> Guard.
     */
    std::vector<float> modulatePacket(const uint8_t* data, size_t length);

    /**
     * Generates a single continuous test tone for hardware frequency probing and SNR testing.
     */
    std::vector<float> generateTestTone(float frequency, float durationSeconds, float amplitude = 0.8f);

private:
    FskConfig config_;
    double phase_{0.0};

    void appendTone(std::vector<float>& buffer, float frequency, int sampleCount, float amplitude = 0.8f, bool ramp = true);
    void appendSilence(std::vector<float>& buffer, int sampleCount);
};

} // namespace dsp
} // namespace sonicdrop

#endif // SONICDROP_FSK_MODULATOR_H
