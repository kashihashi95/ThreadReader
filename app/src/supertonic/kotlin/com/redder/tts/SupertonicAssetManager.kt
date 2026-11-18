package com.redder.tts

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Manages storage, discovery, and integrity checking for Supertonic 3 ONNX TTS assets.
 */
object SupertonicAssetManager {
    const val ASSET_SUBDIR: String = "supertonic"
    const val ONNX_SUBDIR: String = "onnx"
    const val VOICE_STYLES_SUBDIR: String = "voice_styles"

    const val DURATION_PREDICTOR_FILENAME: String = "duration_predictor.onnx"
    const val TEXT_ENCODER_FILENAME: String = "text_encoder.onnx"
    const val VECTOR_ESTIMATOR_FILENAME: String = "vector_estimator.onnx"
    const val VOCODER_FILENAME: String = "vocoder.onnx"
    const val TTS_CONFIG_FILENAME: String = "tts.json"
    const val UNICODE_INDEXER_FILENAME: String = "unicode_indexer.json"
    const val LEGACY_MODEL_FILENAME: String = "supertonic.onnx"

    /**
     * Verifies whether a valid Supertonic model is available within the given directory.
     */
    fun isModelAvailableInDir(dir: File?): Boolean {
        if (dir == null || !dir.exists()) return false
        val onnxSubdir = File(dir, ONNX_SUBDIR)
        val checkDir = if (File(onnxSubdir, DURATION_PREDICTOR_FILENAME).exists()) onnxSubdir else dir
        val has4Models =
            verifyIntegrity(File(checkDir, DURATION_PREDICTOR_FILENAME)) &&
                verifyIntegrity(File(checkDir, TEXT_ENCODER_FILENAME)) &&
                verifyIntegrity(File(checkDir, VECTOR_ESTIMATOR_FILENAME)) &&
                verifyIntegrity(File(checkDir, VOCODER_FILENAME))

        if (has4Models) return true

        val singleModel = File(checkDir, LEGACY_MODEL_FILENAME)
        return verifyIntegrity(singleModel)
    }

    /**
     * Resolves the target directory on local app storage for unpacked Supertonic assets.
     */
    fun getSupertonicDir(context: Context): File {
        val internalDir = File(context.filesDir, ASSET_SUBDIR)
        val externalDir = runCatching { context.getExternalFilesDir(null) }.getOrNull()?.let { File(it, ASSET_SUBDIR) }
        return when {
            isModelAvailableInDir(internalDir) -> internalDir
            externalDir != null && isModelAvailableInDir(externalDir) -> externalDir
            externalDir != null && externalDir.exists() -> externalDir
            else -> internalDir
        }
    }

    /**
     * Resolves the directory containing the ONNX models (checks subfolder "onnx" or root).
     */
    fun getOnnxDir(context: Context): File {
        val baseDir = getSupertonicDir(context)
        val onnxSubdir = File(baseDir, ONNX_SUBDIR)
        return if (File(onnxSubdir, DURATION_PREDICTOR_FILENAME).exists()) {
            onnxSubdir
        } else {
            baseDir
        }
    }

    /**
     * Resolves the directory containing preset voice style JSON files.
     */
    fun getVoiceStylesDir(context: Context): File {
        val baseDir = getSupertonicDir(context)
        val stylesSubdir = File(baseDir, VOICE_STYLES_SUBDIR)
        return if (stylesSubdir.exists()) stylesSubdir else baseDir
    }

    /**
     * Resolves the expected location of the primary model or ONNX directory.
     */
    fun getModelFile(context: Context): File {
        val onnxDir = getOnnxDir(context)
        val dpFile = File(onnxDir, DURATION_PREDICTOR_FILENAME)
        if (dpFile.exists()) return onnxDir

        val singleModel = File(onnxDir, LEGACY_MODEL_FILENAME)
        if (singleModel.exists()) return singleModel

        return onnxDir
    }

    /**
     * Checks if the Supertonic ONNX models exist and pass basic integrity checks.
     */
    fun isModelAvailable(context: Context): Boolean {
        val internalDir = File(context.filesDir, ASSET_SUBDIR)
        val externalDir = runCatching { context.getExternalFilesDir(null) }.getOrNull()?.let { File(it, ASSET_SUBDIR) }
        if (isModelAvailableInDir(internalDir)) return true
        if (externalDir != null && isModelAvailableInDir(externalDir)) return true

        val onnxDir = getOnnxDir(context)
        return isModelAvailableInDir(onnxDir)
    }

    /**
     * Unpacks bundled Supertonic model assets from the APK assets directory
     * into local storage if they are missing or corrupt.
     *
     * Synchronized so the dashboard and the playback service never copy the same files concurrently.
     * Must be called off the main thread: the bundled models are several hundred MB.
     *
     * @param context Android application context.
     * @return True if assets exist and are verified, false otherwise.
     */
    @Synchronized
    fun unpackAssetsIfMissing(context: Context): Boolean {
        val targetDir = getSupertonicDir(context)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        try {
            unpackAssetFolder(context, ASSET_SUBDIR, targetDir)
        } catch (_: Throwable) {
            // Assets directory might not contain supertonic files in some environments
        }

        return isModelAvailable(context)
    }

    private fun unpackAssetFolder(
        context: Context,
        assetPath: String,
        targetDir: File,
    ) {
        val assets = context.assets.list(assetPath) ?: return
        for (item in assets) {
            val subAssetPath = "$assetPath/$item"
            val subAssets = context.assets.list(subAssetPath)
            if (!subAssets.isNullOrEmpty()) {
                val subDir = File(targetDir, item)
                if (!subDir.exists()) subDir.mkdirs()
                unpackAssetFolder(context, subAssetPath, subDir)
            } else {
                val targetFile = File(targetDir, item)
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    val tempFile = File(targetDir, ".tmp_$item")
                    context.assets.open(subAssetPath).use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                    tempFile.renameTo(targetFile)
                }
            }
        }
    }

    /**
     * Verifies that the specified file exists, is a regular file, and has a size >= [expectedMinBytes].
     */
    fun verifyIntegrity(
        file: File?,
        expectedMinBytes: Long = 100L,
    ): Boolean {
        return file != null && file.exists() && file.isFile && file.length() >= expectedMinBytes
    }
}
