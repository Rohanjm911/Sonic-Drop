# 🔊 Sonic-Drop

<p align="center">
  <img src="docs/assets/logo.png" alt="Sonic-Drop Logo" width="180" height="180" />
</p>

<p align="center">
  <b>Near-Ultrasonic Acoustic Air-Gapped Data Transfer Protocol & Android Application</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/Language-Kotlin%20%7C%20C%2B%2B20-blue.svg" alt="Languages" />
  <img src="https://img.shields.io/badge/DSP-Continuous--Phase%20BFSK-orange.svg" alt="Modulation" />
  <img src="https://img.shields.io/badge/FEC-Reed--Solomon%20%2B%20CRC16-purple.svg" alt="FEC" />
  <img src="https://img.shields.io/badge/License-MIT-lightgrey.svg" alt="License" />
</p>

---

## 📌 Overview

**Sonic-Drop** is an offline, air-gapped data transfer engine that communicates device-to-device entirely over acoustic sound waves in the near-ultrasonic frequency band (18 kHz - 20 kHz). 

It operates completely without Wi-Fi, Bluetooth, NFC, or cellular connectivity—making it completely impervious to radio frequency jamming and isolated from IP networks.

---

## ✨ Features

- 📶 **Zero RF / Zero Network**: Completely offline. Transmits data using speaker audio output and microphone recording.
- ⚡ **High-Performance C++20 DSP Core**: Tone synthesis, phase accumulator, matched filtering, and spectral energy analysis compiled to native machine code via Android NDK.
- 🎶 **Continuous-Phase Binary FSK (CP-FSK)**: Clean tone transitions without phase discontinuities to eliminate audible clicks and maximize spectral efficiency.
- 🛡️ **Reed-Solomon FEC + CRC16**: Automatic burst error correction over noisy acoustic channels with strict frame checksum verification.
- 🎯 **Dual-Tone Preamble Detection**: Robust sliding Goertzel filter for frame synchronization and accurate start-of-packet alignment.
- 📊 **Real-time Spectral Diagnostics**: Integrated KissFFT engine for FFT magnitude analysis and SNR (Signal-to-Noise Ratio) estimation.
- 📱 **Clean Kotlin Architecture**: Idiomatic Kotlin JNI bindings (`NativeDspBridge`) with structured asynchronous audio pipeline.

---

## ⚡ Performance & Transfer Characteristics

Acoustic communication propagates data entirely through **sound waves** in the near-ultrasonic spectrum (18.5 kHz – 20.5 kHz) using low-latency physical audio drivers. Because it operates within a tight ~2 kHz acoustic slice to maintain human inaudibility and withstand room reverberation, its throughput characteristics are optimized for **high-security credentials, cryptographic keys, and air-gapped text data**:

### 📊 Physical Layer Throughput:
* **Modulation Scheme**: Continuous-Phase Binary FSK (CP-FSK)
* **Symbol Duration**: 10 ms per symbol (100 Baud)
* **Raw Acoustic Speed**: ~100 bits/sec (~12.5 Bytes/sec)
* **Net Effective Speed**: **~8 to 10 Bytes/sec** (after Preamble, Reed-Solomon (N,K) FEC, CRC-16, and ChaCha20-Poly1305 overhead)

### ⏱️ Estimated Transfer Times by Payload Size:

| Payload Type | Typical Size | Transfer Time | Practical Use Case |
| :--- | :--- | :--- | :--- |
| **X25519 Session Handshake + SAS** | ~32 – 64 B | **~4 – 6 Seconds** | ✅ Seamless mutual verification |
| **OTP / Passwords / 2FA Tokens** | 6 – 16 B | **~2 – 3 Seconds** | ✅ Instant air-gapped MFA |
| **Crypto Wallet Seed / Private Key** | 32 – 128 B | **~8 – 15 Seconds** | ✅ Cold storage wallet recovery |
| **Encrypted Short Text / Notes** | 250 – 500 B | **~30 – 60 Seconds** | ✅ Zero-network secret memos |
| **Small File / Contact Card** | 1 KB – 2 KB | **~1.5 – 3.5 Minutes** | ✅ Offline transfer |
| **Large Files / Media (MBs)** | > 100 KB | **Hours (Impractical)**| ❌ *Use RF protocols (Wi-Fi Direct)* |

