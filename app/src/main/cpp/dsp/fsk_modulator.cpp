#include "dsp/fsk_modulator.h"
#include <cmath>

namespace sonicdrop {
namespace dsp {

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

FskModulator::FskModulator(const FskConfig& config)
    : config_(config), phase_(0.0) {}

void FskModulator::setConfig(const FskConfig& config) {
    config_ = config;
    phase_ = 0.0;
}

void FskModulator::appendTone(std::vector<float>& buffer, float frequency, int sampleCount, float amplitude, bool ramp) {
    if (sampleCount <= 0) return;
    double phaseInc = 2.0 * M_PI * frequency / config_.sampleRate;
    int rampSamples = static_cast<int>(config_.sampleRate * (config_.rampDurationMs / 1000.0f));
    if (rampSamples * 2 > sampleCount) {
        rampSamples = sampleCount / 2;
    }

    for (int i = 0; i < sampleCount; ++i) {
        float sample = static_cast<float>(std::sin(phase_) * amplitude);

        // Apply raised-cosine envelope to suppress keying clicks/splatter
        if (ramp && rampSamples > 0) {
            if (i < rampSamples) {
                float factor = 0.5f * (1.0f - std::cos(static_cast<float>(M_PI * i / rampSamples)));
                sample *= factor;
            } else if (i >= sampleCount - rampSamples) {
                int remaining = sampleCount - 1 - i;
                float factor = 0.5f * (1.0f - std::cos(static_cast<float>(M_PI * remaining / rampSamples)));
                sample *= factor;
            }
        }

        buffer.push_back(sample);
        phase_ += phaseInc;
        if (phase_ >= 2.0 * M_PI) {
            phase_ -= 2.0 * M_PI;
        }
    }
}

void FskModulator::appendSilence(std::vector<float>& buffer, int sampleCount) {
    if (sampleCount <= 0) return;
    buffer.insert(buffer.end(), sampleCount, 0.0f);
    phase_ = 0.0;
}

std::vector<float> FskModulator::modulatePacket(const uint8_t* data, size_t length) {
    std::vector<float> audio;
    if (data == nullptr || length == 0) return audio;

    int symbolSamples = config_.getSamplesPerSymbol();
    int preambleSamples = config_.getSamplesPerPreambleTone();
    int guardSamples = config_.getSamplesPerGuard();

    // Estimate total sample count to minimize allocations
    // 2 guards + 4 preamble tones + length * 8 bits
    size_t totalSymbols = 4 + (length * 8);
    size_t estimatedSamples = (guardSamples * 2) + (preambleSamples * 4) + (length * 8 * symbolSamples);
    audio.reserve(estimatedSamples);

    // Initial guard silence
    appendSilence(audio, guardSamples);

    // Preamble sequence: Dual alternating tones [Freq1, Freq2, Freq1, Freq2]
    appendTone(audio, config_.preambleFreq1, preambleSamples, 0.85f, true);
    appendTone(audio, config_.preambleFreq2, preambleSamples, 0.85f, true);
    appendTone(audio, config_.preambleFreq1, preambleSamples, 0.85f, true);
    appendTone(audio, config_.preambleFreq2, preambleSamples, 0.85f, true);

    // Modulate packet bits (MSB first)
    for (size_t byteIdx = 0; byteIdx < length; ++byteIdx) {
        uint8_t byteVal = data[byteIdx];
        for (int bit = 7; bit >= 0; --bit) {
            bool isMark = ((byteVal >> bit) & 1) != 0;
            float freq = isMark ? config_.markFrequency : config_.spaceFrequency;
            appendTone(audio, freq, symbolSamples, 0.85f, true);
        }
    }

    // Trailing guard silence
    appendSilence(audio, guardSamples);

    return audio;
}

std::vector<float> FskModulator::generateTestTone(float frequency, float durationSeconds, float amplitude) {
    std::vector<float> tone;
    int totalSamples = static_cast<int>(config_.sampleRate * durationSeconds);
    if (totalSamples <= 0) return tone;

    tone.reserve(totalSamples);
    double localPhase = 0.0;
    double phaseInc = 2.0 * M_PI * frequency / config_.sampleRate;
    int rampSamples = static_cast<int>(config_.sampleRate * 0.05f); // 50ms smooth fade in/out

    for (int i = 0; i < totalSamples; ++i) {
        float sample = static_cast<float>(std::sin(localPhase) * amplitude);
        if (i < rampSamples) {
            sample *= (static_cast<float>(i) / rampSamples);
        } else if (i >= totalSamples - rampSamples) {
            sample *= (static_cast<float>(totalSamples - 1 - i) / rampSamples);
        }

        tone.push_back(sample);
        localPhase += phaseInc;
        if (localPhase >= 2.0 * M_PI) localPhase -= 2.0 * M_PI;
    }

    return tone;
}

} // namespace dsp
} // namespace sonicdrop
