
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
    }

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

    suspend fun exportModelTo(uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            if (!isModelAvailable()) {
                return@withContext false
            }

            try {
                val output = context.contentResolver
                    .openOutputStream(uri, "w")
                    ?: return@withContext false

                output.use { destination ->
                    modelFile.inputStream().use { source ->
                        source.copyTo(destination)
                    }
                }

                true
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

            val temporaryFile = File(parent, "$MODEL_NAME.importing")
            val backupFile = File(parent, "$MODEL_NAME.previous")

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
                    backupFile.delete()
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

                backupFile.delete()
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

    suspend fun generate(prompt: String): String {
        return withContext(Dispatchers.IO) {
            when {
                prompt.isBlank() ->
                    "لطفاً پیام خود را وارد کنید."

                !isModelAvailable() ->
                    "مدل هوش مصنوعی هنوز نصب نشده است."

                else ->
                    "فایل مدل شناسایی شد؛ موتور اجرای هوش مصنوعی هنوز متصل نشده است."
            }
        }
    }
}
