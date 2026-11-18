package com.redder.tts

import android.content.Context
import android.content.res.AssetManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

class SupertonicAssetManagerTest {
    @field:TempDir
    lateinit var tempRoot: File

    private lateinit var internalDir: File
    private lateinit var mockContext: Context
    private lateinit var mockAssetManager: AssetManager

    @BeforeEach
    fun setUp() {
        internalDir = File(tempRoot, "internal_files")
        internalDir.mkdirs()

        mockContext = mock(Context::class.java)
        mockAssetManager = mock(AssetManager::class.java)

        `when`(mockContext.filesDir).thenReturn(internalDir)
        `when`(mockContext.getExternalFilesDir(null)).thenReturn(null)
        `when`(mockContext.assets).thenReturn(mockAssetManager)
    }

    private fun createDummyFile(
        dir: File,
        name: String,
        sizeBytes: Int = 200,
    ): File {
        dir.mkdirs()
        val file = File(dir, name)
        FileOutputStream(file).use { out ->
            out.write(ByteArray(sizeBytes) { 1.toByte() })
        }
        return file
    }

    @Test
    fun `verifyIntegrity validates non-null regular file with sufficient size`() {
        assertFalse(SupertonicAssetManager.verifyIntegrity(null))

        val nonExistent = File(tempRoot, "missing.onnx")
        assertFalse(SupertonicAssetManager.verifyIntegrity(nonExistent))

        val directory = File(tempRoot, "subfolder").apply { mkdirs() }
        assertFalse(SupertonicAssetManager.verifyIntegrity(directory))

        val smallFile = createDummyFile(tempRoot, "small.onnx", sizeBytes = 50)
        assertFalse(SupertonicAssetManager.verifyIntegrity(smallFile, expectedMinBytes = 100L))

        val validFile = createDummyFile(tempRoot, "valid.onnx", sizeBytes = 150)
        assertTrue(SupertonicAssetManager.verifyIntegrity(validFile, expectedMinBytes = 100L))
    }

    @Test
    fun `isModelAvailableInDir returns false when dir is null or non-existent`() {
        assertFalse(SupertonicAssetManager.isModelAvailableInDir(null))
        assertFalse(SupertonicAssetManager.isModelAvailableInDir(File(tempRoot, "does_not_exist")))
    }

    @Test
    fun `isModelAvailableInDir returns true when all 4 neural models exist in onnx subfolder`() {
        val modelBaseDir = File(tempRoot, "supertonic")
        val onnxDir = File(modelBaseDir, SupertonicAssetManager.ONNX_SUBDIR)

        createDummyFile(onnxDir, SupertonicAssetManager.DURATION_PREDICTOR_FILENAME)
        createDummyFile(onnxDir, SupertonicAssetManager.TEXT_ENCODER_FILENAME)
        createDummyFile(onnxDir, SupertonicAssetManager.VECTOR_ESTIMATOR_FILENAME)
        createDummyFile(onnxDir, SupertonicAssetManager.VOCODER_FILENAME)

        assertTrue(SupertonicAssetManager.isModelAvailableInDir(modelBaseDir))
    }

    @Test
    fun `isModelAvailableInDir returns false if any of the 4 models is missing`() {
        val modelBaseDir = File(tempRoot, "supertonic")
        val onnxDir = File(modelBaseDir, SupertonicAssetManager.ONNX_SUBDIR)

        createDummyFile(onnxDir, SupertonicAssetManager.DURATION_PREDICTOR_FILENAME)
        createDummyFile(onnxDir, SupertonicAssetManager.TEXT_ENCODER_FILENAME)
        // Missing VECTOR_ESTIMATOR_FILENAME
        createDummyFile(onnxDir, SupertonicAssetManager.VOCODER_FILENAME)

        assertFalse(SupertonicAssetManager.isModelAvailableInDir(modelBaseDir))
    }

    @Test
    fun `isModelAvailableInDir returns true for legacy single model`() {
        val modelBaseDir = File(tempRoot, "supertonic_legacy")
        createDummyFile(modelBaseDir, SupertonicAssetManager.LEGACY_MODEL_FILENAME)

        assertTrue(SupertonicAssetManager.isModelAvailableInDir(modelBaseDir))
    }

