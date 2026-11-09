
package com.faridtech.ai

import android.content.Context
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
        get() = File(
            context.getExternalFilesDir(null),
            "models/$MODEL_NAME"
        )

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

    suspend fun generate(prompt: String): String {
        return withContext(Dispatchers.IO) {

            if (prompt.isBlank()) {
                "لطفاً پیام خود را وارد کنید."
            } else if (!isModelAvailable()) {
                "مدل هوش مصنوعی هنوز نصب نشده است."
            } else {
                "فایل مدل شناسایی شد؛ موتور اجرای هوش مصنوعی هنوز متصل نشده است."
            }
        }
    }
}