> [!NOTE]  
> Sonic-Drop is purposely engineered for **military-grade air-gapped security, classified credential transfer, and zero-RF device pairing** where total radio silence (No Wi-Fi, No Bluetooth, No Cellular) is required. Large multimedia files (MBs) are not suited for ultrasonic sound-wave transmission due to acoustic physical limits.

---

## 🏗️ Architecture

```
               [ User Data / Payload ]
                         │
                         ▼
        ┌──────────────────────────────────┐
        │        Packet Formatter          │  (Type, Session ID, Sequence No)
        └──────────────────────────────────┘
                         │
                         ▼
        ┌──────────────────────────────────┐
        │     CRC-16 & Reed-Solomon        │  (Error detection & FEC redundancy)
        └──────────────────────────────────┘
                         │
                         ▼
        ┌──────────────────────────────────┐
        │     CP-FSK Native Modulator      │  (18 kHz Mark / 19 kHz Space)
        └──────────────────────────────────┘
                         │
                         ▼
            [ Device Speaker / Transmit ]
                         ~ 🔊 ~  (Acoustic Channel)
           [ Device Microphone / Receive ]
                         │
                         ▼
        ┌──────────────────────────────────┐
        │   Lock-free Audio Ring Buffer    │
        └──────────────────────────────────┘
                         │
                         ▼
        ┌──────────────────────────────────┐
        │    Preamble Detector (Goertzel)  │  (Sync detection & Energy peak)
        └──────────────────────────────────┘
                         │
                         ▼
        ┌──────────────────────────────────┐
        │       CP-FSK Demodulator         │  (Symbol decision & bit extraction)
        └──────────────────────────────────┘
                         │
                         ▼
        ┌──────────────────────────────────┐
        │   Reed-Solomon Decode & Verify   │  (Error correction & CRC check)
        └──────────────────────────────────┘
                         │
                         ▼
             [ Verified Output Data ]
```

---

## 📂 Project Structure

```
SONIC-DROP/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── cpp/                      # Native C++ DSP Core
│   │       │   ├── dsp/                  # FSK Modulator, Demodulator, Audio Buffer, Preamble Detector
│   │       │   ├── fec/                  # Reed-Solomon Codec
│   │       │   ├── protocol/             # Packet serialization & CRC16
│   │       │   ├── third_party/kissfft/  # KissFFT spectral analysis library
│   │       │   ├── CMakeLists.txt        # Native build config
│   │       │   └── native-lib.cpp        # JNI Export interfaces
│   │       └── java/com/sonicdrop/
│   │           ├── audio/                # NativeDspBridge Kotlin JNI wrapper
│   │           └── protocol/             # Packet models & enums
│   └── build.gradle.kts
├── docs/
│   └── assets/                           # Logo and architectural diagrams
├── SonicDrop-debug.apk                   # Pre-compiled ready-to-install Android APK
└── README.md
```

---

## 📲 Direct APK Download & Installation

You can directly download and install the pre-compiled APK from the root of this repository:

📥 **[Download SonicDrop-debug.apk (Direct Link)](SonicDrop-debug.apk)**

Or install directly onto a connected physical Android device via ADB:
```bash
adb install -r SonicDrop-debug.apk
```

---

## 🚀 Getting Started

### Prerequisites
- **Android Studio**: Hedgehog (2023.1.1) or newer
- **Android NDK**: Version `25.x` or higher
- **CMake**: Version `3.22.1` or higher
- **Target SDK**: Android 14 (API 34)
- **Minimum SDK**: Android 8.0 (API 26)

### Build from Source
1. Clone the repository:
   ```bash
   git clone https://github.com/Rohanjm911/Sonic-Drop.git
   cd Sonic-Drop
   ```
2. Open the project in Android Studio.
3. Allow Gradle to sync and download necessary NDK/CMake components.
4. Run the app or build the APK using:
   ```bash
   ./gradlew assembleDebug
   ```
   The built APK will be located at `app/build/outputs/apk/debug/app-debug.apk` and root `SonicDrop-debug.apk`.

---

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.