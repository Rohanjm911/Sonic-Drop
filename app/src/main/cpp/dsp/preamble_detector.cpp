#include "dsp/preamble_detector.h"
#include <cmath>
#include <algorithm>

namespace sonicdrop {
namespace dsp {

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

void GoertzelFilter::init(float freq, int sRate, int wSize) {
    targetFreq = freq;
    sampleRate = sRate;
    windowSize = wSize;
    float k = std::floor(0.5f + (static_cast<float>(windowSize) * targetFreq / sampleRate));
    float omega = (2.0f * static_cast<float>(M_PI) / windowSize) * k;
    coeff = 2.0f * std::cos(omega);
}

float GoertzelFilter::computePower(const float* samples, int count) const {
    if (samples == nullptr || count <= 0) return 0.0f;
    int n = std::min(count, windowSize);

    float q0 = 0.0f;
    float q1 = 0.0f;
    float q2 = 0.0f;

    for (int i = 0; i < n; ++i) {
        // Apply Hamming window on the fly
        float hamming = 0.54f - 0.46f * std::cos(2.0f * static_cast<float>(M_PI) * i / (n - 1));
        float sample = samples[i] * hamming;

        q0 = coeff * q1 - q2 + sample;
        q2 = q1;
        q1 = q0;
    }

    float power = (q1 * q1) + (q2 * q2) - (q1 * q2 * coeff);
    return std::max(0.0f, power);
}

PreambleDetector::PreambleDetector(const FskConfig& config)
    : config_(config) {
    setupFilters();
}

void PreambleDetector::setConfig(const FskConfig& config) {
    config_ = config;
    setupFilters();
}

void PreambleDetector::setupFilters() {
    int toneSamples = config_.getSamplesPerPreambleTone();
    filterFreq1_.init(config_.preambleFreq1, config_.sampleRate, toneSamples);
    filterFreq2_.init(config_.preambleFreq2, config_.sampleRate, toneSamples);
    // Noise reference filter at an intermediate offset
    float noiseRefFreq = (config_.preambleFreq1 + config_.preambleFreq2) * 0.5f;
    filterNoiseFloor_.init(noiseRefFreq, config_.sampleRate, toneSamples);
}

int PreambleDetector::detectPreamble(const float* samples, size_t sampleCount, float minConfidenceRatio, float* confidenceOut) {
    if (samples == nullptr) return -1;

    int toneSamples = config_.getSamplesPerPreambleTone();
    int requiredLength = toneSamples * 4; // 4 preamble stages: F1, F2, F1, F2
    if (static_cast<int>(sampleCount) < requiredLength) {
        return -1;
    }

    // Step through the buffer in fractional tone intervals (e.g. 1/4 tone step)
    int stepSize = toneSamples / 4;
    int maxSearchIndex = static_cast<int>(sampleCount) - requiredLength;

    float bestConfidence = 0.0f;
    int bestOffset = -1;

    for (int offset = 0; offset <= maxSearchIndex; offset += stepSize) {
        const float* pTone1 = samples + offset;
        const float* pTone2 = pTone1 + toneSamples;
        const float* pTone3 = pTone2 + toneSamples;
        const float* pTone4 = pTone3 + toneSamples;

        // Stage 1 must be F1 dominant
        float p1_f1 = filterFreq1_.computePower(pTone1, toneSamples);
        float p1_f2 = filterFreq2_.computePower(pTone1, toneSamples);

        // Stage 2 must be F2 dominant
        float p2_f1 = filterFreq1_.computePower(pTone2, toneSamples);
        float p2_f2 = filterFreq2_.computePower(pTone2, toneSamples);

        // Stage 3 must be F1 dominant
        float p3_f1 = filterFreq1_.computePower(pTone3, toneSamples);
        float p3_f2 = filterFreq2_.computePower(pTone3, toneSamples);

        // Stage 4 must be F2 dominant
        float p4_f1 = filterFreq1_.computePower(pTone4, toneSamples);
        float p4_f2 = filterFreq2_.computePower(pTone4, toneSamples);

        float signalSum = p1_f1 + p2_f2 + p3_f1 + p4_f2;
        float crossSum = p1_f2 + p2_f1 + p3_f2 + p4_f1 + 1e-6f;

        float ratio = signalSum / crossSum;

        // Verify all 4 stages showed dominance
        bool stagesMatch = (p1_f1 > p1_f2 * 1.8f) &&
                           (p2_f2 > p2_f1 * 1.8f) &&
                           (p3_f1 > p3_f2 * 1.8f) &&
                           (p4_f2 > p4_f1 * 1.8f);

        if (stagesMatch && ratio > minConfidenceRatio && ratio > bestConfidence) {
            bestConfidence = ratio;
            // The data symbols start immediately after Stage 4
            bestOffset = offset + requiredLength;
        }
    }

    if (confidenceOut != nullptr) {
        *confidenceOut = bestConfidence;
    }

    return bestOffset;
}

float PreambleDetector::measureSignalToNoise(const float* samples, size_t sampleCount, float centerFreq, float bandwidthHz) {
    if (samples == nullptr || sampleCount < 1024) return 0.0f;

    GoertzelFilter centerFilter;
    GoertzelFilter noiseLow;
    GoertzelFilter noiseHigh;

    int window = 512;
    centerFilter.init(centerFreq, config_.sampleRate, window);
    noiseLow.init(std::max(1000.0f, centerFreq - bandwidthHz), config_.sampleRate, window);
    noiseHigh.init(std::min(static_cast<float>(config_.sampleRate) * 0.48f, centerFreq + bandwidthHz), config_.sampleRate, window);

    float signalPower = 0.0f;
    float noisePower = 0.0f;
    int chunks = static_cast<int>(sampleCount / window);

    for (int i = 0; i < chunks; ++i) {
        const float* p = samples + (i * window);
        signalPower += centerFilter.computePower(p, window);
        noisePower += 0.5f * (noiseLow.computePower(p, window) + noiseHigh.computePower(p, window));
    }

    if (noisePower < 1e-6f) noisePower = 1e-6f;
    float snrDb = 10.0f * std::log10(signalPower / noisePower);
    return snrDb;
}

} // namespace dsp
} // namespace sonicdrop
