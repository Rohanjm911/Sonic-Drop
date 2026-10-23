#ifndef SONICDROP_FSK_DEMODULATOR_H
#define SONICDROP_FSK_DEMODULATOR_H

#include "dsp/fsk_modulator.h"
#include "dsp/preamble_detector.h"
#include "dsp/audio_buffer.h"
#include "kiss_fft.h"
#include "kiss_fftr.h"

#include <vector>
#include <cstdint>
#include <cstddef>

namespace sonicdrop {
namespace dsp {

struct DemodulationResult {
    bool success{false};
    std::vector<uint8_t> rawPacketBytes;
    float detectedSnrDb{0.0f};
    int symbolErrorCount{0};
    int consumedSamples{0};
};

class FskDemodulator {
public:
    explicit FskDemodulator(const FskConfig& config = FskConfig());
    ~FskDemodulator();

    void setConfig(const FskConfig& config);
    const FskConfig& getConfig() const { return config_; }

    /**
     * Attempts to detect a preamble and demodulate a framed packet from the audio ring buffer.
     * expectedPacketBytes specifies how many encoded bytes to extract after the preamble.
     */
    DemodulationResult processBuffer(AudioRingBuffer& ringBuffer, size_t expectedPacketBytes);

    /**
     * Direct buffer demodulation method for testing.
     */
    DemodulationResult demodulateSamples(const float* samples, size_t sampleCount, size_t expectedPacketBytes);

    /**
     * Computes Fast Fourier Transform magnitude spectrum of a window of samples using KissFFT.
     * Useful for spectral diagnostics, frequency response checks, and ultrasonic capability probing.
     */
    std::vector<float> computeSpectrum(const float* windowSamples, int nfft);

private:
    FskConfig config_;
    PreambleDetector preambleDetector_;
    GoertzelFilter markFilter_;
    GoertzelFilter spaceFilter_;

    // KissFFT state for spectral diagnostics
    kiss_fftr_cfg fftConfig_{nullptr};
    int currentFftSize_{0};

    void initFilters();
    void releaseFft();
};

} // namespace dsp
} // namespace sonicdrop

#endif // SONICDROP_FSK_DEMODULATOR_H
