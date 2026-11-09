
package com.faridtech.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

class LocalAIEngine(private val context: Context) {

    companion object {
        private const val MODEL_NAME =
            "Qwen2.5-Coder-1.5B-Q4_K_M.gguf"

        private const val MIN_MODEL_SIZE = 500_000_000L

        private const val LOAD_SUCCESS =
            "مدل با موفقیت بارگذاری شد."

        private const val GGUF_VERSION_MIN = 2
        private const val GGUF_VERSION_MAX = 3
    }

    private val native = NativeLlama()

    @Volatile
    private var loadedModelPath: String? = null

    private val modelFile: File
        get() {
            val directory = context.getExternalFilesDir(null)
                ?: context.filesDir

            return File(directory, "models/$MODEL_NAME")
        }

    private fun hasValidGgufHeader(file: File): Boolean {
        if (!file.exists() || !file.isFile) {
            return false
        }

        if (file.length() < MIN_MODEL_SIZE) {
            return false
        }

        return try {
            RandomAccessFile(file, "r").use { input ->

                if (input.length() < 8L) {
                    return@use false
                }

                val magic = ByteArray(4)
                input.readFully(magic)

                if (
                    magic[0] != 'G'.code.toByte() ||
                    magic[1] != 'G'.code.toByte() ||
                    magic[2] != 'U'.code.toByte() ||
                    magic[3] != 'F'.code.toByte()
                ) {
                    return@use false
                }

                val version = Integer.reverseBytes(
                    input.readInt()
                )

                version in GGUF_VERSION_MIN..GGUF_VERSION_MAX
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isModelAvailable(): Boolean {
        return hasValidGgufHeader(modelFile)
    }

    suspend fun checkModel(): Boolean {
        return withContext(Dispatchers.IO) {
            isModelAvailable()
        }
    }

    fun getModelPath(): String? {
        return if (isModelAvailable()) {
            modelFile.absolutePath
        } else {
            null
        }
    }

    fun getModelDetails(): String {
        return if (modelFile.exists() && modelFile.isFile) {
            """
            مسیر فایل:
            ${modelFile.absolutePath}

            حجم فایل:
            ${modelFile.length() / (1024.0 * 1024.0)} MB

            وضعیت سربرگ GGUF:
            ${if (hasValidGgufHeader(modelFile)) "معتبر" else "نامعتبر"}
            """.trimIndent()
        } else {
            "فایل مدل در مسیر مورد انتظار پیدا نشد."
        }
    }

    suspend fun loadModel(): String {
        return withContext(Dispatchers.IO) {

            if (!isModelAvailable()) {
                return@withContext "فایل مدل معتبر نیست یا حجم آن کافی نیست."
            }

            val path = modelFile.absolutePath

            if (loadedModelPath == path) {
                return@withContext LOAD_SUCCESS
            }

            try {
                val result = native.nativeLoadModel(path)

                if (result == LOAD_SUCCESS) {
                    loadedModelPath = path
                }

                result

            } catch (e: Exception) {
                loadedModelPath = null
                "خطا در بارگذاری مدل: ${e.message ?: "نامشخص"}"
            }
        }
    }

    suspend fun generate(prompt: String): String {
        return withContext(Dispatchers.IO) {

            if (prompt.isBlank()) {
                return@withContext "لطفاً پیام خود را وارد کنید."
            }

            if (!isModelAvailable()) {
                return@withContext "فایل مدل معتبر نیست یا نصب نشده است."
            }

            try {
                val path = modelFile.absolutePath

                if (loadedModelPath != path) {

                    val loadResult =
                        native.nativeLoadModel(path)

                    if (loadResult != LOAD_SUCCESS) {
                        loadedModelPath = null
                        return@withContext loadResult
                    }

                    loadedModelPath = path
                }

                native.nativeGenerate(prompt, 256)

            } catch (e: Exception) {
                "خطا در اجرای هوش مصنوعی: ${e.message ?: "نامشخص"}"
            }
        }
    }

    suspend fun releaseModel() {
        withContext(Dispatchers.IO) {
            try {
                native.nativeRelease()
            } finally {
                loadedModelPath = null
            }
        }
    }

    suspend fun exportModelTo(uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {

            if (!isModelAvailable()) {
                return@withContext false
            }

            try {
                val output = context.contentResolver
                    .openOutputStream(uri, "w")
                    ?: return@withContext false

                val copiedBytes = output.use { destination ->
                    modelFile.inputStream().use { source ->
                        source.copyTo(destination)
                    }
                }

                copiedBytes == modelFile.length()

            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun importModelFrom(uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {

            val parent = modelFile.parentFile
                ?: return@withContext false

            if (!parent.exists() && !parent.mkdirs()) {
                return@withContext false
            }

            val temporaryFile =
                File(parent, "$MODEL_NAME.importing")

            val backupFile =
                File(parent, "$MODEL_NAME.previous")

            try {
                val input = context.contentResolver
                    .openInputStream(uri)
                    ?: return@withContext false

                input.use { source ->
                    temporaryFile.outputStream().use { destination ->
                        source.copyTo(destination)
                    }
                }

                // Validate the new file before touching the current model.
                if (!hasValidGgufHeader(temporaryFile)) {
                    temporaryFile.delete()
                    return@withContext false
                }

                // Preserve an existing backup rather than overwriting it.
                if (backupFile.exists()) {
                    temporaryFile.delete()
                    return@withContext false
                }

                val hadOldModel = modelFile.exists()

                if (hadOldModel && !modelFile.renameTo(backupFile)) {
                    temporaryFile.delete()
                    return@withContext false
                }

                if (!temporaryFile.renameTo(modelFile)) {

                    if (hadOldModel) {
                        backupFile.renameTo(modelFile)
                    }

                    temporaryFile.delete()
                    return@withContext false
                }

                // The new file is installed; release the old in-memory model.
                try {
                    native.nativeRelease()
                } finally {
                    loadedModelPath = null
                }

                if (backupFile.exists()) {
                    backupFile.delete()
                }

                true

            } catch (_: Exception) {

                temporaryFile.delete()

                if (backupFile.exists() && !modelFile.exists()) {
                    backupFile.renameTo(modelFile)
                }

                false
            }
        }
    }
}
