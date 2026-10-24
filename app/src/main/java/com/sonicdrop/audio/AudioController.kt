package com.sonicdrop.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance hardware audio controller for Sonic-Drop:
 * - Real-time non-blocking AudioRecord PCM stream piped into Native DSP Ring Buffer
 * - Low-latency AudioTrack playback for modulated acoustic bursts
 * - Hardware capabilities probing and SNR metrics
 */
class AudioController(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT
    }

    private val isRecording = AtomicBoolean(false)
    private var recordThread: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _currentSnr = MutableStateFlow(0.0f)
    val currentSnr: StateFlow<Float> = _currentSnr.asStateFlow()

    private val _hardwareSupported = MutableStateFlow(true)
    val hardwareSupported: StateFlow<Boolean> = _hardwareSupported.asStateFlow()

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Transmits a modulated float waveform through the device speaker.
     */
    suspend fun playAudio(waveform: FloatArray) = withContext(Dispatchers.IO) {
        if (waveform.isEmpty()) return@withContext

        val minTrackBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG_OUT,
            AUDIO_FORMAT
        )
        val bufferSize = maxOf(minTrackBuffer, waveform.size * 4)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG_OUT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            audioTrack.setVolume(AudioTrack.getMaxVolume())
            audioTrack.write(waveform, 0, waveform.size, AudioTrack.WRITE_BLOCKING)
            audioTrack.play()

            // Wait until playback completes
            val durationMs = (waveform.size * 1000L) / SAMPLE_RATE
            kotlinx.coroutines.delay(durationMs + 60)
        } finally {
            try {
                audioTrack.stop()
                audioTrack.release()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    /**
     * Starts background microphone capture loop feeding into NativeDspBridge ring buffer.
     * Uses UNPROCESSED / VOICE_RECOGNITION to disable speech noise cancellation that
     * suppresses ultrasonic sound waves.
     */
    @SuppressLint("MissingPermission")
    fun startListening(): Boolean {
        if (isRecording.get()) return true
        if (!hasRecordPermission()) return false

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG_IN,
            AUDIO_FORMAT
        )
        val bufferSize = maxOf(minBufferSize, 2048 * 4)

        val sourcesToTry = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            intArrayOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
        } else {
            intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
        }

        var audioRecord: AudioRecord? = null
        for (source in sourcesToTry) {
            try {
                val rec = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG_IN)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = rec
                    break
                } else {
                    rec.release()
                }
            } catch (e: Exception) {
                // Try fallback source
            }
        }

        if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            _hardwareSupported.value = false
            return false
        }

        NativeDspBridge.clearRxBuffer()
        audioRecord.startRecording()
        isRecording.set(true)
        _isListening.value = true

        recordThread = scope.launch(Dispatchers.IO) {
            val readBuffer = FloatArray(1024)
            var snrMeasureCounter = 0

            try {
                while (isActive && isRecording.get()) {
                    val read = audioRecord.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        NativeDspBridge.pushAudioSamples(readBuffer, read)

                        // Measure SNR periodically around 18.6 kHz
                        snrMeasureCounter++
                        if (snrMeasureCounter >= 10) {
                            snrMeasureCounter = 0
                            val snr = NativeDspBridge.measureSnr(readBuffer, 18600.0f)
                            _currentSnr.value = maxOf(0.0f, snr)
                        }
                    }
                }
            } catch (e: Exception) {
                // Audio loop terminated
            } finally {
                try {
                    audioRecord.stop()
                    audioRecord.release()
                } catch (e: Exception) {
                    // Ignore cleanup
                }
                _isListening.value = false
            }
        }

        return true
    }

    /**
     * Stops microphone capture and clears native buffer.
     */
    fun stopListening() {
        isRecording.set(false)
        recordThread?.cancel()
        recordThread = null
        NativeDspBridge.clearRxBuffer()
        _isListening.value = false
        _currentSnr.value = 0.0f
    }
}
