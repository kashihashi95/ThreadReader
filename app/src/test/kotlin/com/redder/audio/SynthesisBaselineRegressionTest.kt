package com.redder.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Audio Quality & Golden Baseline Regression Test Suite (TASK-8.1).
 *
 * Evaluates speech synthesis quality, physical audio metrics, and tolerances against
 * golden reference phrases REF-001 through REF-005 according to docs/testing/synthesis-baselines-and-reference.md
 * and CONSTRAINTS.md.
 *
 * Implemented with pure JUnit 5 (zero Robolectric, zero JUnit 4) for OpenJDK 25 compatibility.
 */
class SynthesisBaselineRegressionTest {
    // -------------------------------------------------------------------------
    // Data Models
    // -------------------------------------------------------------------------

    /**
     * Specification and physical tolerance bounds for a golden reference synthesis phrase.
     */
    data class GoldenPhrase(
        val id: String,
        val text: String,
        val wordCount: Int,
        val expectedDurationSeconds: Double,
        val durationToleranceSeconds: Double,
        val targetWpm: Double,
        val minSpectralCentroidHz: Double,
        val maxSpectralCentroidHz: Double,
        val phoneticFocus: String,
    ) {
        val minDurationSeconds: Double get() = expectedDurationSeconds - durationToleranceSeconds
        val maxDurationSeconds: Double get() = expectedDurationSeconds + durationToleranceSeconds

        fun isDurationPassing(
            actualDurationSeconds: Double,
            toleranceEpsilon: Double = 1e-6,
        ): Boolean =
            actualDurationSeconds >= (minDurationSeconds - toleranceEpsilon) &&
                actualDurationSeconds <= (maxDurationSeconds + toleranceEpsilon)

        fun isSpectralCentroidPassing(
            actualCentroidHz: Double,
            toleranceEpsilon: Double = 1e-6,
        ): Boolean =
            actualCentroidHz >= (minSpectralCentroidHz - toleranceEpsilon) &&
                actualCentroidHz <= (maxSpectralCentroidHz + toleranceEpsilon)

        fun calculateWpm(durationSeconds: Double): Double {
            if (durationSeconds <= 0.0) return 0.0
            return (wordCount.toDouble() / durationSeconds) * 60.0
        }
    }

    /**
     * Classification of Real-Time Factor (RTF) performance against CI thresholds.
     */
    enum class RtfStatus {
        PASS,
        BOUNDARY_WARNING,
        FAILURE,
    }

    /**
     * Comprehensive physical audio metrics evaluated against reference constraints.
     */
    data class AudioMetrics(
        val durationSeconds: Double,
        val rtf: Double,
        val sqnrDb: Double,
        val peakAmplitude: Short,
        val rmsDbFs: Double,
        val leadingSilenceDurationMs: Double,
        val spectralCentroidHz: Double,
        val speakingRateWpm: Double,
        val hasClipping: Boolean,
    )

    // -------------------------------------------------------------------------
    // Companion Object: Constants, Reference Dataset & Pure Calculation Utilities
    // -------------------------------------------------------------------------

