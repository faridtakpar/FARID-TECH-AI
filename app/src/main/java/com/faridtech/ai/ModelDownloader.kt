package com.faridtech.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloader(context: Context) {

    companion object {
        private const val MODEL_NAME =
            "Qwen2.5-Coder-1.5B-Q4_K_M.gguf"

        private const val MODEL_URL =
            "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"

        private const val MIN_MODEL_SIZE = 500_000_000L
    }

    private val modelDirectory = File(
        context.getExternalFilesDir(null),
        "models"
    )

    private val finalFile = File(modelDirectory, MODEL_NAME)
    private val partialFile = File(modelDirectory, "$MODEL_NAME.part")

    suspend fun download(
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {

        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
            throw IllegalStateException("ساخت پوشه مدل ناموفق بود.")
        }

        if (finalFile.exists() && finalFile.length() >= MIN_MODEL_SIZE) {
            return@withContext finalFile
        }

        if (finalFile.exists()) finalFile.delete()

        val existingSize = if (partialFile.exists()) {
            partialFile.length()
        } else {
            0L
        }

        var connection: HttpURLConnection? = null

        try {
            connection = (URL(MODEL_URL).openConnection()
                as HttpURLConnection).apply {
                connectTimeout = 30000
                readTimeout = 60000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FARID-TECH-AI/1.0")

                if (existingSize > 0) {
                    setRequestProperty("Range", "bytes=$existingSize-")
                }
            }

            val response = connection.responseCode

            if (response != HttpURLConnection.HTTP_OK &&
                response != HttpURLConnection.HTTP_PARTIAL
            ) {
                throw IllegalStateException("خطای دانلود: HTTP $response")
            }

            val append = response == HttpURLConnection.HTTP_PARTIAL
            val startSize = if (append) existingSize else 0L

            val contentRange = connection.getHeaderField("Content-Range")

            if (append && (contentRange == null ||
                    !contentRange.startsWith("bytes $existingSize-"))
            ) {
                throw IllegalStateException("پاسخ ادامه دانلود معتبر نیست.")
            }

            val totalSize = if (contentRange != null) {
                contentRange.substringAfterLast("/")
                    .toLongOrNull() ?: -1L
            } else {
                connection.contentLengthLong.let {
                    if (it > 0) it + startSize else -1L
                }
            }

            connection.inputStream.use { input ->
                RandomAccessFile(partialFile, "rw").use { output ->
                    if (!append) output.setLength(0)

                    output.seek(startSize)

                    val buffer = ByteArray(64 * 1024)
                    var downloaded = startSize

                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break

                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, totalSize)
                    }
                }
            }

            if (totalSize > 0 && partialFile.length() != totalSize) {
                throw IllegalStateException("دانلود ناقص است؛ فایل موقت حفظ شد.")
            }

            if (partialFile.length() < MIN_MODEL_SIZE) {
                throw IllegalStateException("حجم فایل مدل معتبر نیست.")
            }

            if (!partialFile.renameTo(finalFile)) {
                throw IllegalStateException("انتقال فایل مدل ناموفق بود.")
            }

            finalFile
        } finally {
            connection?.disconnect()
        }
    }
}
