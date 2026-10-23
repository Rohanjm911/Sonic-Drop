/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Minimal logging stub for KissFFT on Android
 */
#ifndef KISS_FFT_LOG_H
#define KISS_FFT_LOG_H

#include <stdio.h>

#define KISS_FFT_LOG_MSG(severity, ...) do {} while (0)
#define KISS_FFT_ERROR(...) do {} while (0)
#define KISS_FFT_WARNING(...) do {} while (0)
#define KISS_FFT_INFO(...) do {} while (0)
#define KISS_FFT_DEBUG(...) do {} while (0)

#endif // KISS_FFT_LOG_H
