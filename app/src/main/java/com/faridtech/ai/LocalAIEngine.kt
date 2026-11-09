
package com.faridtech.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalAIEngine(private val context: Context) {

    companion object {
        private const val MODEL_NAME =
            "Qwen2.5-Coder-1.5B-Q4_K_M.gguf"
    }

    private val modelFile: File
        get() = File(
            context.getExternalFilesDir(null),
            "models/$MODEL_NAME"
        )

    fun isModelAvailable(): Boolean {
        return modelFile.exists() &&
            modelFile.isFile &&
            modelFile.length() > 500_000_000L
    }

    suspend fun checkModel(): Boolean =
        withContext(Dispatchers.IO) {
            isModelAvailable()
        }

    fun getModelPath(): String? {
        return if (isModelAvailable()) {
            modelFile.absolutePath
        } else {
            null
        }
    }

    suspend fun generate(prompt: String): String =
        withContext(Dispatchers.IO) {
            if (prompt.isBlank()) {
                return@withContext "لطفاً پیام خود را وارد کنید."
            }

            if (!isModelAvailable()) {
                return@withContext
                    "مدل هوش مصنوعی هنوز نصب نشده است."
            }

            "فایل مدل شناسایی شد؛ موتور اجرای هوش مصنوعی هنوز متصل نشده است."
        }
}
