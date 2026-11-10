
package com.faridtech.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

class LocalAIEngine(context: Context) {

    companion object {
        private const val MODEL_NAME =
            "Qwen2.5-Coder-1.5B-Q4_K_M.gguf"

        private const val MIN_MODEL_SIZE = 500_000_000L
        private const val GGUF_VERSION_MIN = 2
        private const val GGUF_VERSION_MAX = 3
        private const val MAX_GENERATION_TOKENS = 256

        private const val LOAD_SUCCESS =
            "مدل با موفقیت بارگذاری شد."
    }

    private val appContext = context.applicationContext
    private val native = NativeLlama()

    private val engineMutex =
        ModelStorageCoordinator.engineMutex

    private val modelFile: File
        get() {
            val directory =
                appContext.getExternalFilesDir(null)
                    ?: appContext.filesDir

            return File(directory, "models/$MODEL_NAME")
        }

    private val temporaryFile: File
        get() = File(
            modelFile.parentFile,
            "$MODEL_NAME.importing"
        )

    private val backupFile: File
        get() = File(
            modelFile.parentFile,
            "$MODEL_NAME.previous"
        )

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

                val version =
                    Integer.reverseBytes(input.readInt())

                version in GGUF_VERSION_MIN..GGUF_VERSION_MAX
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isModelAvailable(): Boolean =
        hasValidGgufHeader(modelFile)

    suspend fun checkModel(): Boolean =
        withContext(Dispatchers.IO) {
            isModelAvailable()
        }

    fun getModelPath(): String? =
        if (isModelAvailable()) {
            modelFile.absolutePath
        } else {
            null
        }

    suspend fun getModelDetails(): String =
        withContext(Dispatchers.IO) {
            engineMutex.withLock {
                val exists =
                    modelFile.exists() && modelFile.isFile

                val valid =
                    exists && hasValidGgufHeader(modelFile)

                val sizeMb = if (exists) {
                    String.format(
                        Locale.US,
                        "%.2f",
                        modelFile.length() / (1024.0 * 1024.0)
                    )
                } else {
                    "0"
                }

                val isLoaded =
                    ModelStorageCoordinator.loadedModelPath ==
                        modelFile.absolutePath

                """
                نام مدل:
                $MODEL_NAME

                مسیر فایل:
                ${modelFile.absolutePath}

                حجم فایل:
                $sizeMb MB

                وضعیت فایل:
                ${if (!exists) "پیدا نشد" else if (valid) "معتبر" else "نامعتبر"}

                وضعیت بارگذاری:
                ${if (isLoaded) "بارگذاری شده" else "بارگذاری نشده"}

                نسخه پشتیبان:
                ${if (backupFile.exists()) "موجود" else "وجود ندارد"}

                فایل موقت:
                ${if (temporaryFile.exists()) "موجود" else "وجود ندارد"}
                """.trimIndent()
            }
        }

    // Must only be called while engineMutex is held.
    private fun loadModelLocked(): String {

        if (!isModelAvailable()) {

            // وضعیت Native و Kotlin هر دو پاک می‌شوند.
            ModelStorageCoordinator.releaseLoadedModelLocked()

            return "فایل مدل معتبر نیست یا حجم آن کافی نیست."
        }

        val path = modelFile.absolutePath

        if (
            ModelStorageCoordinator.loadedModelPath == path
        ) {
            return LOAD_SUCCESS
        }

        return try {

            ModelStorageCoordinator.releaseLoadedModelLocked()

            val result = native.nativeLoadModel(path)

            if (result == LOAD_SUCCESS) {
                ModelStorageCoordinator.loadedModelPath = path
            } else {
                ModelStorageCoordinator.loadedModelPath = null
            }

            result

        } catch (e: Exception) {

            ModelStorageCoordinator.loadedModelPath = null

            "خطا در بارگذاری مدل: ${e.message ?: "نامشخص"}"
        }
    }

    suspend fun loadModel(): String =
        withContext(Dispatchers.IO) {
            engineMutex.withLock {
                loadModelLocked()
            }
        }

