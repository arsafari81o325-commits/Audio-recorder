package com.example.audiorecorder

import android.media.*
import android.media.projection.MediaProjection
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Captures internal (playback) audio via MediaProjection + AudioRecord,
 * encodes it to AAC with MediaCodec, and writes raw ADTS frames to disk.
 *
 * Supports: configurable quality, pause/resume, and a live amplitude
 * callback (0-100) for UI visualization.
 */
class AudioCaptureManager(
    private val mediaProjection: MediaProjection,
    private val quality: RecordingQuality = RecordingQuality.HIGH
) {
    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var fileOutputStream: FileOutputStream? = null
    @Volatile private var isRecording = false
    @Volatile var isPaused = false
        private set
    private var recordingThread: Thread? = null

    /** Called from a background thread — hop to main thread before touching UI. */
    var onAmplitude: ((Int) -> Unit)? = null
    /** Called from a background thread if the record loop dies unexpectedly. */
    var onError: ((Exception) -> Unit)? = null

    companion object {
        private const val TAG = "AudioCaptureManager"
    }

    fun startRecording(outputFile: File) {
        try {
            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(quality.sampleRate)
                .setChannelMask(quality.channelConfig)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(
                quality.sampleRate, quality.channelConfig, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) {
                throw IllegalStateException("پارامترهای صوتی انتخاب‌شده روی این دستگاه پشتیبانی نمی‌شود")
            }

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .setBufferSizeInBytes(minBufferSize * 2)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("راه‌اندازی AudioRecord ناموفق بود")
            }

            val codecFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, quality.sampleRate, quality.channelCount
            )
            codecFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            codecFormat.setInteger(MediaFormat.KEY_BIT_RATE, quality.bitRate)
            codecFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            mediaCodec?.configure(codecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            fileOutputStream = FileOutputStream(outputFile)

            mediaCodec?.start()
            audioRecord?.startRecording()
            isRecording = true
            isPaused = false

            recordingThread = Thread(this::recordLoop, "AudioCaptureThread").apply { start() }

            Log.d(TAG, "Recording started (${quality.name}): ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            releaseQuietly()
            throw e
        }
    }

    fun pause() {
        isPaused = true
    }

    fun resume() {
        isPaused = false
    }

    private fun recordLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        val inputBuffer = ShortArray(2048)
        val byteBuffer = ByteArray(4096)

        try {
            while (isRecording) {
                if (isPaused) {
                    // Keep the AudioRecord alive but discard captured data while
                    // paused, so resuming doesn't require re-initializing anything.
                    audioRecord?.read(inputBuffer, 0, inputBuffer.size)
                    Thread.sleep(80)
                    continue
                }

                val shortsRead = audioRecord?.read(inputBuffer, 0, inputBuffer.size) ?: -1
                if (shortsRead > 0) {
                    reportAmplitude(inputBuffer, shortsRead)

                    // Convert PCM16 shorts -> little-endian bytes for the encoder input.
                    for (i in 0 until shortsRead) {
                        val s = inputBuffer[i].toInt()
                        byteBuffer[i * 2] = (s and 0xFF).toByte()
                        byteBuffer[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    val byteCount = shortsRead * 2

                    val inputIndex = mediaCodec?.dequeueInputBuffer(10000) ?: -1
                    if (inputIndex >= 0) {
                        val buf = mediaCodec?.getInputBuffer(inputIndex)
                        buf?.clear()
                        buf?.put(byteBuffer, 0, byteCount)
                        mediaCodec?.queueInputBuffer(inputIndex, 0, byteCount, 0, 0)
                    }

                    drainEncoder(bufferInfo, endOfStream = false)
                }
            }

            val inputIndex = mediaCodec?.dequeueInputBuffer(10000) ?: -1
            if (inputIndex >= 0) {
                mediaCodec?.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainEncoder(bufferInfo, endOfStream = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in record loop", e)
            onError?.invoke(e)
        }
    }

    private fun drainEncoder(bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
        var outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
        while (outputIndex >= 0) {
            if (endOfStream && bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                mediaCodec?.releaseOutputBuffer(outputIndex, false)
                break
            }
            val outBuf = mediaCodec?.getOutputBuffer(outputIndex)
            val chunk = ByteArray(bufferInfo.size + 7)
            outBuf?.get(chunk, 7, bufferInfo.size)
            addAdtsHeader(chunk, chunk.size)
            fileOutputStream?.write(chunk)
            mediaCodec?.releaseOutputBuffer(outputIndex, false)
            outputIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10000 else 0) ?: -1
        }
    }

    /** RMS-based amplitude (0-100) reported to [onAmplitude] for UI visualization. */
    private fun reportAmplitude(buffer: ShortArray, count: Int) {
        val callback = onAmplitude ?: return
        var sum = 0.0
        for (i in 0 until count) sum += (buffer[i] * buffer[i]).toDouble()
        val rms = Math.sqrt(sum / max(count, 1))
        val db = if (rms > 1.0) 20 * log10(rms / 32768.0) else -60.0
        // Map roughly [-60dB, 0dB] to [0, 100].
        val level = ((db + 60.0) / 60.0 * 100.0).toInt().coerceIn(0, 100)
        callback(level)
    }

    private fun addAdtsHeader(packet: ByteArray, packetLen: Int) {
        val profile = 2
        val freqIdx = sampleRateToAdtsIndex(quality.sampleRate)
        val chanCfg = quality.channelCount

        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte()
        packet[2] = (((profile - 1) shl 6) or (freqIdx shl 2) or (chanCfg shr 2)).toByte()
        packet[3] = (((chanCfg and 3) shl 6) or (packetLen shr 11)).toByte()
        packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
        packet[5] = (((packetLen and 7) shl 5) or 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }

    // FIX (vs. original): the ADTS header previously hardcoded freqIdx=3 (48kHz)
    // and chanCfg=2 (stereo) regardless of actual settings, which produced a
    // corrupt/unplayable header whenever quality != HIGH. Now derived properly.
    private fun sampleRateToAdtsIndex(sampleRate: Int): Int = when (sampleRate) {
        96000 -> 0
        88200 -> 1
        64000 -> 2
        48000 -> 3
        44100 -> 4
        32000 -> 5
        24000 -> 6
        22050 -> 7
        16000 -> 8
        12000 -> 9
        11025 -> 10
        8000 -> 11
        else -> 4 // fallback: 44100Hz
    }

    fun stopRecording() {
        isRecording = false
        recordingThread?.join(2000)
        releaseQuietly()
        Log.d(TAG, "Recording stopped")
    }

    private fun releaseQuietly() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { mediaCodec?.stop() } catch (_: Exception) {}
        try { mediaCodec?.release() } catch (_: Exception) {}
        mediaCodec = null

        try { fileOutputStream?.flush() } catch (_: Exception) {}
        try { fileOutputStream?.close() } catch (_: Exception) {}
        fileOutputStream = null
    }
}
