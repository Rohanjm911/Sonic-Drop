#include "dsp/fsk_demodulator.h"
#include <cmath>
#include <algorithm>

namespace sonicdrop {
namespace dsp {

FskDemodulator::FskDemodulator(const FskConfig& config)
    : config_(config), preambleDetector_(config) {
    initFilters();
}

FskDemodulator::~FskDemodulator() {
    releaseFft();
}

void FskDemodulator::releaseFft() {
    if (fftConfig_ != nullptr) {
        free(fftConfig_);
        fftConfig_ = nullptr;
        currentFftSize_ = 0;
    }
}

void FskDemodulator::initFilters() {
    int symbolSamples = config_.getSamplesPerSymbol();
    markFilter_.init(config_.markFrequency, config_.sampleRate, symbolSamples);
    spaceFilter_.init(config_.spaceFrequency, config_.sampleRate, symbolSamples);
}

void FskDemodulator::setConfig(const FskConfig& config) {
    config_ = config;
    preambleDetector_.setConfig(config);
    initFilters();
}

DemodulationResult FskDemodulator::processBuffer(AudioRingBuffer& ringBuffer, size_t expectedPacketBytes) {
    DemodulationResult result;
    result.success = false;

    size_t available = ringBuffer.available();
    int symbolSamples = config_.getSamplesPerSymbol();
    int preambleTotalSamples = config_.getSamplesPerPreambleTone() * 4;
    size_t requiredSamples = preambleTotalSamples + (expectedPacketBytes * 8 * symbolSamples);

    if (available < requiredSamples) {
        return result; // Need more audio data
    }

    // Read a lookahead window without consuming
    std::vector<float> lookahead(available);
    ringBuffer.peek(lookahead.data(), available, 0);

    float confidence = 0.0f;
    int dataStartOffset = preambleDetector_.detectPreamble(lookahead.data(), available, 2.0f, &confidence);

    if (dataStartOffset < 0) {
        // No preamble detected in the first chunk, advance by 1 symbol to slide the window
        ringBuffer.advance(symbolSamples);
        return result;
    }

    // Check if we have enough samples after the preamble to decode the full expected packet
    size_t dataSamplesNeeded = expectedPacketBytes * 8 * symbolSamples;
    if (lookahead.size() < static_cast<size_t>(dataStartOffset) + dataSamplesNeeded) {
        // Preamble detected, but tail of packet not yet arrived. Wait for subsequent audio frames.
        return result;
    }

    // Demodulate the data bits
    const float* dataPtr = lookahead.data() + dataStartOffset;
    result.rawPacketBytes.reserve(expectedPacketBytes);

    int bitIndex = 0;
    uint8_t currentByte = 0;

    for (size_t i = 0; i < expectedPacketBytes * 8; ++i) {
        const float* symbolPtr = dataPtr + (i * symbolSamples);
        float markPower = markFilter_.computePower(symbolPtr, symbolSamples);
        float spacePower = spaceFilter_.computePower(symbolPtr, symbolSamples);

        bool bit = (markPower >= spacePower);
        currentByte = (currentByte << 1) | (bit ? 1 : 0);
        bitIndex++;

        if (bitIndex == 8) {
            result.rawPacketBytes.push_back(currentByte);
            currentByte = 0;
            bitIndex = 0;
        }
    }

    result.success = true;
    result.detectedSnrDb = 10.0f * std::log10(std::max(1.0f, confidence));
    result.consumedSamples = dataStartOffset + static_cast<int>(dataSamplesNeeded);

    // Consume the decoded packet samples from the ring buffer
    ringBuffer.advance(result.consumedSamples);

    return result;
}

DemodulationResult FskDemodulator::demodulateSamples(const float* samples, size_t sampleCount, size_t expectedPacketBytes) {
    AudioRingBuffer tempBuffer(sampleCount + 1024);
    tempBuffer.write(samples, sampleCount);
    return processBuffer(tempBuffer, expectedPacketBytes);
}

std::vector<float> FskDemodulator::computeSpectrum(const float* windowSamples, int nfft) {
    std::vector<float> magnitudes;
    if (windowSamples == nullptr || nfft <= 0 || (nfft % 2 != 0)) {
        return magnitudes;
    }

    if (fftConfig_ == nullptr || currentFftSize_ != nfft) {
        releaseFft();
        fftConfig_ = kiss_fftr_alloc(nfft, 0, nullptr, nullptr);
        currentFftSize_ = nfft;
    }

    if (fftConfig_ == nullptr) return magnitudes;

    int outputBins = (nfft / 2) + 1;
    std::vector<kiss_fft_cpx> freqOut(outputBins);

    // Apply Hann window
    std::vector<float> windowed(nfft);
    for (int i = 0; i < nfft; ++i) {
        float hann = 0.5f * (1.0f - std::cos(2.0f * 3.14159265358979323846f * i / (nfft - 1)));
        windowed[i] = windowSamples[i] * hann;
    }

    kiss_fftr(fftConfig_, windowed.data(), freqOut.data());

    magnitudes.resize(outputBins);
    for (int i = 0; i < outputBins; ++i) {
        float r = freqOut[i].r;
        float im = freqOut[i].i;
        magnitudes[i] = std::sqrt((r * r) + (im * im)) / nfft;
    }

    return magnitudes;
}

} // namespace dsp
} // namespace sonicdrop
