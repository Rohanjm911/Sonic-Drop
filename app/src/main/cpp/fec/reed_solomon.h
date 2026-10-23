#ifndef SONICDROP_REED_SOLOMON_H
#define SONICDROP_REED_SOLOMON_H

#include <vector>
#include <cstdint>
#include <cstddef>

namespace sonicdrop {
namespace fec {

/**
 * Reed-Solomon Error Correction Code over Galois Field GF(2^8).
 * Standard generator polynomial: x^8 + x^4 + x^3 + x^2 + 1 (0x11d).
 * 
 * Supports configurable message length k and codeword length n.
 * For example, RS(32, 24) encodes 24 data bytes with 8 parity bytes,
 * capable of correcting up to t = 4 corrupted bytes per block.
 */
class ReedSolomon {
public:
    explicit ReedSolomon(int parityBytes = 8);
    ~ReedSolomon() = default;

    int getParityBytes() const { return nsym_; }

    /**
     * Encodes a message of length dataLen into output Codeword of length (dataLen + nsym).
     */
    void encode(const uint8_t* data, size_t dataLen, std::vector<uint8_t>& codewordOut);

    /**
     * Decodes and corrects errors in a received codeword.
     * Returns true if decoded successfully (uncorrupted or corrected), false if uncorrectable.
     * correctedCountOut optionally returns number of corrected symbol errors.
     */
    bool decode(const uint8_t* codeword, size_t codewordLen, std::vector<uint8_t>& dataOut, int* correctedCountOut = nullptr);

private:
    int nsym_; // Number of parity symbols (2 * t)
    uint8_t gfExp_[512]; // Exponential table
    uint8_t gfLog_[256]; // Logarithm table
    std::vector<uint8_t> genPoly_; // Generator polynomial

    void initGaloisField();
    void initGeneratorPoly();

    uint8_t gfAdd(uint8_t a, uint8_t b) const { return a ^ b; }
    uint8_t gfSub(uint8_t a, uint8_t b) const { return a ^ b; }
    uint8_t gfMul(uint8_t a, uint8_t b) const;
    uint8_t gfDiv(uint8_t a, uint8_t b) const;
    uint8_t gfPow(uint8_t a, int power) const;
    uint8_t gfInverse(uint8_t a) const;

    std::vector<uint8_t> polyMul(const std::vector<uint8_t>& p1, const std::vector<uint8_t>& p2) const;
    uint8_t polyEval(const std::vector<uint8_t>& poly, uint8_t x) const;
};

} // namespace fec
} // namespace sonicdrop

#endif // SONICDROP_REED_SOLOMON_H
