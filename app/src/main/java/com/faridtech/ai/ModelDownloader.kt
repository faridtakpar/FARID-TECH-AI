
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

        private const val GGUF_VERSION_MIN = 2
        private const val GGUF_VERSION_MAX = 3
    }

    private val modelDirectory: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "models"
    )

    private val finalFile =
        File(modelDirectory, MODEL_NAME)

    private val partialFile =
        File(modelDirectory, "$MODEL_NAME.part")

    private val backupFile =
        File(modelDirectory, "$MODEL_NAME.previous")

    private fun hasValidGgufHeader(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 8L) {
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

                version in GGUF_VERSION_MIN..GGUF_VERSION_MAX
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun download(
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {

        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
            throw IllegalStateException(
                "ساخت پوشه مدل ناموفق بود."
            )
        }

        // Do not replace an existing valid model.
        if (hasValidGgufHeader(finalFile) &&
            finalFile.length() >= MIN_MODEL_SIZE
        ) {
            return@withContext finalFile
        }

        if (backupFile.exists()) {
            throw IllegalStateException(
                "نسخه پشتیبان قبلی وجود دارد؛ ابتدا وضعیت آن را بررسی کن."
            )
        }

        // Validate an existing partial file before resuming.
        if (partialFile.exists()) {
            if (
                partialFile.length() < 8L ||
                !hasValidGgufHeader(partialFile)
            ) {
                partialFile.delete()
            }
        }

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

                setRequestProperty(
                    "User-Agent",
                    "FARID-TECH-AI/1.0"
                )

                setRequestProperty(
                    "Accept-Encoding",
                    "identity"
                )

                if (existingSize > 0L) {
                    setRequestProperty(
                        "Range",
                        "bytes=$existingSize-"
                    )
                }
            }

            val response = connection.responseCode

            if (
                response != HttpURLConnection.HTTP_OK &&
                response != HttpURLConnection.HTTP_PARTIAL
            ) {
                throw IllegalStateException(
                    "خطای دانلود از سرور: HTTP $response"
                )
            }

            val append =
                response == HttpURLConnection.HTTP_PARTIAL

            val startSize = if (append) {
                existingSize
            } else {
                0L
            }

            var totalSize = -1L

            if (append) {

                val contentRange =
                    connection.getHeaderField("Content-Range")

                val match = Regex(
                    "^bytes (\\d+)-(\\d+)/(\\d+)$"
                ).matchEntire(contentRange ?: "")

                if (match == null) {
                    throw IllegalStateException(
                        "پاسخ ادامه دانلود معتبر نیست."
                    )
                }

                val rangeStart =
                    match.groupValues[1].toLong()

                val rangeEnd =
                    match.groupValues[2].toLong()

                totalSize =
                    match.groupValues[3].toLong()

                if (
                    rangeStart != existingSize ||
                    rangeEnd < rangeStart ||
                    totalSize <= rangeEnd
                ) {
                    throw IllegalStateException(
                        "محدوده دریافتی سرور با فایل قبلی سازگار نیست."
                    )
                }

            } else {

                totalSize = connection.contentLengthLong
            }

            val expectedFinalSize = totalSize

            connection.inputStream.use { input ->

                RandomAccessFile(
                    partialFile,
                    "rw"
                ).use { output ->

                    if (!append) {
                        output.setLength(0L)
                    }

                    output.seek(startSize)

                    val buffer = ByteArray(64 * 1024)

                    var downloaded = startSize

                    while (true) {

                        val count = input.read(buffer)

                        if (count == -1) {
                            break
                        }

                        output.write(buffer, 0, count)

                        downloaded += count

                        onProgress(
                            downloaded,
                            expectedFinalSize
                        )
                    }

                    output.fd.sync()
                }
            }

            // Verify final size when the server provides it.
            if (
                expectedFinalSize > 0L &&
                partialFile.length() != expectedFinalSize
            ) {
                throw IllegalStateException(
                    "دانلود ناقص است؛ فایل موقت برای ادامه دانلود حفظ شد."
                )
            }

            if (partialFile.length() < MIN_MODEL_SIZE) {
                throw IllegalStateException(
                    "حجم فایل مدل کمتر از حد مجاز است."
                )
            }

            // Check GGUF magic and version before installation.
            if (!hasValidGgufHeader(partialFile)) {
                partialFile.delete()

                throw IllegalStateException(
                    "فایل دریافتی قالب GGUF معتبر ندارد."
                )
            }

            // Preserve any existing file until the new one is ready.
            val hadOldFile = finalFile.exists()

            if (hadOldFile && !finalFile.renameTo(backupFile)) {
                throw IllegalStateException(
                    "نگهداری نسخه قبلی مدل ناموفق بود."
                )
            }

            if (!partialFile.renameTo(finalFile)) {

                if (hadOldFile) {
                    backupFile.renameTo(finalFile)
                }

                throw IllegalStateException(
                    "انتقال فایل مدل ناموفق بود."
                )
            }

            // Verify the installed file once more.
            if (!hasValidGgufHeader(finalFile)) {

                finalFile.delete()

                if (hadOldFile) {
                    backupFile.renameTo(finalFile)
                }

                throw IllegalStateException(
                    "اعتبارسنجی فایل نهایی ناموفق بود."
                )
            }

            if (backupFile.exists()) {
                backupFile.delete()
            }

            onProgress(
                finalFile.length(),
                finalFile.length()
            )

            finalFile

        } finally {
            connection?.disconnect()
        }
    }
}
