#include "fec/reed_solomon.h"
#include <algorithm>

namespace sonicdrop {
namespace fec {

void ReedSolomon::initGaloisField() {
    // Primitive polynomial: x^8 + x^4 + x^3 + x^2 + 1 (0x11d)
    uint32_t poly = 0x11d;
    uint32_t x = 1;

    for (int i = 0; i < 255; ++i) {
        gfExp_[i] = static_cast<uint8_t>(x);
        gfLog_[x] = static_cast<uint8_t>(i);
        x <<= 1;
        if (x & 0x100) {
            x ^= poly;
        }
    }
    for (int i = 255; i < 512; ++i) {
        gfExp_[i] = gfExp_[i - 255];
    }
    gfLog_[0] = 0; // Not strictly defined, but sentinel
}

uint8_t ReedSolomon::gfMul(uint8_t a, uint8_t b) const {
    if (a == 0 || b == 0) return 0;
    return gfExp_[gfLog_[a] + gfLog_[b]];
}

uint8_t ReedSolomon::gfDiv(uint8_t a, uint8_t b) const {
    if (a == 0) return 0;
    if (b == 0) return 0; // Avoid divide by zero
    return gfExp_[(gfLog_[a] + 255 - gfLog_[b]) % 255];
}

uint8_t ReedSolomon::gfPow(uint8_t a, int power) const {
    if (a == 0) return 0;
    int p = (gfLog_[a] * power) % 255;
    if (p < 0) p += 255;
    return gfExp_[p];
}

uint8_t ReedSolomon::gfInverse(uint8_t a) const {
    if (a == 0) return 0;
    return gfExp_[255 - gfLog_[a]];
}

std::vector<uint8_t> ReedSolomon::polyMul(const std::vector<uint8_t>& p1, const std::vector<uint8_t>& p2) const {
    std::vector<uint8_t> result(p1.size() + p2.size() - 1, 0);
    for (size_t i = 0; i < p1.size(); ++i) {
        for (size_t j = 0; j < p2.size(); ++j) {
            result[i + j] ^= gfMul(p1[i], p2[j]);
        }
    }
    return result;
}

uint8_t ReedSolomon::polyEval(const std::vector<uint8_t>& poly, uint8_t x) const {
    uint8_t y = 0;
    for (size_t i = 0; i < poly.size(); ++i) {
        y = gfMul(y, x) ^ poly[i];
    }
    return y;
}

void ReedSolomon::initGeneratorPoly() {
    genPoly_ = {1};
    for (int i = 0; i < nsym_; ++i) {
        // Multiply by (x - alpha^i) = (x + alpha^i)
        std::vector<uint8_t> term = {1, gfExp_[i]};
        genPoly_ = polyMul(genPoly_, term);
    }
}

ReedSolomon::ReedSolomon(int parityBytes)
    : nsym_(parityBytes) {
    initGaloisField();
    initGeneratorPoly();
}

void ReedSolomon::encode(const uint8_t* data, size_t dataLen, std::vector<uint8_t>& codewordOut) {
    if (data == nullptr || dataLen == 0) return;

    codewordOut.resize(dataLen + nsym_, 0);
    std::copy(data, data + dataLen, codewordOut.begin());

    // Compute polynomial remainder
    std::vector<uint8_t> remainder(nsym_, 0);

    for (size_t i = 0; i < dataLen; ++i) {
        uint8_t feedback = data[i] ^ remainder[0];
        if (feedback != 0) {
            for (int j = 0; j < nsym_ - 1; ++j) {
                remainder[j] = remainder[j + 1] ^ gfMul(genPoly_[j + 1], feedback);
            }
            remainder[nsym_ - 1] = gfMul(genPoly_[nsym_], feedback);
        } else {
            for (int j = 0; j < nsym_ - 1; ++j) {
                remainder[j] = remainder[j + 1];
            }
            remainder[nsym_ - 1] = 0;
        }
    }

    // Append parity bytes to output codeword
    for (int i = 0; i < nsym_; ++i) {
        codewordOut[dataLen + i] = remainder[i];
    }
}

bool ReedSolomon::decode(const uint8_t* codeword, size_t codewordLen, std::vector<uint8_t>& dataOut, int* correctedCountOut) {
    if (codeword == nullptr || codewordLen <= static_cast<size_t>(nsym_)) {
        return false;
    }

    size_t dataLen = codewordLen - nsym_;
    std::vector<uint8_t> work(codeword, codeword + codewordLen);

    // 1. Calculate syndromes: S_i = polyEval(codeword, alpha^i) for i = 0..(nsym - 1)
    std::vector<uint8_t> syndromes(nsym_, 0);
    bool hasErrors = false;

    for (int i = 0; i < nsym_; ++i) {
        uint8_t s = 0;
        uint8_t alpha_i = gfExp_[i];
        for (size_t j = 0; j < codewordLen; ++j) {
            s = gfMul(s, alpha_i) ^ work[j];
        }
        syndromes[i] = s;
        if (s != 0) hasErrors = true;
    }

    if (!hasErrors) {
        // No errors detected
        dataOut.assign(work.begin(), work.begin() + dataLen);
        if (correctedCountOut != nullptr) *correctedCountOut = 0;
        return true;
    }

    // 2. Berlekamp-Massey algorithm to find error locator polynomial Lambda(x)
    std::vector<uint8_t> lambda = {1};
    std::vector<uint8_t> b = {1};
    int l = 0;
    int m = 1;

    for (int n = 0; n < nsym_; ++n) {
        uint8_t delta = syndromes[n];
        for (size_t i = 1; i < lambda.size(); ++i) {
            delta ^= gfMul(lambda[lambda.size() - 1 - i], syndromes[n - i]);
        }

        if (delta != 0) {
            std::vector<uint8_t> tLambda = lambda;
            std::vector<uint8_t> shiftedB(b.size() + m, 0);
            for (size_t k = 0; k < b.size(); ++k) {
                shiftedB[k] = gfMul(b[k], delta);
            }

            // Align sizes and add
            while (lambda.size() < shiftedB.size()) {
                lambda.insert(lambda.begin(), 0);
            }
            while (shiftedB.size() < lambda.size()) {
                shiftedB.insert(shiftedB.begin(), 0);
            }
            for (size_t k = 0; k < lambda.size(); ++k) {
                lambda[k] ^= shiftedB[k];
            }

            if (2 * l <= n) {
                l = n + 1 - l;
                b = tLambda;
                uint8_t invDelta = gfInverse(delta);
                for (size_t k = 0; k < b.size(); ++k) {
                    b[k] = gfMul(b[k], invDelta);
                }
                m = 1;
            } else {
                m++;
            }
        } else {
            m++;
        }
    }

    int numErrors = l;
    if (numErrors * 2 > nsym_) {
        // Too many errors to correct
        return false;
    }

    // 3. Chien search to find roots of Lambda(x)
    std::vector<int> errorPositions;
    for (size_t i = 0; i < codewordLen; ++i) {
        // Test X = alpha^-(codewordLen - 1 - i)
        uint8_t x = gfExp_[(255 - (codewordLen - 1 - i)) % 255];
        uint8_t eval = polyEval(lambda, x);
        if (eval == 0) {
            errorPositions.push_back(static_cast<int>(i));
        }
    }

    if (errorPositions.size() != static_cast<size_t>(numErrors)) {
        // Number of roots does not match error polynomial degree -> uncorrectable
        return false;
    }

    // 4. Forney algorithm to calculate error values
    // Omega(x) = (Syndromes(x) * Lambda(x)) mod x^nsym
    std::vector<uint8_t> revSyndromes = syndromes;
    std::reverse(revSyndromes.begin(), revSyndromes.end());
    std::vector<uint8_t> omega = polyMul(revSyndromes, lambda);
    if (omega.size() > static_cast<size_t>(nsym_)) {
        omega.erase(omega.begin(), omega.end() - nsym_);
    }

    for (int pos : errorPositions) {
        uint8_t x = gfExp_[(255 - (codewordLen - 1 - pos)) % 255];
        uint8_t xInv = gfExp_[(codewordLen - 1 - pos) % 255];

        uint8_t num = polyEval(omega, xInv);

        // Compute formal derivative of Lambda(x)
        uint8_t denom = 0;
        for (size_t j = 1; j < lambda.size(); j += 2) {
            denom ^= gfMul(lambda[lambda.size() - 1 - j], gfPow(xInv, static_cast<int>(j - 1)));
        }

        if (denom == 0) return false;
        uint8_t errVal = gfDiv(num, denom);
        work[pos] ^= errVal;
    }

    dataOut.assign(work.begin(), work.begin() + dataLen);
    if (correctedCountOut != nullptr) {
        *correctedCountOut = numErrors;
    }
    return true;
}

} // namespace fec
} // namespace sonicdrop
