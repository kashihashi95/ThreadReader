package com.redder.tts

import android.content.Context
import com.redder.settings.RedderPreferences
import java.io.File

/** Builds the Supertonic engine from its unpacked files, for the builds that run it on the phone. */
object SupertonicEngineFactory {
    /** Throws when the files cannot be read. [fallback] speaks when an inference fails; null leaves the failure to the caller. */
    fun create(
        context: Context,
        fallback: () -> TtsEngine?,
    ): TtsEngine {
        val voiceStylesDir = SupertonicAssetManager.getVoiceStylesDir(context)
        SupertonicVoiceCatalog.loadVoiceStylesFromDir(voiceStylesDir)
        val modelFile = SupertonicAssetManager.getModelFile(context)
        val onnxDir = SupertonicAssetManager.getOnnxDir(context)
        val indexerFile = File(onnxDir, SupertonicAssetManager.UNICODE_INDEXER_FILENAME)
        val textProcessor =
            if (indexerFile.exists()) {
                SupertonicTextProcessor.fromFile(indexerFile)
            } else {
                SupertonicTextProcessor()
            }
        val prefs = context.getSharedPreferences(RedderPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        return SupertonicOnnxTtsEngine(
            modelPathResolver = { modelFile.absolutePath },
            textProcessor = textProcessor,
            fallbackEngine = fallback(),
            // Read at synthesis time so a dashboard change applies to the next utterance.
            totalStepsProvider = {
                prefs.getInt(RedderPreferences.KEY_QUALITY_STEPS, RedderPreferences.DEFAULT_QUALITY_STEPS)
            },
        )
    }
}
