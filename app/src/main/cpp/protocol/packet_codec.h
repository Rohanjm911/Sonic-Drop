#ifndef SONICDROP_PACKET_CODEC_H
#define SONICDROP_PACKET_CODEC_H

#include "fec/reed_solomon.h"
#include <vector>
#include <cstdint>
#include <cstddef>

namespace sonicdrop {
namespace protocol {

#pragma pack(push, 1)
// Binary Wire Header (12 bytes)
struct PacketHeader {
    uint8_t magic[2];     // 0x53 ('S'), 0x44 ('D')
    uint8_t version;      // 0x01
    uint8_t packetType;   // 0x01: SYN, 0x02: SYN_ACK, 0x03: AUTH, 0x04: DATA, 0x05: ACK, 0x06: FIN
    uint32_t sessionId;   // Ephemeral 32-bit session ID
    uint16_t sequenceNum; // Packet sequence number
    uint16_t payloadLen;  // Payload byte length
};
#pragma pack(pop)

// Maximum raw payload size per packet
constexpr size_t MAX_PAYLOAD_SIZE = 128;
// Reed-Solomon parity symbols per packet block
constexpr int FEC_PARITY_BYTES = 8;
// CRC16 size
constexpr size_t CRC16_SIZE = 2;

class PacketCodec {
public:
    PacketCodec();
    ~PacketCodec() = default;

    /**
     * Encodes a packet:
     * [Header (12B)] + [Payload (N bytes)] + [CRC16 (2B)] -> RS FEC Encoding -> Transmission Bytes
     */
    std::vector<uint8_t> encodePacket(
        uint8_t packetType,
        uint32_t sessionId,
        uint16_t sequenceNum,
        const uint8_t* payload,
        uint16_t payloadLen
    );

    /**
     * Decodes and validates a received raw demodulated byte stream.
     * 1. Applies Reed-Solomon error correction
     * 2. Validates Header magic & version
     * 3. Validates CRC16 checksum
     * Returns true on success, false if corrupted/uncorrectable.
     */
    bool decodePacket(
        const uint8_t* rawData,
        size_t rawLen,
        PacketHeader& headerOut,
        std::vector<uint8_t>& payloadOut,
        int* correctedSymbolsOut = nullptr
    );

    static uint16_t computeCrc16(const uint8_t* data, size_t length);

private:
    fec::ReedSolomon rsFec_;
};

} // namespace protocol
} // namespace sonicdrop

#endif // SONICDROP_PACKET_CODEC_H
