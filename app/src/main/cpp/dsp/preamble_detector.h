#ifndef SONICDROP_PREAMBLE_DETECTOR_H
#define SONICDROP_PREAMBLE_DETECTOR_H

#include "dsp/fsk_modulator.h"
#include <vector>
#include <cstddef>

namespace sonicdrop {
namespace dsp {

/**
 * Goertzel algorithm state for single-bin frequency power detection.
 * Highly efficient for real-time tone detection compared to running a full FFT per window.
 */
struct GoertzelFilter {
    float targetFreq{0.0f};
    int sampleRate{48000};
    int windowSize{0};
    float coeff{0.0f};

    void init(float freq, int sRate, int wSize);
    float computePower(const float* samples, int count) const;
};

/**
 * Preamble Detector:
 * Listens for the 4-stage alternating sync sequence [Freq1, Freq2, Freq1, Freq2].
 * Returns sync index when the preamble signature matches with confidence exceeding threshold.
 */
class PreambleDetector {
public:
    explicit PreambleDetector(const FskConfig& config = FskConfig());
    ~PreambleDetector() = default;

    void setConfig(const FskConfig& config);

    /**
     * Scans sample buffer for preamble pattern.
     * If detected, returns the sample offset in the buffer where data symbols begin.
     * Returns -1 if no preamble is found.
     * confidenceOut receives the detected SNR/confidence ratio.
     */
    int detectPreamble(const float* samples, size_t sampleCount, float minConfidenceRatio, float* confidenceOut = nullptr);

    /**
     * Computes measured SNR between target ultrasonic band and background noise floor.
     */
    float measureSignalToNoise(const float* samples, size_t sampleCount, float centerFreq, float bandwidthHz = 3000.0f);

private:
    FskConfig config_;
    GoertzelFilter filterFreq1_;
    GoertzelFilter filterFreq2_;
    GoertzelFilter filterNoiseFloor_;

    void setupFilters();
};

} // namespace dsp
} // namespace sonicdrop

#endif // SONICDROP_PREAMBLE_DETECTOR_H
