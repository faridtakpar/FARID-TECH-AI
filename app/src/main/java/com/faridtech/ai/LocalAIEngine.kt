
package com.faridtech.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

class LocalAIEngine(private val context: Context) {

    companion object {

        private const val MODEL_NAME =
            "Qwen2.5-Coder-1.5B-Q4_K_M.gguf"

        private const val MIN_MODEL_SIZE =
            500_000_000L

        private const val LOAD_SUCCESS =
            "مدل با موفقیت بارگذاری شد."

        private const val GGUF_VERSION_MIN = 2
        private const val GGUF_VERSION_MAX = 3
    }

    private val native = NativeLlama()

    // Prevent simultaneous model loading,
    // generation, import and release operations.
    private val engineMutex = Mutex()

    @Volatile
    private var loadedModelPath: String? = null

    private val modelFile: File
        get() {

            val directory =
                context.getExternalFilesDir(null)
                    ?: context.filesDir

            return File(
                directory,
                "models/$MODEL_NAME"
            )
        }

    private fun hasValidGgufHeader(file: File): Boolean {

        if (
            !file.exists() ||
            !file.isFile ||
            file.length() < MIN_MODEL_SIZE
        ) {
            return false
        }

        return try {

            RandomAccessFile(file, "r").use { input ->

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

                version in
                    GGUF_VERSION_MIN..GGUF_VERSION_MAX
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

        return if (
            modelFile.exists() &&
            modelFile.isFile
        ) {

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

    // Must be called while engineMutex is locked.
    private fun loadModelLocked(): String {

        if (!isModelAvailable()) {

            loadedModelPath = null

            return "فایل مدل معتبر نیست یا حجم آن کافی نیست."
        }

        val path = modelFile.absolutePath

        if (loadedModelPath == path) {
            return LOAD_SUCCESS
        }

        return try {

            val result =
                native.nativeLoadModel(path)

            if (result == LOAD_SUCCESS) {

                loadedModelPath = path

            } else {

                loadedModelPath = null
            }

            result

        } catch (e: Exception) {

            loadedModelPath = null

            "خطا در بارگذاری مدل: ${
                e.message ?: "نامشخص"
            }"
        }
    }

    suspend fun loadModel(): String {

        return withContext(Dispatchers.IO) {

            engineMutex.withLock {
                loadModelLocked()
            }
        }
    }

    suspend fun generate(prompt: String): String {

        return withContext(Dispatchers.IO) {

            if (prompt.isBlank()) {
                return@withContext "لطفاً پیام خود را وارد کنید."
            }

            engineMutex.withLock {

                if (!isModelAvailable()) {

                    return@withLock
                        "فایل مدل معتبر نیست یا نصب نشده است."
                }

                val path = modelFile.absolutePath

                if (loadedModelPath != path) {

                    val loadResult =
                        loadModelLocked()

                    if (loadResult != LOAD_SUCCESS) {

                        return@withLock loadResult
                    }
                }

                try {

                    native.nativeGenerate(
                        prompt,
                        256
                    )

                } catch (e: Exception) {

                    "خطا در اجرای هوش مصنوعی: ${
                        e.message ?: "نامشخص"
                    }"
                }
            }
        }
    }

    suspend fun releaseModel() {

        withContext(Dispatchers.IO) {

            engineMutex.withLock {

                try {

                    native.nativeRelease()

                } finally {

                    loadedModelPath = null
                }
            }
        }
    }

    suspend fun exportModelTo(uri: Uri): Boolean {

        return withContext(Dispatchers.IO) {

            engineMutex.withLock {

                if (!isModelAvailable()) {
                    return@withLock false
                }

                try {

                    val output =
                        context.contentResolver
                            .openOutputStream(uri, "w")
                            ?: return@withLock false

                    val originalLength =
                        modelFile.length()

                    val copiedBytes = output.use { destination ->

                        modelFile.inputStream().use { source ->

                            source.copyTo(destination)
                        }
                    }

                    copiedBytes == originalLength

                } catch (_: Exception) {

                    false
                }
            }
        }
    }

    suspend fun importModelFrom(uri: Uri): Boolean {

        return withContext(Dispatchers.IO) {

            engineMutex.withLock {

                val parent = modelFile.parentFile
                    ?: return@withLock false

                if (
                    !parent.exists() &&
                    !parent.mkdirs()
                ) {
                    return@withLock false
                }

                val temporaryFile = File(
                    parent,
                    "$MODEL_NAME.importing"
                )

                val backupFile = File(
                    parent,
                    "$MODEL_NAME.previous"
                )

                var oldModelMoved = false

                try {

                    // Copy the selected file to a temporary location.
                    val input =
                        context.contentResolver
                            .openInputStream(uri)
                            ?: return@withLock false

                    input.use { source ->

                        temporaryFile.outputStream().use { destination ->

                            source.copyTo(destination)
                        }
                    }

                    // Validate before replacing the current model.
                    if (!hasValidGgufHeader(temporaryFile)) {

                        temporaryFile.delete()

                        return@withLock false
                    }

                    // Never overwrite an existing recovery backup.
                    if (backupFile.exists()) {

                        temporaryFile.delete()

                        return@withLock false
                    }

                    // Release the in-memory model before replacing its file.
                    try {

                        native.nativeRelease()

                    } finally {

                        loadedModelPath = null
                    }

                    val hadOldModel = modelFile.exists()

                    if (hadOldModel) {

                        if (!modelFile.renameTo(backupFile)) {

                            temporaryFile.delete()

                            return@withLock false
                        }

                        oldModelMoved = true
                    }

                    // Install the validated temporary file.
                    if (!temporaryFile.renameTo(modelFile)) {

                        if (oldModelMoved) {
                            backupFile.renameTo(modelFile)
                            oldModelMoved = false
                        }

                        temporaryFile.delete()

                        return@withLock false
                    }

                    // Validate the installed file again.
                    if (!hasValidGgufHeader(modelFile)) {

                        modelFile.delete()

                        if (oldModelMoved) {
                            backupFile.renameTo(modelFile)
                            oldModelMoved = false
                        }

                        return@withLock false
                    }

                    // New model is installed successfully.
                    if (backupFile.exists()) {
                        backupFile.delete()
                    }

                    true

                } catch (_: Exception) {

                    temporaryFile.delete()

                    // Restore the old model if installation failed.
                    if (
                        oldModelMoved &&
                        backupFile.exists() &&
                        !modelFile.exists()
                    ) {

                        backupFile.renameTo(modelFile)
                    }

                    loadedModelPath = null

                    false
                }
            }
        }
    }
}
