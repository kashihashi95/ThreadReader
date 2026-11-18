package com.redder.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WavUtilTest {
    private fun shortToLeBytes(value: Short): ByteArray {
        val b0 = (value.toInt() and 0xFF).toByte()
        val b1 = ((value.toInt() shr 8) and 0xFF).toByte()
        return byteArrayOf(b0, b1)
    }

    private fun shortsToLeBytes(vararg values: Short): ByteArray {
        val bytes = ByteArray(values.size * 2)
        for (i in values.indices) {
            bytes[i * 2] = (values[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((values[i].toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun readLeInt(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val b3 = bytes[offset + 3].toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readLeShort(
        bytes: ByteArray,
        offset: Int,
    ): Short {
        val low = bytes[offset].toInt() and 0xFF
        val high = bytes[offset + 1].toInt() and 0xFF
        return ((high shl 8) or low).toShort()
    }

    @Test
    fun `verifies standard constants match audio format requirements`() {
        assertEquals(22050, WavUtil.DEFAULT_SAMPLE_RATE)
        assertEquals(1, WavUtil.DEFAULT_CHANNELS)
        assertEquals(16, WavUtil.DEFAULT_BITS_PER_SAMPLE)
        assertEquals(44100, WavUtil.DEFAULT_BYTE_RATE)
        assertEquals(2, WavUtil.DEFAULT_BLOCK_ALIGN)
        assertEquals(44, WavUtil.HEADER_SIZE)
    }

    @Test
    fun `creates valid 44-byte wav header validating all byte offsets with default parameters`() {
        val pcmSize = 8820
        val header = WavUtil.createWavHeader(pcmDataSize = pcmSize)

        assertEquals(WavUtil.HEADER_SIZE, header.size)

        // 0..3: "RIFF" (ASCII)
        assertEquals('R'.code.toByte(), header[0])
        assertEquals('I'.code.toByte(), header[1])
        assertEquals('F'.code.toByte(), header[2])
        assertEquals('F'.code.toByte(), header[3])
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))

        // 4..7: Total chunk size = 36 + pcmSize (32-bit Little-Endian)
        assertEquals(36 + pcmSize, readLeInt(header, 4))

        // 8..11: "WAVE" (ASCII)
        assertEquals('W'.code.toByte(), header[8])
        assertEquals('A'.code.toByte(), header[9])
        assertEquals('V'.code.toByte(), header[10])
        assertEquals('E'.code.toByte(), header[11])
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))

        // 12..15: "fmt " (ASCII)
        assertEquals('f'.code.toByte(), header[12])
        assertEquals('m'.code.toByte(), header[13])
        assertEquals('t'.code.toByte(), header[14])
        assertEquals(' '.code.toByte(), header[15])
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))

        // 16..19: Subchunk1 size = 16 for PCM (32-bit Little-Endian)
        assertEquals(16, readLeInt(header, 16))

        // 20..21: Audio format = 1 (PCM) (16-bit Little-Endian)
        assertEquals(1.toShort(), readLeShort(header, 20))

        // 22..23: Num channels = 1 (16-bit Little-Endian)
        assertEquals(1.toShort(), readLeShort(header, 22))

        // 24..27: Sample rate = 22,050 (32-bit Little-Endian)
        assertEquals(22050, readLeInt(header, 24))

        // 28..31: Byte rate = 44,100 (32-bit Little-Endian)
        assertEquals(44100, readLeInt(header, 28))

        // 32..33: Block align = 2 (16-bit Little-Endian)
        assertEquals(2.toShort(), readLeShort(header, 32))

        // 34..35: Bits per sample = 16 (16-bit Little-Endian)
        assertEquals(16.toShort(), readLeShort(header, 34))

        // 36..39: "data" (ASCII)
        assertEquals('d'.code.toByte(), header[36])
        assertEquals('a'.code.toByte(), header[37])
        assertEquals('t'.code.toByte(), header[38])
        assertEquals('a'.code.toByte(), header[39])
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))

        // 40..43: Subchunk2 size = pcmSize (32-bit Little-Endian)
        assertEquals(pcmSize, readLeInt(header, 40))
    }

    @Test
    fun `creates valid wav header with non-standard sample rates and stereo parameters`() {
        val pcmSize = 192000
        val sampleRate = 48000
        val channels = 2
        val bitsPerSample = 16
        val expectedByteRate = sampleRate * channels * (bitsPerSample / 8) // 192,000
        val expectedBlockAlign = channels * (bitsPerSample / 8) // 4

        val header =
            WavUtil.createWavHeader(
                pcmDataSize = pcmSize,
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
            )

        assertEquals(WavUtil.HEADER_SIZE, header.size)
        assertEquals(36 + pcmSize, readLeInt(header, 4))
        assertEquals(channels.toShort(), readLeShort(header, 22))
        assertEquals(sampleRate, readLeInt(header, 24))
        assertEquals(expectedByteRate, readLeInt(header, 28))
        assertEquals(expectedBlockAlign.toShort(), readLeShort(header, 32))
        assertEquals(bitsPerSample.toShort(), readLeShort(header, 34))
        assertEquals(pcmSize, readLeInt(header, 40))
    }

    @Test
    fun `creates valid wav header for 16000 Hz mono configuration`() {
        val pcmSize = 32000
        val sampleRate = 16000
        val channels = 1
        val bitsPerSample = 16
        val expectedByteRate = 32000
        val expectedBlockAlign = 2

        val header =
            WavUtil.createWavHeader(
                pcmDataSize = pcmSize,
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
            )

        assertEquals(sampleRate, readLeInt(header, 24))
        assertEquals(expectedByteRate, readLeInt(header, 28))
        assertEquals(expectedBlockAlign.toShort(), readLeShort(header, 32))
        assertEquals(pcmSize, readLeInt(header, 40))
    }

    @Test
    fun `createWavHeader rejects invalid arguments`() {
        assertThrows(IllegalArgumentException::class.java) {
            WavUtil.createWavHeader(pcmDataSize = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WavUtil.createWavHeader(pcmDataSize = 100, sampleRate = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WavUtil.createWavHeader(pcmDataSize = 100, channels = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WavUtil.createWavHeader(pcmDataSize = 100, bitsPerSample = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WavUtil.createWavHeader(pcmDataSize = 100, bitsPerSample = 12)
        }
    }

    @Test
    fun `pcmToWav prepends 44-byte header and preserves pcm payload`() {
        val samplePayload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val wav = WavUtil.pcmToWav(samplePayload)

        assertEquals(WavUtil.HEADER_SIZE + samplePayload.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + samplePayload.size, readLeInt(wav, 4))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(samplePayload.size, readLeInt(wav, 40))

        val extractedPayload = wav.copyOfRange(WavUtil.HEADER_SIZE, wav.size)
        assertTrue(samplePayload.contentEquals(extractedPayload))
    }

    @Test
    fun `pcmToWav handles empty pcm byte array`() {
        val emptyPayload = ByteArray(0)
        val wav = WavUtil.pcmToWav(emptyPayload)

        assertEquals(WavUtil.HEADER_SIZE, wav.size)
        assertEquals(36, readLeInt(wav, 4))
        assertEquals(0, readLeInt(wav, 40))
    }

    @Test
    fun `pcmToWav supports parameterized sample rates and channels`() {
        val pcm = byteArrayOf(10, 20, 30, 40)
        val wav =
            WavUtil.pcmToWav(
                pcmData = pcm,
                sampleRate = 48000,
                channels = 2,
                bitsPerSample = 16,
            )

        assertEquals(48000, readLeInt(wav, 24))
        assertEquals(2.toShort(), readLeShort(wav, 22))
        assertEquals(pcm.size, readLeInt(wav, 40))
    }

    @Test
    fun `calculates duration in seconds accurately`() {
        // Standard default: 22050 Hz, mono, 16-bit -> byteRate = 44,100 bytes/sec
        assertEquals(1.0, WavUtil.calculateDurationSeconds(44100), 1e-6)
        assertEquals(0.5, WavUtil.calculateDurationSeconds(22050), 1e-6)
        assertEquals(2.0, WavUtil.calculateDurationSeconds(88200), 1e-6)
        assertEquals(0.0, WavUtil.calculateDurationSeconds(0), 1e-6)
        assertEquals(0.0, WavUtil.calculateDurationSeconds(-1000), 1e-6)

        // Stereo 48,000 Hz 16-bit -> byteRate = 48000 * 2 * 2 = 192,000 bytes/sec
        assertEquals(
            1.0,
            WavUtil.calculateDurationSeconds(
                pcmByteCount = 192000,
                sampleRate = 48000,
                channels = 2,
                bitsPerSample = 16,
            ),
            1e-6,
        )

        // Edge cases with invalid zero or negative configurations
        assertEquals(0.0, WavUtil.calculateDurationSeconds(44100, sampleRate = 0), 1e-6)
        assertEquals(0.0, WavUtil.calculateDurationSeconds(44100, channels = 0), 1e-6)
        assertEquals(0.0, WavUtil.calculateDurationSeconds(44100, bitsPerSample = 0), 1e-6)
        assertEquals(0.0, WavUtil.calculateDurationSeconds(44100, bitsPerSample = 4), 1e-6)
    }

    @Test
    fun `detectClipping handles empty and incomplete sample arrays`() {
        assertFalse(WavUtil.detectClipping(ByteArray(0)))
        assertFalse(WavUtil.detectClipping(byteArrayOf(0x7F)))
    }

    @Test
    fun `detectClipping accurately detects positive and negative clipping boundaries`() {
        // Sample at 0 -> no clip
        assertFalse(WavUtil.detectClipping(shortToLeBytes(0.toShort())))

        // Sample at 16000 -> no clip
        assertFalse(WavUtil.detectClipping(shortToLeBytes(16000.toShort())))

        // Sample at -16000 -> no clip
        assertFalse(WavUtil.detectClipping(shortToLeBytes((-16000).toShort())))

        // Sample at 31999 -> no clip
        assertFalse(WavUtil.detectClipping(shortToLeBytes(31999.toShort())))

        // Sample at -31999 -> no clip
        assertFalse(WavUtil.detectClipping(shortToLeBytes((-31999).toShort())))

        // Sample at 32000 -> clipping detected
        assertTrue(WavUtil.detectClipping(shortToLeBytes(32000.toShort())))

        // Sample at -32000 -> clipping detected
        assertTrue(WavUtil.detectClipping(shortToLeBytes((-32000).toShort())))

        // Sample at 32767 -> clipping detected
        assertTrue(WavUtil.detectClipping(shortToLeBytes(32767.toShort())))

        // Sample at -32768 -> clipping detected
        assertTrue(WavUtil.detectClipping(shortToLeBytes((-32768).toShort())))
    }

    @Test
    fun `detectClipping detects clipping across multi-sample buffers`() {
        val stream = shortsToLeBytes(0, 500, -1200, 32050, 100)
        assertTrue(WavUtil.detectClipping(stream))

        val cleanStream = shortsToLeBytes(0, 500, -1200, 28000, 100)
        assertFalse(WavUtil.detectClipping(cleanStream))
    }

    @Test
    fun `detectClipping respects custom and negative threshold values`() {
        val sample = shortToLeBytes(15000.toShort())

        // Threshold = 10000 -> 15000 is clipping
        assertTrue(WavUtil.detectClipping(sample, threshold = 10000.toShort()))

        // Threshold = 20000 -> 15000 is not clipping
        assertFalse(WavUtil.detectClipping(sample, threshold = 20000.toShort()))

        // Negative threshold = -10000 -> abs value is 10000 -> 15000 is clipping
        assertTrue(WavUtil.detectClipping(sample, threshold = (-10000).toShort()))
    }

    @Test
    fun `calculatePeakAmplitude handles empty and invalid data`() {
        assertEquals(0.toShort(), WavUtil.calculatePeakAmplitude(ByteArray(0)))
        assertEquals(0.toShort(), WavUtil.calculatePeakAmplitude(byteArrayOf(0x55)))
    }

    @ParameterizedTest
    @ValueSource(shorts = [0, 16000, -16000, 32000, -32000, 32767])
    fun `calculatePeakAmplitude returns absolute amplitude for standard samples`(sampleValue: Short) {
        val bytes = shortToLeBytes(sampleValue)
        val expected = kotlin.math.abs(sampleValue.toInt()).toShort()
        assertEquals(expected, WavUtil.calculatePeakAmplitude(bytes))
    }

    @Test
    fun `calculatePeakAmplitude clamps -32768 to Short MAX_VALUE`() {
        val bytes = shortToLeBytes((-32768).toShort())
        assertEquals(Short.MAX_VALUE, WavUtil.calculatePeakAmplitude(bytes))
    }

    @Test
    fun `calculatePeakAmplitude finds maximum across multiple samples`() {
        val stream = shortsToLeBytes(100, -2500, 18000, -29000, 400)
        assertEquals(29000.toShort(), WavUtil.calculatePeakAmplitude(stream))
    }

    @Test
    fun `calculateRmsDbFs handles empty, incomplete, and silent audio`() {
        assertEquals(Double.NEGATIVE_INFINITY, WavUtil.calculateRmsDbFs(ByteArray(0)))
        assertEquals(Double.NEGATIVE_INFINITY, WavUtil.calculateRmsDbFs(byteArrayOf(0x10)))
        assertEquals(Double.NEGATIVE_INFINITY, WavUtil.calculateRmsDbFs(shortsToLeBytes(0, 0, 0)))
    }

    @Test
    fun `calculateRmsDbFs computes correct dBFS for full scale and mid scale signals`() {
        // Full scale positive square wave: samples = 32767 -> rms = 32767.0 -> 0.0 dBFS
        val fullScale = shortsToLeBytes(32767, 32767, 32767, 32767)
        val rmsFullScale = WavUtil.calculateRmsDbFs(fullScale)
        assertEquals(0.0, rmsFullScale, 1e-4)

        // Full scale negative square wave: samples = -32768 -> rms = 32768.0 -> ~0.00026 dBFS
        val minScale = shortsToLeBytes((-32768).toShort(), (-32768).toShort())
        val rmsMinScale = WavUtil.calculateRmsDbFs(minScale)
        assertTrue(rmsMinScale > 0.0 && rmsMinScale < 0.001)

        // Half scale: sample = 16383.5 -> rms / 32767 = ~0.5 -> 20 * log10(0.5) = ~ -6.02 dBFS
        val halfScale = shortsToLeBytes(16384, -16384, 16384, -16384)
        val rmsHalfScale = WavUtil.calculateRmsDbFs(halfScale)
        assertEquals(-6.02, rmsHalfScale, 0.01)
    }

    @Test
    fun `appendSilence adds zero samples and updates RIFF and data sizes`() {
        val pcm = shortsToLeBytes(1000, -1000, 500)
        val wav = WavUtil.pcmToWav(pcm, sampleRate = 44100)

        val out = WavUtil.appendSilence(wav, durationMs = 100)

        val silenceBytes = 4410 * 2
        assertEquals(wav.size + silenceBytes, out.size)
        assertEquals(36 + pcm.size + silenceBytes, readLeInt(out, 4))
        assertEquals(pcm.size + silenceBytes, readLeInt(out, 40))
        assertEquals(44100, WavUtil.extractSampleRateFromWav(out))
        assertTrue(out.copyOfRange(0, wav.size).drop(44).toByteArray().contentEquals(pcm))
        assertTrue(out.copyOfRange(wav.size, out.size).all { it == 0.toByte() })
    }

    @Test
    fun `appendSilence leaves non-positive durations and non-canonical WAVs untouched`() {
        val wav = WavUtil.pcmToWav(shortsToLeBytes(1, 2), sampleRate = 22050)
        assertTrue(WavUtil.appendSilence(wav, 0) === wav)
        assertTrue(WavUtil.appendSilence(wav, -5) === wav)

        val withListChunk = wav.copyOf().also { "LIST".toByteArray(Charsets.US_ASCII).copyInto(it, 36) }
        assertFalse(WavUtil.isCanonicalPcmWav(withListChunk))
        assertTrue(WavUtil.appendSilence(withListChunk, 100) === withListChunk)

        val tiny = byteArrayOf(1, 2, 3)
        assertTrue(WavUtil.appendSilence(tiny, 100) === tiny)
        assertTrue(WavUtil.isCanonicalPcmWav(wav))
    }
}