    suspend fun generate(prompt: String): String =
        withContext(Dispatchers.IO) {

            if (prompt.isBlank()) {
                return@withContext "لطفاً پیام خود را وارد کنید."
            }

            engineMutex.withLock {

                if (!isModelAvailable()) {

                    ModelStorageCoordinator.releaseLoadedModelLocked()

                    return@withLock "فایل مدل معتبر نیست یا نصب نشده است."
                }

                if (
                    ModelStorageCoordinator.loadedModelPath !=
                    modelFile.absolutePath
                ) {
                    val result = loadModelLocked()

                    if (result != LOAD_SUCCESS) {
                        return@withLock result
                    }
                }

                try {

                    native.nativeGenerate(
                        prompt,
                        MAX_GENERATION_TOKENS
                    )

                } catch (e: Exception) {

                    "خطا در اجرای هوش مصنوعی: ${e.message ?: "نامشخص"}"
                }
            }
        }

    suspend fun releaseModel() {
        withContext(Dispatchers.IO) {
            engineMutex.withLock {
                ModelStorageCoordinator.releaseLoadedModelLocked()
            }
        }
    }

    suspend fun exportModelTo(uri: Uri): Boolean =
        withContext(Dispatchers.IO) {

            engineMutex.withLock {

                if (!isModelAvailable()) {
                    return@withLock false
                }

                try {

                    val originalLength = modelFile.length()

                    val output = appContext.contentResolver
                        .openOutputStream(uri, "w")
                        ?: return@withLock false

                    val copiedBytes = output.use { destination ->

                        modelFile.inputStream().use { source ->
                            source.copyTo(destination)
                        }
                    }

                    copiedBytes == originalLength &&
                        modelFile.length() == originalLength &&
                        hasValidGgufHeader(modelFile)

                } catch (_: Exception) {
                    false
                }
            }
        }

    suspend fun importModelFrom(uri: Uri): Boolean =
        withContext(Dispatchers.IO) {

            engineMutex.withLock {

                val parent = modelFile.parentFile
                    ?: return@withLock false

                if (
                    !parent.exists() &&
                    !parent.mkdirs()
                ) {
                    return@withLock false
                }

                if (backupFile.exists()) {
                    return@withLock false
                }

                var oldModelMoved = false
                var newModelInstalled = false

                try {

                    if (
                        temporaryFile.exists() &&
                        !temporaryFile.delete()
                    ) {
                        return@withLock false
                    }

                    val input = appContext.contentResolver
                        .openInputStream(uri)
                        ?: return@withLock false

                    input.use { source ->

                        temporaryFile.outputStream().use { destination ->
                            source.copyTo(destination)
                            destination.fd.sync()
                        }
                    }

                    if (!hasValidGgufHeader(temporaryFile)) {
                        temporaryFile.delete()
                        return@withLock false
                    }

                    val requiredSpace =
                        temporaryFile.length() +
                            if (modelFile.exists()) {
                                modelFile.length()
                            } else {
                                0L
                            }

                    if (parent.usableSpace < requiredSpace) {
                        temporaryFile.delete()
                        return@withLock false
                    }

                    // Release native model before replacing its file.
                    ModelStorageCoordinator.releaseLoadedModelLocked()

                    if (modelFile.exists()) {

                        if (!modelFile.renameTo(backupFile)) {
                            temporaryFile.delete()
                            return@withLock false
                        }

                        oldModelMoved = true
                    }

                    if (!temporaryFile.renameTo(modelFile)) {

                        restorePreviousModel(oldModelMoved)

                        return@withLock false
                    }

                    newModelInstalled = true

                    if (!hasValidGgufHeader(modelFile)) {

                        modelFile.delete()

                        restorePreviousModel(oldModelMoved)

                        return@withLock false
                    }

                    if (
                        oldModelMoved &&
                        !backupFile.delete()
                    ) {

                        modelFile.delete()

                        restorePreviousModel(true)

                        return@withLock false
                    }

                    ModelStorageCoordinator.loadedModelPath = null

                    true

                } catch (_: Exception) {

                    if (temporaryFile.exists()) {
                        temporaryFile.delete()
                    }

                    if (
                        newModelInstalled &&
                        modelFile.exists()
                    ) {
                        modelFile.delete()
                    }

                    restorePreviousModel(oldModelMoved)

                    ModelStorageCoordinator.loadedModelPath = null

                    false
                }
            }
        }

    // Must only be called while engineMutex is held.
    private fun restorePreviousModel(
        oldModelMoved: Boolean
    ): Boolean {

        if (!oldModelMoved || !backupFile.exists()) {
            return !oldModelMoved
        }

        if (
            modelFile.exists() &&
            !modelFile.delete()
        ) {
            return false
        }

        return backupFile.renameTo(modelFile)
    }
}
