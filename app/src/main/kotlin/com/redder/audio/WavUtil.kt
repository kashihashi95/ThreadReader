package com.redder.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Audio format constants and utility functions for RIFF/WAV header generation,
 * PCM audio duration calculation, clipping detection, and amplitude analysis.
 */
object WavUtil {
    const val DEFAULT_SAMPLE_RATE: Int = 22050
    const val SUPERTONIC_SAMPLE_RATE: Int = 44100
    const val DEFAULT_CHANNELS: Int = 1
    const val DEFAULT_BITS_PER_SAMPLE: Int = 16
    const val DEFAULT_BYTE_RATE: Int = DEFAULT_SAMPLE_RATE * DEFAULT_CHANNELS * (DEFAULT_BITS_PER_SAMPLE / 8)
    const val DEFAULT_BLOCK_ALIGN: Int = DEFAULT_CHANNELS * (DEFAULT_BITS_PER_SAMPLE / 8)
    const val HEADER_SIZE: Int = 44

    /**
     * Extracts the sample rate in Hz from a 44-byte RIFF/WAV audio header.
     *
     * @param wavBytes WAV audio byte array (must be at least 44 bytes).
     * @return Sampling rate integer in Hz.
     */
    fun extractSampleRateFromWav(wavBytes: ByteArray): Int {
        require(wavBytes.size >= HEADER_SIZE) {
            "WAV data too short: ${wavBytes.size} bytes (minimum $HEADER_SIZE required)"
        }
        return ByteBuffer.wrap(wavBytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    /**
     * Generates a 44-byte Little-Endian RIFF/WAV header for raw PCM audio data.
     *
     * @param pcmDataSize Total size of the PCM audio payload in bytes.
     * @param sampleRate Sampling rate in Hz (default: 22,050 Hz).
     * @param channels Number of audio channels (default: 1 for Mono).
     * @param bitsPerSample Bit depth per audio sample (default: 16-bit).
     * @return 44-byte RIFF/WAV header byte array.
     */
    fun createWavHeader(
        pcmDataSize: Int,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channels: Int = DEFAULT_CHANNELS,
        bitsPerSample: Int = DEFAULT_BITS_PER_SAMPLE,
    ): ByteArray {
        require(pcmDataSize >= 0) { "pcmDataSize must be non-negative: $pcmDataSize" }
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(channels > 0) { "channels must be positive: $channels" }
        require(bitsPerSample > 0 && bitsPerSample % 8 == 0) {
            "bitsPerSample must be a positive multiple of 8: $bitsPerSample"
        }

        val bytesPerSample = bitsPerSample / 8
        val byteRate = sampleRate * channels * bytesPerSample
        val blockAlign = channels * bytesPerSample

        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        // 0..3: "RIFF" (ASCII)
        buffer.put('R'.code.toByte())
        buffer.put('I'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.put('F'.code.toByte())
        // 4..7: Total chunk size = 36 + pcmDataSize (32-bit Little-Endian)
        buffer.putInt(36 + pcmDataSize)
        // 8..11: "WAVE" (ASCII)
        buffer.put('W'.code.toByte())
        buffer.put('A'.code.toByte())
        buffer.put('V'.code.toByte())
        buffer.put('E'.code.toByte())
        // 12..15: "fmt " (ASCII)
        buffer.put('f'.code.toByte())
        buffer.put('m'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put(' '.code.toByte())
        // 16..19: Subchunk1 size = 16 for PCM (32-bit Little-Endian)
        buffer.putInt(16)
        // 20..21: Audio format = 1 (PCM) (16-bit Little-Endian)
        buffer.putShort(1.toShort())
        // 22..23: Num channels (16-bit Little-Endian)
        buffer.putShort(channels.toShort())
        // 24..27: Sample rate (32-bit Little-Endian)
        buffer.putInt(sampleRate)
        // 28..31: Byte rate = sampleRate * channels * (bitsPerSample / 8) (32-bit Little-Endian)
        buffer.putInt(byteRate)
        // 32..33: Block align = channels * (bitsPerSample / 8) (16-bit Little-Endian)
        buffer.putShort(blockAlign.toShort())
        // 34..35: Bits per sample (16-bit Little-Endian)
        buffer.putShort(bitsPerSample.toShort())
        // 36..39: "data" (ASCII)
        buffer.put('d'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put('a'.code.toByte())
        // 40..43: Subchunk2 size = pcmDataSize (32-bit Little-Endian)
        buffer.putInt(pcmDataSize)

        return buffer.array()
    }

    /**
     * Packages raw PCM audio data into a valid WAV file byte array by prepending
     * a 44-byte RIFF/WAV header.
     *
     * @param pcmData Raw PCM audio byte array.
     * @param sampleRate Sampling rate in Hz (default: 22,050 Hz).
     * @param channels Number of audio channels (default: 1 for Mono).
     * @param bitsPerSample Bit depth per audio sample (default: 16-bit).
     * @return Complete WAV file byte array (header + PCM data).
     */
    fun pcmToWav(
        pcmData: ByteArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channels: Int = DEFAULT_CHANNELS,
        bitsPerSample: Int = DEFAULT_BITS_PER_SAMPLE,
    ): ByteArray {
        val header =
            createWavHeader(
                pcmDataSize = pcmData.size,
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
            )
        return header + pcmData
    }

    /**
     * Returns a copy of the 44-byte-header WAV [wavBytes] with [durationMs] of digital silence
     * appended and the RIFF and data sizes updated to match. The format (sample rate, channels,
     * bit depth) is read from the header. Returns [wavBytes] unchanged when [durationMs] is not
     * positive or the data is not a canonical 44-byte-header PCM WAV (for example a platform TTS file
     * with extra chunks), since appending to those would corrupt them.
     */
    fun appendSilence(
        wavBytes: ByteArray,
        durationMs: Int,
    ): ByteArray {
        if (durationMs <= 0 || !isCanonicalPcmWav(wavBytes)) return wavBytes
        val header = ByteBuffer.wrap(wavBytes, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val channels = header.getShort(22).toInt()
        val sampleRate = header.getInt(24)
        val bitsPerSample = header.getShort(34).toInt()
        if (channels <= 0 || sampleRate <= 0 || bitsPerSample <= 0 || bitsPerSample % 8 != 0) return wavBytes
        val frameBytes = channels * (bitsPerSample / 8)
        val silenceBytes = (durationMs.toLong() * sampleRate / 1000L).toInt() * frameBytes
        val pcmSize = wavBytes.size - HEADER_SIZE + silenceBytes
        val out = wavBytes.copyOf(wavBytes.size + silenceBytes)
        createWavHeader(pcmSize, sampleRate, channels, bitsPerSample).copyInto(out)
        return out
    }

    /**
     * True when [wavBytes] starts with the exact 44-byte RIFF/WAVE/fmt(16, PCM)/data layout that
     * [createWavHeader] writes.
     */
    fun isCanonicalPcmWav(wavBytes: ByteArray): Boolean {
        if (wavBytes.size < HEADER_SIZE) return false

        fun tag(
            offset: Int,
            value: String,
        ) = String(wavBytes, offset, 4, Charsets.US_ASCII) == value
        val header = ByteBuffer.wrap(wavBytes, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        return tag(0, "RIFF") && tag(8, "WAVE") && tag(12, "fmt ") && tag(36, "data") &&
            header.getInt(16) == 16 && header.getShort(20).toInt() == 1
    }

    /**
     * Calculates audio duration in seconds from raw PCM byte count.
     *
     * @param pcmByteCount Number of bytes of raw PCM audio.
     * @param sampleRate Sampling rate in Hz (default: 22,050 Hz).
     * @param channels Number of audio channels (default: 1 for Mono).
     * @param bitsPerSample Bit depth per audio sample (default: 16-bit).
     * @return Duration in seconds. Returns 0.0 if byteRate <= 0 or pcmByteCount <= 0.
     */
    fun calculateDurationSeconds(
        pcmByteCount: Int,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channels: Int = DEFAULT_CHANNELS,
        bitsPerSample: Int = DEFAULT_BITS_PER_SAMPLE,
    ): Double {
        if (pcmByteCount <= 0 || sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) {
            return 0.0
        }
        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample <= 0) {
            return 0.0
        }
        val byteRate = sampleRate.toLong() * channels.toLong() * bytesPerSample.toLong()
        return pcmByteCount.toDouble() / byteRate.toDouble()
    }

    /**
     * Detects whether any 16-bit signed PCM sample exceeds or meets the given threshold.
     *
     * @param pcmData Raw 16-bit Little-Endian PCM audio byte array.
     * @param threshold Amplitude threshold (default: 32,000).
     * @return true if any sample has abs(sample) >= abs(threshold); false otherwise.
     */
    fun detectClipping(
        pcmData: ByteArray,
        threshold: Short = 32000,
    ): Boolean {
        val targetThreshold = abs(threshold.toInt())
        val sampleCount = pcmData.size / 2
        for (i in 0 until sampleCount) {
            val offset = i * 2
            val sample = ((pcmData[offset + 1].toInt() shl 8) or (pcmData[offset].toInt() and 0xFF)).toShort()
            if (abs(sample.toInt()) >= targetThreshold) {
                return true
            }
        }
        return false
    }

    /**
     * Calculates the peak absolute amplitude across 16-bit signed PCM samples.
     *
     * @param pcmData Raw 16-bit Little-Endian PCM audio byte array.
     * @return Maximum absolute amplitude clamped to Short.MAX_VALUE (32,767).
     *         Returns 0 for empty or invalid data.
     */
    fun calculatePeakAmplitude(pcmData: ByteArray): Short {
        val sampleCount = pcmData.size / 2
        if (sampleCount == 0) {
            return 0
        }
        var maxAmp = 0
        for (i in 0 until sampleCount) {
            val offset = i * 2
            val sample = ((pcmData[offset + 1].toInt() shl 8) or (pcmData[offset].toInt() and 0xFF)).toShort()
            val absVal = abs(sample.toInt())
            if (absVal > maxAmp) {
                maxAmp = absVal
            }
        }
        if (maxAmp > Short.MAX_VALUE.toInt()) {
            return Short.MAX_VALUE
        }
        return maxAmp.toShort()
    }

    /**
     * Calculates Root Mean Square (RMS) level in dBFS for 16-bit signed PCM samples.
     *
     * @param pcmData Raw 16-bit Little-Endian PCM audio byte array.
     * @return RMS level in dBFS, or Double.NEGATIVE_INFINITY if data is empty or rms == 0.0.
     */
    fun calculateRmsDbFs(pcmData: ByteArray): Double {
        val numSamples = pcmData.size / 2
        if (numSamples == 0) {
            return Double.NEGATIVE_INFINITY
        }
        var sumSquares = 0.0
        for (i in 0 until numSamples) {
            val offset = i * 2
            val sample = ((pcmData[offset + 1].toInt() shl 8) or (pcmData[offset].toInt() and 0xFF)).toShort()
            val sampleDouble = sample.toDouble()
            sumSquares += sampleDouble * sampleDouble
        }
        val rms = sqrt(sumSquares / numSamples)
        if (rms == 0.0) {
            return Double.NEGATIVE_INFINITY
        }
        return 20.0 * log10(rms / 32767.0)
    }
}
