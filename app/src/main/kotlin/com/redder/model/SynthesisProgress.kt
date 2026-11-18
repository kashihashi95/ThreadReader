package com.redder.model

/**
 * Stages representing the progression of neural audio synthesis for Reddit dialogue.
 */
enum class SynthesisStage {
    IDLE,
    FETCHING_THREAD,
    DOWNLOADING_MODEL,
    SYNTHESIZING,
    READY,
    ERROR,
}

/**
 * Domain model capturing real-time neural audio synthesis progress and download status.
 *
 * @property currentChunk Index of the dialogue chunk currently being synthesized.
 * @property totalChunks Total number of dialogue utterances to synthesize.
 * @property stage Current synthesis operational stage.
 * @property progressPercent Optional download progress percentage in range [0.0, 100.0].
 */
data class SynthesisProgress(
    val currentChunk: Int = 0,
    val totalChunks: Int = 0,
    val stage: SynthesisStage = SynthesisStage.IDLE,
    val progressPercent: Float? = null,
)
