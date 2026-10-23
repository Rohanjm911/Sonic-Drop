cmake_minimum_required(VERSION 3.22.1)
project("sonicdrop" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# KissFFT compilation
add_library(kissfft STATIC
    third_party/kissfft/kiss_fft.c
    third_party/kissfft/kiss_fftr.c
)
target_include_directories(kissfft PUBLIC
    third_party/kissfft
)
target_compile_definitions(kissfft PUBLIC kiss_fft_scalar=float)

# SonicDrop Native DSP & Protocol Engine
add_library(sonicdrop_native SHARED
    native-lib.cpp
    dsp/audio_buffer.cpp
    dsp/fsk_modulator.cpp
    dsp/fsk_demodulator.cpp
    dsp/preamble_detector.cpp
    fec/reed_solomon.cpp
    protocol/packet_codec.cpp
)

target_include_directories(sonicdrop_native PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
    third_party/kissfft
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(sonicdrop_native
    kissfft
    ${log-lib}
    ${android-lib}
)
