
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

        // Exact size of the selected Q4_K_M model.
        private const val EXPECTED_MODEL_SIZE =
            1_117_320_768L

        private const val MIN_MODEL_SIZE =
            500_000_000L

        private const val GGUF_VERSION_MIN = 2
        private const val GGUF_VERSION_MAX = 3

        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 60000
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

        if (
            !file.exists() ||
            !file.isFile ||
            file.length() < 8L
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

    private fun isCompleteModel(file: File): Boolean {

        return file.exists() &&
            file.isFile &&
            file.length() == EXPECTED_MODEL_SIZE &&
            file.length() >= MIN_MODEL_SIZE &&
            hasValidGgufHeader(file)
    }

    suspend fun download(
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {

        if (
            !modelDirectory.exists() &&
            !modelDirectory.mkdirs()
        ) {
            throw IllegalStateException(
                "ساخت پوشه مدل ناموفق بود."
            )
        }

        // Keep an already complete model.
        if (isCompleteModel(finalFile)) {
            return@withContext finalFile
        }

        // Never overwrite an existing recovery backup.
        if (backupFile.exists()) {
            throw IllegalStateException(
                "نسخه پشتیبان قبلی وجود دارد؛ ابتدا وضعیت آن را بررسی کن."
            )
        }

        // Remove invalid or oversized partial downloads.
        if (partialFile.exists()) {

            if (
                !hasValidGgufHeader(partialFile) ||
                partialFile.length() >= EXPECTED_MODEL_SIZE
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

                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
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

                val totalSize =
                    match.groupValues[3].toLong()

                val receivedRangeSize =
                    rangeEnd - rangeStart + 1L

                val responseBodySize =
                    connection.contentLengthLong

                if (
                    rangeStart != existingSize ||
                    rangeEnd < rangeStart ||
                    totalSize != EXPECTED_MODEL_SIZE ||
                    rangeEnd != totalSize - 1L ||
                    receivedRangeSize <= 0L ||
                    (
                        responseBodySize >= 0L &&
                        responseBodySize != receivedRangeSize
                    )
                ) {
                    throw IllegalStateException(
                        "محدوده دریافتی سرور با فایل مدل سازگار نیست."
                    )
                }

            } else {

                val responseSize =
                    connection.contentLengthLong

                if (
                    responseSize >= 0L &&
                    responseSize != EXPECTED_MODEL_SIZE
                ) {
                    throw IllegalStateException(
                        "اندازه اعلام‌شده فایل با نسخه اصلی مدل مطابقت ندارد."
                    )
                }
            }

            onProgress(
                startSize,
                EXPECTED_MODEL_SIZE
            )

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

                        if (
                            downloaded + count >
                            EXPECTED_MODEL_SIZE
                        ) {
                            partialFile.delete()

                            throw IllegalStateException(
                                "حجم دریافتی بیشتر از اندازه فایل اصلی است."
                            )
                        }

                        output.write(
                            buffer,
                            0,
                            count
                        )

                        downloaded += count

                        onProgress(
                            downloaded,
                            EXPECTED_MODEL_SIZE
                        )
                    }

                    output.fd.sync()
                }
            }

            // Verify exact file size.
            if (
                partialFile.length() != EXPECTED_MODEL_SIZE
            ) {
                throw IllegalStateException(
                    "دانلود ناقص است؛ فایل موقت برای ادامه دانلود حفظ شد."
                )
            }

            if (partialFile.length() < MIN_MODEL_SIZE) {

                partialFile.delete()

                throw IllegalStateException(
                    "حجم فایل مدل کمتر از حد مجاز است."
                )
            }

            // Validate GGUF header and version.
            if (!hasValidGgufHeader(partialFile)) {

                partialFile.delete()

                throw IllegalStateException(
                    "فایل دریافتی قالب GGUF معتبر ندارد."
                )
            }

            // Preserve the old file until installation succeeds.
            val hadOldFile = finalFile.exists()

            if (
                hadOldFile &&
                !finalFile.renameTo(backupFile)
            ) {
                throw IllegalStateException(
                    "نگهداری نسخه قبلی مدل ناموفق بود."
                )
            }

            // Install the downloaded model.
            if (!partialFile.renameTo(finalFile)) {

                if (hadOldFile) {
                    backupFile.renameTo(finalFile)
                }

                throw IllegalStateException(
                    "انتقال فایل مدل ناموفق بود."
                )
            }

            // Validate the installed file.
            if (!isCompleteModel(finalFile)) {

                finalFile.delete()

                if (hadOldFile) {
                    backupFile.renameTo(finalFile)
                }

                throw IllegalStateException(
                    "اعتبارسنجی فایل نهایی ناموفق بود."
                )
            }

            // Remove the old backup only after successful installation.
            if (backupFile.exists()) {
                backupFile.delete()
            }

            onProgress(
                EXPECTED_MODEL_SIZE,
                EXPECTED_MODEL_SIZE
            )

            finalFile

        } finally {

            connection?.disconnect()
        }
    }
}
