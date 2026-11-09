
package com.faridtech.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalAIEngine(private val context: Context) {

    companion object {
        private const val MODEL_NAME =
            "Qwen2.5-Coder-1.5B-Q4_K_M.gguf"

        private const val MIN_MODEL_SIZE = 500_000_000L

        private const val LOAD_SUCCESS =
            "مدل با موفقیت بارگذاری شد."
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

    fun isModelAvailable(): Boolean {
        return modelFile.exists() &&
            modelFile.isFile &&
            modelFile.length() >= MIN_MODEL_SIZE
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
            """.trimIndent()
        } else {
            "فایل مدل در مسیر مورد انتظار پیدا نشد."
        }
    }

    suspend fun loadModel(): String {
        return withContext(Dispatchers.IO) {

            if (!isModelAvailable()) {
                return@withContext "فایل مدل پیدا نشد یا حجم آن کافی نیست."
            }

            val path = modelFile.absolutePath

            if (loadedModelPath == path) {
                return@withContext LOAD_SUCCESS
            }

            try {
                native.nativeRelease()
                loadedModelPath = null

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
                return@withContext "مدل هوش مصنوعی هنوز نصب نشده است."
            }

            try {
                val path = modelFile.absolutePath

                if (loadedModelPath != path) {
                    native.nativeRelease()
                    loadedModelPath = null

                    val loadResult =
                        native.nativeLoadModel(path)

                    if (loadResult != LOAD_SUCCESS) {
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

                if (!temporaryFile.exists() ||
                    temporaryFile.length() < MIN_MODEL_SIZE
                ) {
                    temporaryFile.delete()
                    return@withContext false
                }

                if (backupFile.exists()) {
                    temporaryFile.delete()
                    return@withContext false
                }

                releaseModel()

                val hadOldModel = modelFile.exists()

                if (hadOldModel &&
                    !modelFile.renameTo(backupFile)
                ) {
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
