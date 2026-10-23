#include "protocol/packet_codec.h"
#include <cstring>

namespace sonicdrop {
namespace protocol {

// Standard CCITT CRC16 polynomial 0x1021
uint16_t PacketCodec::computeCrc16(const uint8_t* data, size_t length) {
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < length; ++i) {
        crc ^= (static_cast<uint16_t>(data[i]) << 8);
        for (int b = 0; b < 8; ++b) {
            if (crc & 0x8000) {
                crc = (crc << 1) ^ 0x1021;
            } else {
                crc <<= 1;
            }
        }
    }
    return crc;
}

PacketCodec::PacketCodec()
    : rsFec_(FEC_PARITY_BYTES) {}

std::vector<uint8_t> PacketCodec::encodePacket(
    uint8_t packetType,
    uint32_t sessionId,
    uint16_t sequenceNum,
    const uint8_t* payload,
    uint16_t payloadLen
) {
    if (payloadLen > MAX_PAYLOAD_SIZE) {
        payloadLen = MAX_PAYLOAD_SIZE;
    }

    PacketHeader header;
    header.magic[0] = 0x53; // 'S'
    header.magic[1] = 0x44; // 'D'
    header.version = 0x01;
    header.packetType = packetType;
    header.sessionId = sessionId;
    header.sequenceNum = sequenceNum;
    header.payloadLen = payloadLen;

    size_t headerSize = sizeof(PacketHeader);
    size_t unencodedSize = headerSize + payloadLen + CRC16_SIZE;
    std::vector<uint8_t> rawPacket(unencodedSize);

    // Copy header
    std::memcpy(rawPacket.data(), &header, headerSize);

    // Copy payload
    if (payloadLen > 0 && payload != nullptr) {
        std::memcpy(rawPacket.data() + headerSize, payload, payloadLen);
    }

    // Compute and append CRC16 over Header + Payload
    uint16_t crc = computeCrc16(rawPacket.data(), headerSize + payloadLen);
    rawPacket[headerSize + payloadLen] = static_cast<uint8_t>((crc >> 8) & 0xFF);
    rawPacket[headerSize + payloadLen + 1] = static_cast<uint8_t>(crc & 0xFF);

    // Apply Reed-Solomon Forward Error Correction
    std::vector<uint8_t> transmissionBytes;
    rsFec_.encode(rawPacket.data(), rawPacket.size(), transmissionBytes);

    return transmissionBytes;
}

bool PacketCodec::decodePacket(
    const uint8_t* rawData,
    size_t rawLen,
    PacketHeader& headerOut,
    std::vector<uint8_t>& payloadOut,
    int* correctedSymbolsOut
) {
    if (rawData == nullptr || rawLen <= static_cast<size_t>(FEC_PARITY_BYTES + sizeof(PacketHeader) + CRC16_SIZE)) {
        return false;
    }

    // 1. Reed-Solomon Error Correction
    std::vector<uint8_t> decodedBytes;
    bool fecSuccess = rsFec_.decode(rawData, rawLen, decodedBytes, correctedSymbolsOut);
    if (!fecSuccess) {
        return false;
    }

    // 2. Validate Size & Magic
    size_t headerSize = sizeof(PacketHeader);
    if (decodedBytes.size() < headerSize + CRC16_SIZE) {
        return false;
    }

    std::memcpy(&headerOut, decodedBytes.data(), headerSize);
    if (headerOut.magic[0] != 0x53 || headerOut.magic[1] != 0x44 || headerOut.version != 0x01) {
        return false;
    }

    // 3. Validate Length Bounds
    if (decodedBytes.size() != headerSize + headerOut.payloadLen + CRC16_SIZE) {
        return false;
    }

    // 4. Validate CRC16
    uint16_t expectedCrc = computeCrc16(decodedBytes.data(), headerSize + headerOut.payloadLen);
    uint16_t receivedCrc = (static_cast<uint16_t>(decodedBytes[headerSize + headerOut.payloadLen]) << 8) |
                           static_cast<uint16_t>(decodedBytes[headerSize + headerOut.payloadLen + 1]);

    if (expectedCrc != receivedCrc) {
        return false;
    }

    // 5. Extract Payload
    if (headerOut.payloadLen > 0) {
        payloadOut.assign(decodedBytes.begin() + headerSize, decodedBytes.begin() + headerSize + headerOut.payloadLen);
    } else {
        payloadOut.clear();
    }

    return true;
}

} // namespace protocol
} // namespace sonicdrop
