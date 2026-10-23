#include "dsp/audio_buffer.h"

namespace sonicdrop {
namespace dsp {

AudioRingBuffer::AudioRingBuffer(size_t capacitySamples)
    : capacity_(capacitySamples), buffer_(capacitySamples, 0.0f) {}

void AudioRingBuffer::write(const float* data, size_t count) {
    if (data == nullptr || count == 0) return;
    std::lock_guard<std::mutex> lock(mutex_);

    for (size_t i = 0; i < count; ++i) {
        buffer_[head_] = data[i];
        head_ = (head_ + 1) % capacity_;
        if (size_ < capacity_) {
            size_++;
        } else {
            // Overwrote oldest sample, move tail forward
            tail_ = (tail_ + 1) % capacity_;
        }
    }
}

size_t AudioRingBuffer::read(float* dest, size_t count) {
    if (dest == nullptr || count == 0) return 0;
    std::lock_guard<std::mutex> lock(mutex_);

    size_t toRead = std::min(count, size_);
    for (size_t i = 0; i < toRead; ++i) {
        dest[i] = buffer_[tail_];
        tail_ = (tail_ + 1) % capacity_;
    }
    size_ -= toRead;
    return toRead;
}

size_t AudioRingBuffer::peek(float* dest, size_t count, size_t offset) const {
    if (dest == nullptr || count == 0) return 0;
    std::lock_guard<std::mutex> lock(mutex_);

    if (offset >= size_) return 0;
    size_t availableAfterOffset = size_ - offset;
    size_t toPeek = std::min(count, availableAfterOffset);
    size_t current = (tail_ + offset) % capacity_;

    for (size_t i = 0; i < toPeek; ++i) {
        dest[i] = buffer_[current];
        current = (current + 1) % capacity_;
    }
    return toPeek;
}

void AudioRingBuffer::advance(size_t count) {
    std::lock_guard<std::mutex> lock(mutex_);
    size_t toDiscard = std::min(count, size_);
    tail_ = (tail_ + toDiscard) % capacity_;
    size_ -= toDiscard;
}

size_t AudioRingBuffer::available() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return size_;
}

void AudioRingBuffer::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    head_ = 0;
    tail_ = 0;
    size_ = 0;
}

} // namespace dsp
} // namespace sonicdrop
