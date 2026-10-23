#ifndef SONICDROP_AUDIO_BUFFER_H
#define SONICDROP_AUDIO_BUFFER_H

#include <vector>
#include <cstdint>
#include <cstddef>
#include <mutex>
#include <algorithm>

namespace sonicdrop {
namespace dsp {

/**
 * Thread-safe Circular Audio Buffer designed for real-time PCM audio streaming
 * between AudioRecord callbacks / JNI and the DSP demodulation thread.
 */
class AudioRingBuffer {
public:
    explicit AudioRingBuffer(size_t capacitySamples = 48000 * 4); // Default 4 seconds @ 48kHz
    ~AudioRingBuffer() = default;

    // Writes samples into the ring buffer. Overwrites oldest data if overflow occurs.
    void write(const float* data, size_t count);

    // Reads up to count samples. Returns the actual number of samples read.
    size_t read(float* dest, size_t count);

    // Peeks at up to count samples without advancing the read pointer.
    size_t peek(float* dest, size_t count, size_t offset = 0) const;

    // Discards count samples from the read pointer.
    void advance(size_t count);

    // Current number of readable samples.
    size_t available() const;

    // Clears all samples in the buffer.
    void clear();

    size_t capacity() const { return capacity_; }

private:
    size_t capacity_;
    std::vector<float> buffer_;
    size_t head_{0}; // Write index
    size_t tail_{0}; // Read index
    size_t size_{0}; // Stored elements
    mutable std::mutex mutex_;
};

} // namespace dsp
} // namespace sonicdrop

#endif // SONICDROP_AUDIO_BUFFER_H