    @Test
    fun `getOnnxDir and getVoiceStylesDir resolve existing subdirectories`() {
        val supertonicDir = File(internalDir, SupertonicAssetManager.ASSET_SUBDIR)
        val onnxDir = File(supertonicDir, SupertonicAssetManager.ONNX_SUBDIR)
        val stylesDir = File(supertonicDir, SupertonicAssetManager.VOICE_STYLES_SUBDIR)

        createDummyFile(onnxDir, SupertonicAssetManager.DURATION_PREDICTOR_FILENAME)
        createDummyFile(onnxDir, SupertonicAssetManager.TEXT_ENCODER_FILENAME)
        createDummyFile(onnxDir, SupertonicAssetManager.VECTOR_ESTIMATOR_FILENAME)
        createDummyFile(onnxDir, SupertonicAssetManager.VOCODER_FILENAME)
        stylesDir.mkdirs()

        assertEquals(onnxDir, SupertonicAssetManager.getOnnxDir(mockContext))
        assertEquals(stylesDir, SupertonicAssetManager.getVoiceStylesDir(mockContext))
        assertEquals(onnxDir, SupertonicAssetManager.getModelFile(mockContext))
    }

    @Test
    fun `unpackAssetsIfMissing unpacks assets and isModelAvailable becomes true`() {
        assertFalse(SupertonicAssetManager.isModelAvailable(mockContext))

        `when`(mockAssetManager.list(SupertonicAssetManager.ASSET_SUBDIR))
            .thenReturn(arrayOf(SupertonicAssetManager.ONNX_SUBDIR, SupertonicAssetManager.VOICE_STYLES_SUBDIR))

        val onnxFiles =
            arrayOf(
                SupertonicAssetManager.DURATION_PREDICTOR_FILENAME,
                SupertonicAssetManager.TEXT_ENCODER_FILENAME,
                SupertonicAssetManager.VECTOR_ESTIMATOR_FILENAME,
                SupertonicAssetManager.VOCODER_FILENAME,
                SupertonicAssetManager.TTS_CONFIG_FILENAME,
                SupertonicAssetManager.UNICODE_INDEXER_FILENAME,
            )
        `when`(mockAssetManager.list("${SupertonicAssetManager.ASSET_SUBDIR}/${SupertonicAssetManager.ONNX_SUBDIR}"))
            .thenReturn(onnxFiles)

        val voiceStyles = arrayOf("F1.json", "M1.json")
        `when`(mockAssetManager.list("${SupertonicAssetManager.ASSET_SUBDIR}/${SupertonicAssetManager.VOICE_STYLES_SUBDIR}"))
            .thenReturn(voiceStyles)

        for (filename in onnxFiles) {
            val assetPath = "${SupertonicAssetManager.ASSET_SUBDIR}/${SupertonicAssetManager.ONNX_SUBDIR}/$filename"
            `when`(mockAssetManager.list(assetPath)).thenReturn(emptyArray())
            `when`(mockAssetManager.open(assetPath)).thenAnswer {
                ByteArrayInputStream(ByteArray(256) { 7.toByte() })
            }
        }

        for (filename in voiceStyles) {
            val assetPath = "${SupertonicAssetManager.ASSET_SUBDIR}/${SupertonicAssetManager.VOICE_STYLES_SUBDIR}/$filename"
            `when`(mockAssetManager.list(assetPath)).thenReturn(emptyArray())
            `when`(mockAssetManager.open(assetPath)).thenAnswer {
                ByteArrayInputStream(ByteArray(128) { 3.toByte() })
            }
        }

        val unpackResult = SupertonicAssetManager.unpackAssetsIfMissing(mockContext)
        assertTrue(unpackResult)
        assertTrue(SupertonicAssetManager.isModelAvailable(mockContext))

        // Verify unpacked files on local storage
        val targetSupertonicDir = File(internalDir, SupertonicAssetManager.ASSET_SUBDIR)
        val targetOnnxDir = File(targetSupertonicDir, SupertonicAssetManager.ONNX_SUBDIR)
        for (filename in onnxFiles) {
            val file = File(targetOnnxDir, filename)
            assertTrue(file.exists(), "File should exist: $filename")
            assertEquals(256L, file.length())
        }

        val targetStylesDir = File(targetSupertonicDir, SupertonicAssetManager.VOICE_STYLES_SUBDIR)
        for (filename in voiceStyles) {
            val file = File(targetStylesDir, filename)
            assertTrue(file.exists(), "Voice style should exist: $filename")
            assertEquals(128L, file.length())
        }

        // Second unpack call is idempotent and does not corrupt files
        assertTrue(SupertonicAssetManager.unpackAssetsIfMissing(mockContext))
        assertTrue(SupertonicAssetManager.isModelAvailable(mockContext))
    }

    @Test
    fun `unpackAssetsIfMissing handles empty or throwing assetManager gracefully`() {
        `when`(mockAssetManager.list(SupertonicAssetManager.ASSET_SUBDIR))
            .thenThrow(RuntimeException("Simulated asset reading error"))

        val result = SupertonicAssetManager.unpackAssetsIfMissing(mockContext)
        assertFalse(result)
        assertFalse(SupertonicAssetManager.isModelAvailable(mockContext))
    }
}