    companion object {
        const val RTF_PASS_THRESHOLD: Double = 0.40
        const val RTF_FAIL_THRESHOLD: Double = 0.50
        const val SQNR_PASS_THRESHOLD_DB: Double = 28.0
        const val MAX_ALLOWED_PEAK_AMPLITUDE: Short = 32000
        const val MAX_LEADING_SILENCE_MS: Double = 120.0
        const val MIN_TARGET_RMS_DBFS: Double = -24.0
        const val MAX_TARGET_RMS_DBFS: Double = -18.0
        const val DEFAULT_SILENCE_THRESHOLD_AMPLITUDE: Short = 300

        val REF_001 =
            GoldenPhrase(
                id = "REF-001",
                text = "Android architecture requires clean separation of concerns.",
                wordCount = 7,
                expectedDurationSeconds = 2.3,
                durationToleranceSeconds = 0.3,
                targetWpm = 160.0,
                minSpectralCentroidHz = 1200.0,
                maxSpectralCentroidHz = 2800.0,
                phoneticFocus = "Verifies syllable stress on \"architecture\" and \"separation\".",
            )

        val REF_002 =
            GoldenPhrase(
                id = "REF-002",
                text = "User cool coder 42 replies: What about memory overhead?",
                wordCount = 9,
                expectedDurationSeconds = 2.9,
                durationToleranceSeconds = 0.4,
                targetWpm = 155.0,
                minSpectralCentroidHz = 1200.0,
                maxSpectralCentroidHz = 2800.0,
                phoneticFocus =
                    "Verifies username formatting (\"cool coder\") and question pitch intonation at \"overhead?\".",
            )

        val REF_003 =
            GoldenPhrase(
                id = "REF-003",
                text = "The model parameters are eighty-two million, quantized to INT8.",
                wordCount = 9,
                expectedDurationSeconds = 3.4,
                durationToleranceSeconds = 0.4,
                targetWpm = 150.0,
                minSpectralCentroidHz = 1100.0,
                maxSpectralCentroidHz = 2900.0,
                phoneticFocus = "Verifies numerical and acronym pronunciation (\"eighty-two\", \"INT-eight\").",
            )

        val REF_004 =
            GoldenPhrase(
                id = "REF-004",
                text = "Post by system engineer: Warning, thermal throttling will degrade RTF below real time.",
                wordCount = 13,
                expectedDurationSeconds = 4.6,
                durationToleranceSeconds = 0.5,
                targetWpm = 160.0,
                minSpectralCentroidHz = 1200.0,
                maxSpectralCentroidHz = 2800.0,
                phoneticFocus = "Verifies dialogue prefix cadence (\"Post by...\") and acronym G2P (\"R-T-F\").",
            )

        val REF_005 =
            GoldenPhrase(
                id = "REF-005",
                text = "Yes! Exactly. Couldn't agree more.",
                wordCount = 5,
                expectedDurationSeconds = 1.7,
                durationToleranceSeconds = 0.3,
                targetWpm = 140.0,
                minSpectralCentroidHz = 1300.0,
                maxSpectralCentroidHz = 3000.0,
                phoneticFocus = "Verifies contractions (\"Couldn't\") and punctuation pause markers.",
            )

        val GOLDEN_PHRASES: List<GoldenPhrase> = listOf(REF_001, REF_002, REF_003, REF_004, REF_005)

        fun getGoldenPhrase(id: String): GoldenPhrase? = GOLDEN_PHRASES.firstOrNull { it.id == id }

        /**
         * Real-Time Factor: RTF = T_inference / T_audio
         */
        fun calculateRtf(
            inferenceDurationSeconds: Double,
            audioDurationSeconds: Double,
        ): Double {
            if (audioDurationSeconds <= 0.0) {
                return if (inferenceDurationSeconds <= 0.0) 0.0 else Double.POSITIVE_INFINITY
            }
            if (inferenceDurationSeconds < 0.0) return 0.0
            return inferenceDurationSeconds / audioDurationSeconds
        }

        /**
         * Classifies RTF into PASS (<= 0.40), BOUNDARY_WARNING (0.40 < RTF <= 0.50), or FAILURE (> 0.50).
         */
        fun classifyRtf(rtf: Double): RtfStatus =
            when {
                rtf.isNaN() || rtf > RTF_FAIL_THRESHOLD -> RtfStatus.FAILURE
                rtf > RTF_PASS_THRESHOLD -> RtfStatus.BOUNDARY_WARNING
                else -> RtfStatus.PASS
            }

        /**
         * Signal-to-Quantization-Noise Ratio: SQNR = 10 * log10( sum(x[n]^2) / sum((x[n] - y[n])^2) )
         *
         * Returns Double.POSITIVE_INFINITY for perfect reconstruction (x[n] == y[n]).
         * Returns Double.NEGATIVE_INFINITY for zero reference signal (sum(x[n]^2) == 0).
         */
        fun calculateSqnr(
            reference: ShortArray,
            quantized: ShortArray,
        ): Double {
            require(reference.size == quantized.size) {
                "Reference sample count (${reference.size}) must match quantized sample count (${quantized.size})"
            }
            if (reference.isEmpty()) return Double.NEGATIVE_INFINITY

            var sumRefSq = 0.0
            var sumNoiseSq = 0.0

            for (i in reference.indices) {
                val ref = reference[i].toDouble()
                val quant = quantized[i].toDouble()
                val diff = ref - quant
                sumRefSq += ref * ref
                sumNoiseSq += diff * diff
            }

            if (sumRefSq == 0.0) return Double.NEGATIVE_INFINITY
            if (sumNoiseSq == 0.0) return Double.POSITIVE_INFINITY

            return 10.0 * log10(sumRefSq / sumNoiseSq)
        }

        /**
         * Signal-to-Quantization-Noise Ratio for Little-Endian 16-bit PCM byte arrays.
         */
        fun calculateSqnr(
            reference: ByteArray,
            quantized: ByteArray,
        ): Double {
            require(reference.size == quantized.size) {
                "Reference byte count (${reference.size}) must match quantized byte count (${quantized.size})"
            }
            val sampleCount = reference.size / 2
            val refSamples = ShortArray(sampleCount)
            val quantSamples = ShortArray(sampleCount)
            for (i in 0 until sampleCount) {
                val offset = i * 2
                refSamples[i] =
                    ((reference[offset + 1].toInt() shl 8) or (reference[offset].toInt() and 0xFF)).toShort()
                quantSamples[i] =
                    ((quantized[offset + 1].toInt() shl 8) or (quantized[offset].toInt() and 0xFF)).toShort()
            }
            return calculateSqnr(refSamples, quantSamples)
        }

        /**
         * Calculates speaking rate in Words Per Minute (WPM): (wordCount / durationSeconds) * 60.0.
         */
        fun calculateSpeakingRateWpm(
            wordCount: Int,
            durationSeconds: Double,
        ): Double {
            if (wordCount <= 0 || durationSeconds <= 0.0) return 0.0
            return (wordCount.toDouble() / durationSeconds) * 60.0
        }

        /**
         * Measures duration in milliseconds of initial silence before signal exceeds amplitude threshold.
         */
        fun calculateLeadingSilenceDurationMs(
            pcmData: ByteArray,
            sampleRate: Int = WavUtil.DEFAULT_SAMPLE_RATE,
            silenceThreshold: Short = DEFAULT_SILENCE_THRESHOLD_AMPLITUDE,
        ): Double {
            if (pcmData.size < 2 || sampleRate <= 0) return 0.0
            val numSamples = pcmData.size / 2
            val threshold = abs(silenceThreshold.toInt())
            var silentCount = 0
            for (i in 0 until numSamples) {
                val offset = i * 2
                val sample = ((pcmData[offset + 1].toInt() shl 8) or (pcmData[offset].toInt() and 0xFF)).toShort()
                if (abs(sample.toInt()) > threshold) {
                    break
                }
                silentCount++
            }
            return (silentCount.toDouble() / sampleRate.toDouble()) * 1000.0
        }

        /**
         * Spectral Centroid: Centroid = sum(f * |X[f]|) / sum(|X[f]|).
         * Computes spectral centroid from explicit magnitude bins and frequency bins.
         */
        fun calculateSpectralCentroid(
            magnitudes: DoubleArray,
            binFrequencies: DoubleArray,
        ): Double {
            require(magnitudes.size == binFrequencies.size) {
                "magnitudes size (${magnitudes.size}) must match binFrequencies size (${binFrequencies.size})"
            }
            var numerator = 0.0
            var denominator = 0.0
            for (i in magnitudes.indices) {
                numerator += binFrequencies[i] * magnitudes[i]
                denominator += magnitudes[i]
            }
            return if (denominator == 0.0) 0.0 else numerator / denominator
        }

        /**
         * Computes spectral centroid from discrete magnitude spectrum and sampling rate.
         */
        fun calculateSpectralCentroid(
            magnitudes: DoubleArray,
            sampleRate: Int,
            fftSize: Int = if (magnitudes.size > 1) (magnitudes.size - 1) * 2 else magnitudes.size,
        ): Double {
            if (magnitudes.isEmpty() || sampleRate <= 0 || fftSize <= 0) return 0.0
            val binWidth = sampleRate.toDouble() / fftSize.toDouble()
            var numerator = 0.0
            var denominator = 0.0
            for (k in magnitudes.indices) {
                val freq = k * binWidth
                val mag = magnitudes[k]
                numerator += freq * mag
                denominator += mag
            }
            return if (denominator == 0.0) 0.0 else numerator / denominator
        }

        /**
         * Computes spectral centroid directly from 16-bit PCM samples using Hann-windowed FFT.
         */
        fun calculateSpectralCentroid(
            samples: ShortArray,
            sampleRate: Int = WavUtil.DEFAULT_SAMPLE_RATE,
        ): Double {
            if (samples.isEmpty() || sampleRate <= 0) return 0.0

            var hasSignal = false
            for (s in samples) {
                if (s.toInt() != 0) {
                    hasSignal = true
                    break
                }
            }
            if (!hasSignal) return 0.0

            val n = samples.size
            val frameSize = if (n >= 2048) 2048 else nextPowerOfTwo(n.coerceAtLeast(64))
            val hopSize = frameSize / 2
            val numBins = frameSize / 2 + 1
            val accumulatedMagnitudes = DoubleArray(numBins)

            val real = DoubleArray(frameSize)
            val imag = DoubleArray(frameSize)
            val window =
                DoubleArray(frameSize) { i ->
                    0.5 * (1.0 - cos(2.0 * Math.PI * i / (frameSize - 1)))
                }

            var frameStart = 0
            while (frameStart < n) {
                val frameEnd = min(frameStart + frameSize, n)
                val frameLen = frameEnd - frameStart

                var sum = 0.0
                for (i in 0 until frameLen) {
                    sum += samples[frameStart + i]
                }
                val mean = sum / frameLen

                for (i in 0 until frameSize) {
                    if (i < frameLen) {
                        real[i] = (samples[frameStart + i] - mean) * window[i]
                    } else {
                        real[i] = 0.0
                    }
                    imag[i] = 0.0
                }

                cooleyTukeyFft(real, imag)

                for (k in 0 until numBins) {
                    val mag = sqrt(real[k] * real[k] + imag[k] * imag[k])
                    accumulatedMagnitudes[k] += mag
                }

                frameStart += hopSize
            }

            return calculateSpectralCentroid(accumulatedMagnitudes, sampleRate, frameSize)
        }

        /**
         * Computes spectral centroid from 16-bit Little-Endian PCM byte array.
         */
        fun calculateSpectralCentroid(
            pcmData: ByteArray,
            sampleRate: Int = WavUtil.DEFAULT_SAMPLE_RATE,
        ): Double {
            if (pcmData.size < 2 || sampleRate <= 0) return 0.0
            val numSamples = pcmData.size / 2
            val samples = ShortArray(numSamples)
            for (i in 0 until numSamples) {
                val offset = i * 2
                samples[i] = ((pcmData[offset + 1].toInt() shl 8) or (pcmData[offset].toInt() and 0xFF)).toShort()
            }
            return calculateSpectralCentroid(samples, sampleRate)
        }

        /**
         * Aggregates all audio physical metrics for a PCM audio buffer.
         */
        fun evaluateAudioMetrics(
            pcmData: ByteArray,
            referencePcmData: ByteArray? = null,
            inferenceDurationSeconds: Double = 0.0,
            wordCount: Int = 0,
            sampleRate: Int = WavUtil.DEFAULT_SAMPLE_RATE,
            clippingThreshold: Short = MAX_ALLOWED_PEAK_AMPLITUDE,
            silenceThreshold: Short = DEFAULT_SILENCE_THRESHOLD_AMPLITUDE,
        ): AudioMetrics {
            val durationSeconds = WavUtil.calculateDurationSeconds(pcmData.size, sampleRate)
            val rtf = calculateRtf(inferenceDurationSeconds, durationSeconds)
            val sqnrDb =
                if (referencePcmData != null) {
                    calculateSqnr(referencePcmData, pcmData)
                } else {
                    Double.NaN
                }
            val peakAmplitude = WavUtil.calculatePeakAmplitude(pcmData)
            val rmsDbFs = WavUtil.calculateRmsDbFs(pcmData)
            val leadingSilenceMs = calculateLeadingSilenceDurationMs(pcmData, sampleRate, silenceThreshold)
            val spectralCentroidHz = calculateSpectralCentroid(pcmData, sampleRate)
            val speakingRateWpm =
                if (wordCount > 0 && durationSeconds > 0.0) {
                    calculateSpeakingRateWpm(wordCount, durationSeconds)
                } else {
                    0.0
                }
            val hasClipping = WavUtil.detectClipping(pcmData, clippingThreshold)

            return AudioMetrics(
                durationSeconds = durationSeconds,
                rtf = rtf,
                sqnrDb = sqnrDb,
                peakAmplitude = peakAmplitude,
                rmsDbFs = rmsDbFs,
                leadingSilenceDurationMs = leadingSilenceMs,
                spectralCentroidHz = spectralCentroidHz,
                speakingRateWpm = speakingRateWpm,
                hasClipping = hasClipping,
            )
        }

        /**
         * Generates deterministic 16-bit Little-Endian PCM audio with leading silence and tone burst.
         */
        fun generateSyntheticPcm(
            toneFrequencyHz: Double,
            toneDurationSeconds: Double,
            amplitude: Short = 4130,
            sampleRate: Int = WavUtil.DEFAULT_SAMPLE_RATE,
            leadingSilenceSeconds: Double = 0.05,
            trailingSilenceSeconds: Double = 0.05,
        ): ByteArray {
            val leadingSamples = (leadingSilenceSeconds * sampleRate).toInt().coerceAtLeast(0)
            val toneSamples = (toneDurationSeconds * sampleRate).toInt().coerceAtLeast(0)
            val trailingSamples = (trailingSilenceSeconds * sampleRate).toInt().coerceAtLeast(0)
            val totalSamples = leadingSamples + toneSamples + trailingSamples

            val pcmBytes = ByteArray(totalSamples * 2)
            val twoPi = 2.0 * Math.PI

            for (i in 0 until toneSamples) {
                val sampleIndex = leadingSamples + i
                val angle = twoPi * toneFrequencyHz * i / sampleRate
                val sample = (sin(angle) * amplitude.toInt()).toInt().coerceIn(-32768, 32767).toShort()
                val offset = sampleIndex * 2
                pcmBytes[offset] = (sample.toInt() and 0xFF).toByte()
                pcmBytes[offset + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }

            return pcmBytes
        }

        /**
         * Generates synthetic baseline audio strictly matching the duration and spectral centroid bounds of [phrase].
         */
        fun generateGoldenPhraseAudio(
            phrase: GoldenPhrase,
            sampleRate: Int = WavUtil.DEFAULT_SAMPLE_RATE,
            leadingSilenceSeconds: Double = 0.05,
            trailingSilenceSeconds: Double = 0.05,
        ): ByteArray {
            val totalDuration = phrase.expectedDurationSeconds
            val toneDuration = (totalDuration - leadingSilenceSeconds - trailingSilenceSeconds).coerceAtLeast(0.1)
            val targetFrequency = (phrase.minSpectralCentroidHz + phrase.maxSpectralCentroidHz) / 2.0
            // Amplitude 4130 yields RMS around -21.0 dBFS, safely within [-24.0, -18.0] dBFS
            return generateSyntheticPcm(
                toneFrequencyHz = targetFrequency,
                toneDurationSeconds = toneDuration,
                amplitude = 4130,
                sampleRate = sampleRate,
                leadingSilenceSeconds = leadingSilenceSeconds,
                trailingSilenceSeconds = trailingSilenceSeconds,
            )
        }

        private fun nextPowerOfTwo(v: Int): Int {
            var n = v - 1
            n = n or (n shr 1)
            n = n or (n shr 2)
            n = n or (n shr 4)
            n = n or (n shr 8)
            n = n or (n shr 16)
            return if (n < 0) 1 else n + 1
        }

        private fun cooleyTukeyFft(
            real: DoubleArray,
            imag: DoubleArray,
        ) {
            val n = real.size
            require(n > 0 && (n and (n - 1)) == 0) { "Size must be a power of 2: $n" }

            var j = 0
            for (i in 0 until n - 1) {
                if (i < j) {
                    val tempR = real[i]
                    real[i] = real[j]
                    real[j] = tempR
                    val tempI = imag[i]
                    imag[i] = imag[j]
                    imag[j] = tempI
                }
                var k = n shr 1
                while (k <= j) {
                    j -= k
                    k = k shr 1
                }
                j += k
            }

            var len = 2
            while (len <= n) {
                val halfLen = len shr 1
                val angle = -2.0 * Math.PI / len
                val wStepR = cos(angle)
                val wStepI = sin(angle)
                var i = 0
                while (i < n) {
                    var wR = 1.0
                    var wI = 0.0
                    for (k in 0 until halfLen) {
                        val uR = real[i + k]
                        val uI = imag[i + k]
                        val vR = real[i + k + halfLen] * wR - imag[i + k + halfLen] * wI
                        val vI = real[i + k + halfLen] * wI + imag[i + k + halfLen] * wR

                        real[i + k] = uR + vR
                        imag[i + k] = uI + vI
                        real[i + k + halfLen] = uR - vR
                        imag[i + k + halfLen] = uI - vI

                        val nextWR = wR * wStepR - wI * wStepI
                        val nextWI = wR * wStepI + wI * wStepR
                        wR = nextWR
                        wI = nextWI
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }

    // =========================================================================
    // Category 1: Golden Dataset Constants & Structural Integrity
    // =========================================================================

    @Test
    fun `golden dataset contains all five reference phrases REF-001 through REF-005`() {
        assertEquals(5, GOLDEN_PHRASES.size)
        val ids = GOLDEN_PHRASES.map { it.id }.toSet()
        assertTrue(ids.contains("REF-001"))
        assertTrue(ids.contains("REF-002"))
        assertTrue(ids.contains("REF-003"))
        assertTrue(ids.contains("REF-004"))
        assertTrue(ids.contains("REF-005"))
    }

    @Test
    fun `golden phrases have valid non-blank text and strictly positive word counts`() {
        for (phrase in GOLDEN_PHRASES) {
            assertTrue(phrase.text.isNotBlank(), "Phrase text must not be blank for ${phrase.id}")
            assertTrue(phrase.wordCount > 0, "Word count must be positive for ${phrase.id}")
            val counted = phrase.text.trim().split(Regex("\\s+")).size
            assertEquals(phrase.wordCount, counted, "Actual word count matches specification for ${phrase.id}")
        }
    }

    @Test
    fun `golden phrases have consistent duration and positive tolerance bounds`() {
        for (phrase in GOLDEN_PHRASES) {
            assertTrue(phrase.expectedDurationSeconds > 0.0, "Expected duration must be positive")
            assertTrue(phrase.durationToleranceSeconds > 0.0, "Duration tolerance must be positive")
            assertTrue(phrase.minDurationSeconds < phrase.maxDurationSeconds)
            assertTrue(phrase.minDurationSeconds > 0.0, "Min duration must be strictly positive")
        }
    }

    @Test
    fun `golden phrases have valid target WPM and spectral centroid intervals`() {
        for (phrase in GOLDEN_PHRASES) {
            assertTrue(phrase.targetWpm >= 100.0 && phrase.targetWpm <= 200.0, "WPM in natural range")
            assertTrue(phrase.minSpectralCentroidHz < phrase.maxSpectralCentroidHz)
            assertTrue(phrase.minSpectralCentroidHz >= 1000.0, "Min centroid above 1000 Hz")
            assertTrue(phrase.maxSpectralCentroidHz <= 3500.0, "Max centroid within speech bounds")
            assertTrue(phrase.phoneticFocus.isNotBlank(), "Phonetic focus must be documented")
        }
    }

    @Test
    fun `golden phrase lookup by ID resolves expected phrases and returns null for unknown`() {
        assertNotNull(getGoldenPhrase("REF-001"))
        assertNotNull(getGoldenPhrase("REF-002"))
        assertNotNull(getGoldenPhrase("REF-003"))
        assertNotNull(getGoldenPhrase("REF-004"))
        assertNotNull(getGoldenPhrase("REF-005"))
        assertNull(getGoldenPhrase("REF-UNKNOWN"))
        assertNull(getGoldenPhrase(""))
    }

    // =========================================================================
    // Category 2: Reference Phrase 001 Tests
    // =========================================================================

    @Test
    fun `REF-001 verifies syllable stress specification, duration bounds, and spectral limits`() {
        val phrase = REF_001
        assertEquals("REF-001", phrase.id)
        assertEquals("Android architecture requires clean separation of concerns.", phrase.text)
        assertEquals(7, phrase.wordCount)
        assertEquals(2.3, phrase.expectedDurationSeconds, 1e-6)
        assertEquals(0.3, phrase.durationToleranceSeconds, 1e-6)
        assertEquals(2.0, phrase.minDurationSeconds, 1e-6)
        assertEquals(2.6, phrase.maxDurationSeconds, 1e-6)
        assertEquals(160.0, phrase.targetWpm, 1e-6)
        assertEquals(1200.0, phrase.minSpectralCentroidHz, 1e-6)
        assertEquals(2800.0, phrase.maxSpectralCentroidHz, 1e-6)
        assertTrue(phrase.phoneticFocus.contains("architecture"))
        assertTrue(phrase.phoneticFocus.contains("separation"))

        assertTrue(phrase.isDurationPassing(2.3))
        assertTrue(phrase.isDurationPassing(2.0))
        assertTrue(phrase.isDurationPassing(2.6))
        assertFalse(phrase.isDurationPassing(1.9))
        assertFalse(phrase.isDurationPassing(2.7))

        assertTrue(phrase.isSpectralCentroidPassing(2000.0))
        assertFalse(phrase.isSpectralCentroidPassing(1150.0))
        assertFalse(phrase.isSpectralCentroidPassing(2850.0))
    }

    @Test
    fun `REF-001 synthetic baseline audio satisfies all physical metric regression bounds`() {
        val phrase = REF_001
        val pcm = generateGoldenPhraseAudio(phrase)
        val inferenceTime = phrase.expectedDurationSeconds * 0.35 // RTF = 0.35
        val metrics = evaluateAudioMetrics(pcmData = pcm, inferenceDurationSeconds = inferenceTime, wordCount = phrase.wordCount)

        assertTrue(phrase.isDurationPassing(metrics.durationSeconds))
        assertEquals(RtfStatus.PASS, classifyRtf(metrics.rtf))
        assertFalse(metrics.hasClipping)
        assertTrue(metrics.peakAmplitude <= MAX_ALLOWED_PEAK_AMPLITUDE)
        assertTrue(metrics.rmsDbFs in MIN_TARGET_RMS_DBFS..MAX_TARGET_RMS_DBFS)
        assertTrue(metrics.leadingSilenceDurationMs <= MAX_LEADING_SILENCE_MS)
        assertTrue(phrase.isSpectralCentroidPassing(metrics.spectralCentroidHz))
        assertTrue(metrics.speakingRateWpm > 0.0)
    }

    // =========================================================================
    // Category 3: Reference Phrase 002 Tests
    // =========================================================================

    @Test
    fun `REF-002 verifies username formatting specification, duration bounds, and spectral limits`() {
        val phrase = REF_002
        assertEquals("REF-002", phrase.id)
        assertEquals("User cool coder 42 replies: What about memory overhead?", phrase.text)
        assertEquals(9, phrase.wordCount)
        assertEquals(2.9, phrase.expectedDurationSeconds, 1e-6)
        assertEquals(0.4, phrase.durationToleranceSeconds, 1e-6)
        assertEquals(2.5, phrase.minDurationSeconds, 1e-6)
        assertEquals(3.3, phrase.maxDurationSeconds, 1e-6)
        assertEquals(155.0, phrase.targetWpm, 1e-6)
        assertEquals(1200.0, phrase.minSpectralCentroidHz, 1e-6)
        assertEquals(2800.0, phrase.maxSpectralCentroidHz, 1e-6)
        assertTrue(phrase.phoneticFocus.contains("cool coder"))
        assertTrue(phrase.phoneticFocus.contains("overhead?"))

        assertTrue(phrase.isDurationPassing(2.9))
        assertTrue(phrase.isDurationPassing(2.5))
        assertTrue(phrase.isDurationPassing(3.3))
        assertFalse(phrase.isDurationPassing(2.4))
        assertFalse(phrase.isDurationPassing(3.4))
    }

    @Test
    fun `REF-002 synthetic baseline audio satisfies all physical metric regression bounds`() {
        val phrase = REF_002
        val pcm = generateGoldenPhraseAudio(phrase)
        val inferenceTime = phrase.expectedDurationSeconds * 0.32
        val metrics = evaluateAudioMetrics(pcmData = pcm, inferenceDurationSeconds = inferenceTime, wordCount = phrase.wordCount)

        assertTrue(phrase.isDurationPassing(metrics.durationSeconds))
        assertEquals(RtfStatus.PASS, classifyRtf(metrics.rtf))
        assertFalse(metrics.hasClipping)
        assertTrue(metrics.peakAmplitude <= MAX_ALLOWED_PEAK_AMPLITUDE)
        assertTrue(metrics.rmsDbFs in MIN_TARGET_RMS_DBFS..MAX_TARGET_RMS_DBFS)
        assertTrue(metrics.leadingSilenceDurationMs <= MAX_LEADING_SILENCE_MS)
        assertTrue(phrase.isSpectralCentroidPassing(metrics.spectralCentroidHz))
    }

    // =========================================================================
    // Category 4: Reference Phrase 003 Tests
    // =========================================================================

    @Test
    fun `REF-003 verifies numerical and acronym specification, duration bounds, and spectral limits`() {
        val phrase = REF_003
        assertEquals("REF-003", phrase.id)
        assertEquals("The model parameters are eighty-two million, quantized to INT8.", phrase.text)
        assertEquals(9, phrase.wordCount)
        assertEquals(3.4, phrase.expectedDurationSeconds, 1e-6)
        assertEquals(0.4, phrase.durationToleranceSeconds, 1e-6)
        assertEquals(3.0, phrase.minDurationSeconds, 1e-6)
        assertEquals(3.8, phrase.maxDurationSeconds, 1e-6)
        assertEquals(150.0, phrase.targetWpm, 1e-6)
        assertEquals(1100.0, phrase.minSpectralCentroidHz, 1e-6)
        assertEquals(2900.0, phrase.maxSpectralCentroidHz, 1e-6)
        assertTrue(phrase.phoneticFocus.contains("eighty-two"))
        assertTrue(phrase.phoneticFocus.contains("INT-eight"))

        assertTrue(phrase.isDurationPassing(3.4))
        assertTrue(phrase.isDurationPassing(3.0))
        assertTrue(phrase.isDurationPassing(3.8))
        assertFalse(phrase.isDurationPassing(2.9))
        assertFalse(phrase.isDurationPassing(3.9))
    }

    @Test
    fun `REF-003 synthetic baseline audio satisfies all physical metric regression bounds`() {
        val phrase = REF_003
        val pcm = generateGoldenPhraseAudio(phrase)
        val inferenceTime = phrase.expectedDurationSeconds * 0.38
        val metrics = evaluateAudioMetrics(pcmData = pcm, inferenceDurationSeconds = inferenceTime, wordCount = phrase.wordCount)

        assertTrue(phrase.isDurationPassing(metrics.durationSeconds))
        assertEquals(RtfStatus.PASS, classifyRtf(metrics.rtf))
        assertFalse(metrics.hasClipping)
        assertTrue(metrics.peakAmplitude <= MAX_ALLOWED_PEAK_AMPLITUDE)
        assertTrue(metrics.rmsDbFs in MIN_TARGET_RMS_DBFS..MAX_TARGET_RMS_DBFS)
        assertTrue(metrics.leadingSilenceDurationMs <= MAX_LEADING_SILENCE_MS)
        assertTrue(phrase.isSpectralCentroidPassing(metrics.spectralCentroidHz))
    }

    // =========================================================================
    // Category 5: Reference Phrase 004 Tests
    // =========================================================================

    @Test
    fun `REF-004 verifies dialogue prefix specification, duration bounds, and spectral limits`() {
        val phrase = REF_004
        assertEquals("REF-004", phrase.id)
        assertEquals(
            "Post by system engineer: Warning, thermal throttling will degrade RTF below real time.",
            phrase.text,
        )
        assertEquals(13, phrase.wordCount)
        assertEquals(4.6, phrase.expectedDurationSeconds, 1e-6)
        assertEquals(0.5, phrase.durationToleranceSeconds, 1e-6)
        assertEquals(4.1, phrase.minDurationSeconds, 1e-6)
        assertEquals(5.1, phrase.maxDurationSeconds, 1e-6)
        assertEquals(160.0, phrase.targetWpm, 1e-6)
        assertEquals(1200.0, phrase.minSpectralCentroidHz, 1e-6)
        assertEquals(2800.0, phrase.maxSpectralCentroidHz, 1e-6)
        assertTrue(phrase.phoneticFocus.contains("Post by..."))
        assertTrue(phrase.phoneticFocus.contains("R-T-F"))

        assertTrue(phrase.isDurationPassing(4.6))
        assertTrue(phrase.isDurationPassing(4.1))
        assertTrue(phrase.isDurationPassing(5.1))
        assertFalse(phrase.isDurationPassing(4.0))
        assertFalse(phrase.isDurationPassing(5.2))
    }

    @Test
    fun `REF-004 synthetic baseline audio satisfies all physical metric regression bounds`() {
        val phrase = REF_004
        val pcm = generateGoldenPhraseAudio(phrase)
        val inferenceTime = phrase.expectedDurationSeconds * 0.36
        val metrics = evaluateAudioMetrics(pcmData = pcm, inferenceDurationSeconds = inferenceTime, wordCount = phrase.wordCount)

        assertTrue(phrase.isDurationPassing(metrics.durationSeconds))
        assertEquals(RtfStatus.PASS, classifyRtf(metrics.rtf))
        assertFalse(metrics.hasClipping)
        assertTrue(metrics.peakAmplitude <= MAX_ALLOWED_PEAK_AMPLITUDE)
        assertTrue(metrics.rmsDbFs in MIN_TARGET_RMS_DBFS..MAX_TARGET_RMS_DBFS)
        assertTrue(metrics.leadingSilenceDurationMs <= MAX_LEADING_SILENCE_MS)
        assertTrue(phrase.isSpectralCentroidPassing(metrics.spectralCentroidHz))
    }

    // =========================================================================
    // Category 6: Reference Phrase 005 Tests
    // =========================================================================

    @Test
    fun `REF-005 verifies contraction and pause specification, duration bounds, and spectral limits`() {
        val phrase = REF_005
        assertEquals("REF-005", phrase.id)
        assertEquals("Yes! Exactly. Couldn't agree more.", phrase.text)
        assertEquals(5, phrase.wordCount)
        assertEquals(1.7, phrase.expectedDurationSeconds, 1e-6)
        assertEquals(0.3, phrase.durationToleranceSeconds, 1e-6)
        assertEquals(1.4, phrase.minDurationSeconds, 1e-6)
        assertEquals(2.0, phrase.maxDurationSeconds, 1e-6)
        assertEquals(140.0, phrase.targetWpm, 1e-6)
        assertEquals(1300.0, phrase.minSpectralCentroidHz, 1e-6)
        assertEquals(3000.0, phrase.maxSpectralCentroidHz, 1e-6)
        assertTrue(phrase.phoneticFocus.contains("Couldn't"))

        assertTrue(phrase.isDurationPassing(1.7))
        assertTrue(phrase.isDurationPassing(1.4))
        assertTrue(phrase.isDurationPassing(2.0))
        assertFalse(phrase.isDurationPassing(1.3))
        assertFalse(phrase.isDurationPassing(2.1))
    }

    @Test
    fun `REF-005 synthetic baseline audio satisfies all physical metric regression bounds`() {
        val phrase = REF_005
        val pcm = generateGoldenPhraseAudio(phrase)
        val inferenceTime = phrase.expectedDurationSeconds * 0.28
        val metrics = evaluateAudioMetrics(pcmData = pcm, inferenceDurationSeconds = inferenceTime, wordCount = phrase.wordCount)

        assertTrue(phrase.isDurationPassing(metrics.durationSeconds))
        assertEquals(RtfStatus.PASS, classifyRtf(metrics.rtf))
        assertFalse(metrics.hasClipping)
        assertTrue(metrics.peakAmplitude <= MAX_ALLOWED_PEAK_AMPLITUDE)
        assertTrue(metrics.rmsDbFs in MIN_TARGET_RMS_DBFS..MAX_TARGET_RMS_DBFS)
        assertTrue(metrics.leadingSilenceDurationMs <= MAX_LEADING_SILENCE_MS)
        assertTrue(phrase.isSpectralCentroidPassing(metrics.spectralCentroidHz))
    }

    // =========================================================================
    // Category 7: Real-Time Factor (RTF) Metric Tests
    // =========================================================================

    @Test
    fun `calculateRtf correctly computes ratio of inference duration to audio duration`() {
        val rtf = calculateRtf(inferenceDurationSeconds = 0.8, audioDurationSeconds = 2.0)
        assertEquals(0.40, rtf, 1e-6)

        val rtf2 = calculateRtf(inferenceDurationSeconds = 0.5, audioDurationSeconds = 2.0)
        assertEquals(0.25, rtf2, 1e-6)

        val rtf3 = calculateRtf(inferenceDurationSeconds = 1.2, audioDurationSeconds = 2.0)
        assertEquals(0.60, rtf3, 1e-6)
    }

    @Test
    fun `classifyRtf classifies RTF at or below 0_40 as PASS`() {
        assertEquals(RtfStatus.PASS, classifyRtf(0.10))
        assertEquals(RtfStatus.PASS, classifyRtf(0.35))
        assertEquals(RtfStatus.PASS, classifyRtf(0.40))
    }

    @Test
    fun `classifyRtf classifies RTF between 0_40 and 0_50 as BOUNDARY_WARNING`() {
        assertEquals(RtfStatus.BOUNDARY_WARNING, classifyRtf(0.4001))
        assertEquals(RtfStatus.BOUNDARY_WARNING, classifyRtf(0.45))
        assertEquals(RtfStatus.BOUNDARY_WARNING, classifyRtf(0.50))
    }

    @Test
    fun `classifyRtf classifies RTF exceeding 0_50 as FAILURE`() {
        assertEquals(RtfStatus.FAILURE, classifyRtf(0.5001))
        assertEquals(RtfStatus.FAILURE, classifyRtf(0.75))
        assertEquals(RtfStatus.FAILURE, classifyRtf(1.50))
        assertEquals(RtfStatus.FAILURE, classifyRtf(Double.POSITIVE_INFINITY))
        assertEquals(RtfStatus.FAILURE, classifyRtf(Double.NaN))
    }

    @Test
    fun `calculateRtf handles edge cases including zero audio duration and zero inference time`() {
        assertEquals(0.0, calculateRtf(0.0, 1.0), 1e-6)
        assertEquals(0.0, calculateRtf(0.0, 0.0), 1e-6)
        assertEquals(Double.POSITIVE_INFINITY, calculateRtf(1.0, 0.0))
        assertEquals(0.0, calculateRtf(-0.5, 2.0), 1e-6)
    }

    // =========================================================================
    // Category 8: Signal-to-Quantization-Noise Ratio (SQNR) Metric Tests
    // =========================================================================

    @Test
    fun `calculateSqnr returns positive infinity for identical reference and quantized signals`() {
        val signal = shortArrayOf(100, 200, -300, 400, 500)
        val sqnr = calculateSqnr(signal, signal.copyOf())
        assertEquals(Double.POSITIVE_INFINITY, sqnr)

        val byteSignal = byteArrayOf(1, 2, 3, 4, 5, 6)
        val byteSqnr = calculateSqnr(byteSignal, byteSignal.copyOf())
        assertEquals(Double.POSITIVE_INFINITY, byteSqnr)
    }

    @Test
    fun `calculateSqnr returns negative infinity when reference signal is completely silent`() {
        val silence = ShortArray(100) { 0 }
        val noise = ShortArray(100) { 5 }
        assertEquals(Double.NEGATIVE_INFINITY, calculateSqnr(silence, noise))
        assertEquals(Double.NEGATIVE_INFINITY, calculateSqnr(silence, silence))
    }

    @Test
    fun `calculateSqnr evaluates acceptable quantization noise meeting or exceeding 28 dB threshold`() {
        // High signal amplitude (10,000) with minor rounding noise (+/- 1) yields SQNR > 60 dB
        val ref = ShortArray(1000) { 10000.toShort() }
        val quant = ShortArray(1000) { (10000 + (it % 3 - 1)).toShort() }

        val sqnr = calculateSqnr(ref, quant)
        assertTrue(sqnr >= SQNR_PASS_THRESHOLD_DB, "SQNR $sqnr dB must meet pass threshold $SQNR_PASS_THRESHOLD_DB dB")
    }

    @Test
    fun `calculateSqnr evaluates distorted quantization noise falling below 28 dB threshold`() {
        // Signal amplitude (1000) with heavy distortion (+/- 300) yields SQNR around 10 dB (< 28 dB)
        val ref = ShortArray(1000) { 1000.toShort() }
        val distorted = ShortArray(1000) { (1000 + if (it % 2 == 0) 300 else -300).toShort() }

        val sqnr = calculateSqnr(ref, distorted)
        assertTrue(sqnr < SQNR_PASS_THRESHOLD_DB, "Distorted SQNR $sqnr dB must trigger fail threshold")
    }

    @Test
    fun `calculateSqnr throws IllegalArgumentException when reference and quantized signals differ in length`() {
        val s1 = ShortArray(10)
        val s2 = ShortArray(12)
        assertThrows(IllegalArgumentException::class.java) {
            calculateSqnr(s1, s2)
        }

        val b1 = ByteArray(10)
        val b2 = ByteArray(8)
        assertThrows(IllegalArgumentException::class.java) {
            calculateSqnr(b1, b2)
        }
    }

    // =========================================================================
    // Category 9: Spectral Centroid Metric Tests
    // =========================================================================

    @Test
    fun `calculateSpectralCentroid from explicit magnitude spectrum calculates exact weighted centroid`() {
        // Bin 0: 0 Hz (mag 0.0), Bin 1: 1000 Hz (mag 10.0), Bin 2: 2000 Hz (mag 0.0) -> Centroid = 1000 Hz
        val magnitudes = doubleArrayOf(0.0, 10.0, 0.0)
        val binFrequencies = doubleArrayOf(0.0, 1000.0, 2000.0)

        val centroid = calculateSpectralCentroid(magnitudes, binFrequencies)
        assertEquals(1000.0, centroid, 1e-6)

        // Equal magnitudes at 1000 Hz and 2000 Hz -> Centroid = 1500 Hz
        val equalMags = doubleArrayOf(0.0, 5.0, 5.0)
        val equalCentroid = calculateSpectralCentroid(equalMags, binFrequencies)
        assertEquals(1500.0, equalCentroid, 1e-6)
    }

    @Test
    fun `calculateSpectralCentroid returns zero for pure silence or empty audio`() {
        val silence = ShortArray(2048) { 0 }
        assertEquals(0.0, calculateSpectralCentroid(silence), 1e-6)

        val empty = ShortArray(0)
        assertEquals(0.0, calculateSpectralCentroid(empty), 1e-6)

        val zeroBytes = ByteArray(100) { 0 }
        assertEquals(0.0, calculateSpectralCentroid(zeroBytes), 1e-6)
    }

    @Test
    fun `calculateSpectralCentroid calculates accurate centroid for pure tone sinusoidal waveforms`() {
        val sampleRate = WavUtil.DEFAULT_SAMPLE_RATE
        val duration = 0.5

        // Pure 1,500 Hz tone -> centroid should be within +/- 60 Hz of 1,500 Hz
        val pcm1500 =
            generateSyntheticPcm(
                toneFrequencyHz = 1500.0,
                toneDurationSeconds = duration,
                sampleRate = sampleRate,
                leadingSilenceSeconds = 0.0,
                trailingSilenceSeconds = 0.0,
            )
        val centroid1500 = calculateSpectralCentroid(pcm1500, sampleRate)
        assertTrue(centroid1500 in 1440.0..1560.0, "Expected ~1500 Hz, got $centroid1500 Hz")

        // Pure 2,200 Hz tone -> centroid should be within +/- 60 Hz of 2,200 Hz
        val pcm2200 =
            generateSyntheticPcm(
                toneFrequencyHz = 2200.0,
                toneDurationSeconds = duration,
                sampleRate = sampleRate,
                leadingSilenceSeconds = 0.0,
                trailingSilenceSeconds = 0.0,
            )
        val centroid2200 = calculateSpectralCentroid(pcm2200, sampleRate)
        assertTrue(centroid2200 in 2140.0..2260.0, "Expected ~2200 Hz, got $centroid2200 Hz")
    }

    @Test
    fun `calculateSpectralCentroid accurately tracks frequency shifts across distinct spectral bands`() {
        val sampleRate = WavUtil.DEFAULT_SAMPLE_RATE
        val pcmLow =
            generateSyntheticPcm(
                toneFrequencyHz = 1200.0,
                toneDurationSeconds = 0.4,
                sampleRate = sampleRate,
                leadingSilenceSeconds = 0.0,
                trailingSilenceSeconds = 0.0,
            )
        val pcmMid =
            generateSyntheticPcm(
                toneFrequencyHz = 2000.0,
                toneDurationSeconds = 0.4,
                sampleRate = sampleRate,
                leadingSilenceSeconds = 0.0,
                trailingSilenceSeconds = 0.0,
            )
        val pcmHigh =
            generateSyntheticPcm(
                toneFrequencyHz = 2800.0,
                toneDurationSeconds = 0.4,
                sampleRate = sampleRate,
                leadingSilenceSeconds = 0.0,
                trailingSilenceSeconds = 0.0,
            )

        val cLow = calculateSpectralCentroid(pcmLow, sampleRate)
        val cMid = calculateSpectralCentroid(pcmMid, sampleRate)
        val cHigh = calculateSpectralCentroid(pcmHigh, sampleRate)

        assertTrue(cLow < cMid, "Low centroid ($cLow) must be below mid ($cMid)")
        assertTrue(cMid < cHigh, "Mid centroid ($cMid) must be below high ($cHigh)")
    }

    // =========================================================================
    // Category 10: Silence & Amplitude Bounds Tests
    // =========================================================================

    @Test
    fun `detectClipping passes audio below threshold and flags audio at or exceeding 32000`() {
        // Safe amplitude (4,130) -> no clipping
        val safePcm = generateSyntheticPcm(toneFrequencyHz = 1000.0, toneDurationSeconds = 0.1, amplitude = 4130)
        assertFalse(WavUtil.detectClipping(safePcm, threshold = MAX_ALLOWED_PEAK_AMPLITUDE))

        // Clipped amplitude (32,500) -> clipping detected
        val clippedPcm = generateSyntheticPcm(toneFrequencyHz = 1000.0, toneDurationSeconds = 0.1, amplitude = 32500.toShort())
        assertTrue(WavUtil.detectClipping(clippedPcm, threshold = MAX_ALLOWED_PEAK_AMPLITUDE))
    }

    @Test
    fun `calculatePeakAmplitude measures maximum absolute sample value clamped to Short MAX_VALUE`() {
        val pcm = generateSyntheticPcm(toneFrequencyHz = 1000.0, toneDurationSeconds = 0.1, amplitude = 12345)
        val peak = WavUtil.calculatePeakAmplitude(pcm)
        assertTrue(peak in 12000..12345, "Peak amplitude should match generated amplitude: $peak")

        val emptyPcm = ByteArray(0)
        assertEquals(0.toShort(), WavUtil.calculatePeakAmplitude(emptyPcm))
    }

    @Test
    fun `calculateRmsDbFs evaluates signals within target -18 dBFS to -24 dBFS range`() {
        // Amplitude 4,130 yields sine RMS ~ 2,920 -> ~ -21.0 dBFS
        val pcmTarget =
            generateSyntheticPcm(
                toneFrequencyHz = 1000.0,
                toneDurationSeconds = 1.0,
                amplitude = 4130,
                leadingSilenceSeconds = 0.0,
                trailingSilenceSeconds = 0.0,
            )
        val rmsDbFs = WavUtil.calculateRmsDbFs(pcmTarget)
        assertTrue(
            rmsDbFs in MIN_TARGET_RMS_DBFS..MAX_TARGET_RMS_DBFS,
            "RMS $rmsDbFs dBFS must fall in [$MIN_TARGET_RMS_DBFS, $MAX_TARGET_RMS_DBFS]",
        )

        // Silent audio yields negative infinity
        val silentPcm = ByteArray(1000) { 0 }
        assertEquals(Double.NEGATIVE_INFINITY, WavUtil.calculateRmsDbFs(silentPcm))
    }

    @Test
    fun `calculateLeadingSilenceDurationMs accurately measures initial silence duration and checks 120ms bound`() {
        val sampleRate = WavUtil.DEFAULT_SAMPLE_RATE

        // 50 ms leading silence -> passes <= 120 ms bound
        val pcm50ms =
            generateSyntheticPcm(
                toneFrequencyHz = 1000.0,
                toneDurationSeconds = 0.5,
                leadingSilenceSeconds = 0.05,
                trailingSilenceSeconds = 0.0,
            )
        val silence50ms = calculateLeadingSilenceDurationMs(pcm50ms, sampleRate)
        assertTrue(silence50ms in 48.0..52.0, "Expected ~50ms, got $silence50ms ms")
        assertTrue(silence50ms <= MAX_LEADING_SILENCE_MS)

        // 100 ms leading silence -> passes <= 120 ms bound
        val pcm100ms =
            generateSyntheticPcm(
                toneFrequencyHz = 1000.0,
                toneDurationSeconds = 0.5,
                leadingSilenceSeconds = 0.10,
                trailingSilenceSeconds = 0.0,
            )
        val silence100ms = calculateLeadingSilenceDurationMs(pcm100ms, sampleRate)
        assertTrue(silence100ms in 98.0..102.0, "Expected ~100ms, got $silence100ms ms")
        assertTrue(silence100ms <= MAX_LEADING_SILENCE_MS)

        // 150 ms leading silence -> fails > 120 ms bound
        val pcm150ms =
            generateSyntheticPcm(
                toneFrequencyHz = 1000.0,
                toneDurationSeconds = 0.5,
                leadingSilenceSeconds = 0.15,
                trailingSilenceSeconds = 0.0,
            )
        val silence150ms = calculateLeadingSilenceDurationMs(pcm150ms, sampleRate)
        assertTrue(silence150ms > MAX_LEADING_SILENCE_MS, "150ms should exceed $MAX_LEADING_SILENCE_MS ms threshold")
    }

    @Test
    fun `calculateLeadingSilenceDurationMs returns total duration for completely silent buffer`() {
        val sampleRate = 22050
        val silentBytes = ByteArray(22050 * 2) { 0 } // 1.0 second of silence
        val durationMs = calculateLeadingSilenceDurationMs(silentBytes, sampleRate)
        assertEquals(1000.0, durationMs, 1e-3)
    }

    // =========================================================================
    // Category 11: Comprehensive Audio Quality Evaluation & Test Helpers
    // =========================================================================

    @Test
    fun `calculateSpeakingRateWpm calculates words per minute and handles zero duration edge cases`() {
        // 10 words in 3.0 seconds -> (10 / 3) * 60 = 200 WPM
        assertEquals(200.0, calculateSpeakingRateWpm(10, 3.0), 1e-6)

        // 7 words in 2.3 seconds -> (7 / 2.3) * 60 = 182.6 WPM
        assertEquals(182.6086956521739, calculateSpeakingRateWpm(7, 2.3), 1e-6)

        // Edge cases
        assertEquals(0.0, calculateSpeakingRateWpm(0, 2.0), 1e-6)
        assertEquals(0.0, calculateSpeakingRateWpm(10, 0.0), 1e-6)
        assertEquals(0.0, calculateSpeakingRateWpm(10, -1.0), 1e-6)
    }

    @Test
    fun `evaluateAudioMetrics aggregates all physical metrics into cohesive report`() {
        val pcm =
            generateSyntheticPcm(
                toneFrequencyHz = 1800.0,
                toneDurationSeconds = 2.0,
                amplitude = 4130,
                leadingSilenceSeconds = 0.04,
                trailingSilenceSeconds = 0.04,
            )
        val metrics =
            evaluateAudioMetrics(
                pcmData = pcm,
                referencePcmData = pcm.copyOf(),
                inferenceDurationSeconds = 0.6,
                wordCount = 7,
            )

        assertEquals(2.08, metrics.durationSeconds, 0.01)
        assertEquals(0.6 / metrics.durationSeconds, metrics.rtf, 1e-4)
        assertEquals(Double.POSITIVE_INFINITY, metrics.sqnrDb)
        assertFalse(metrics.hasClipping)
        assertTrue(metrics.peakAmplitude in 4000..4130)
        assertTrue(metrics.rmsDbFs in MIN_TARGET_RMS_DBFS..MAX_TARGET_RMS_DBFS)
        assertTrue(metrics.leadingSilenceDurationMs <= MAX_LEADING_SILENCE_MS)
        assertTrue(metrics.spectralCentroidHz in 1700.0..1900.0)
        assertTrue(metrics.speakingRateWpm > 0.0)
    }

    @Test
    fun `all golden phrases pass batch baseline regression validation against synthetic reference waveforms`() {
        for (phrase in GOLDEN_PHRASES) {
            val pcm = generateGoldenPhraseAudio(phrase)
            val simulatedInferenceTime = phrase.expectedDurationSeconds * 0.35
            val metrics =
                evaluateAudioMetrics(
                    pcmData = pcm,
                    referencePcmData = pcm.copyOf(),
                    inferenceDurationSeconds = simulatedInferenceTime,
                    wordCount = phrase.wordCount,
                )

            assertTrue(phrase.isDurationPassing(metrics.durationSeconds), "Duration failed for ${phrase.id}")
            assertEquals(RtfStatus.PASS, classifyRtf(metrics.rtf), "RTF status failed for ${phrase.id}")
            assertEquals(Double.POSITIVE_INFINITY, metrics.sqnrDb, "SQNR failed for ${phrase.id}")
            assertFalse(metrics.hasClipping, "Clipping detected for ${phrase.id}")
            assertTrue(metrics.peakAmplitude <= MAX_ALLOWED_PEAK_AMPLITUDE, "Peak amplitude exceeded for ${phrase.id}")
            assertTrue(
                metrics.rmsDbFs in MIN_TARGET_RMS_DBFS..MAX_TARGET_RMS_DBFS,
                "RMS ${metrics.rmsDbFs} out of target bounds for ${phrase.id}",
            )
            assertTrue(
                metrics.leadingSilenceDurationMs <= MAX_LEADING_SILENCE_MS,
                "Leading silence ${metrics.leadingSilenceDurationMs}ms exceeded for ${phrase.id}",
            )
            assertTrue(
                phrase.isSpectralCentroidPassing(metrics.spectralCentroidHz),
                "Spectral centroid ${metrics.spectralCentroidHz}Hz out of bounds for ${phrase.id}",
            )
            assertTrue(metrics.speakingRateWpm > 0.0, "Speaking rate must be positive for ${phrase.id}")
        }
    }
}
