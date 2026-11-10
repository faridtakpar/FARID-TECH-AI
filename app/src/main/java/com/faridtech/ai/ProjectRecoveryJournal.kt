
package com.faridtech.ai

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal enum class RecoveryOperationType {
    APPLY_CHANGE,
    RESTORE_HISTORICAL,
    RESTORE_BACKUP
}

internal enum class RecoveryOperationState {
    PREPARED,
    COMMITTED
}

internal data class RecoveryJournalEntry(
    val id: String,
    val projectPath: String,
    val filePath: String,
    val backupPath: String,
    val beforeHash: String,
    val targetHash: String,
    val operationType: RecoveryOperationType,
    val state: RecoveryOperationState,
    val createdAt: Long
)

internal sealed class RecoveryCheckResult {

    data class Resolved(
        val operationId: String,
        val message: String
    ) : RecoveryCheckResult()

    data class Pending(
        val operationId: String,
        val message: String
    ) : RecoveryCheckResult()

    data class Invalid(
        val operationId: String,
        val message: String
    ) : RecoveryCheckResult()
}

internal class ProjectRecoveryJournal(context: Context) {

    companion object {
        private const val JOURNAL_VERSION = 1
        private const val MAX_JOURNAL_SIZE = 16 * 1024
        private const val MAX_TARGET_FILE_SIZE = 256 * 1024

        private const val JOURNAL_DIRECTORY = "project_recovery"

        private val HASH_REGEX =
            Regex("^[a-fA-F0-9]{64}$")

        private val ID_REGEX =
            Regex("^[a-fA-F0-9-]{36}$")
    }

    private val appContext = context.applicationContext

    private val journalDirectory =
        File(appContext.filesDir, JOURNAL_DIRECTORY)

    private fun journalFile(id: String): File? {
        if (!ID_REGEX.matches(id)) {
            return null
        }

        return try {
            val root = journalDirectory.canonicalFile
            val target = File(root, "$id.json").canonicalFile

            if (target.parentFile == root) {
                target
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidHash(hash: String): Boolean {
        return HASH_REGEX.matches(hash)
    }

    private fun isInsideDirectory(
        file: File,
        directory: File
    ): Boolean {
        val rootPath = directory.canonicalPath
            .trimEnd(File.separatorChar)

        val filePath = file.canonicalPath

        return filePath.startsWith(
            rootPath + File.separator
        )
    }

    private fun isValidEntry(
        entry: RecoveryJournalEntry
    ): Boolean {

        if (!ID_REGEX.matches(entry.id)) return false

        if (entry.projectPath.isBlank()) return false
        if (entry.filePath.isBlank()) return false
        if (entry.backupPath.isBlank()) return false

        if (!isValidHash(entry.beforeHash)) return false
        if (!isValidHash(entry.targetHash)) return false

        if (entry.createdAt <= 0L) return false

        return true
    }

    private fun serialize(
        entry: RecoveryJournalEntry
    ): ByteArray {

        val json = JSONObject().apply {
            put("version", JOURNAL_VERSION)
            put("id", entry.id)
            put("projectPath", entry.projectPath)
            put("filePath", entry.filePath)
            put("backupPath", entry.backupPath)
            put("beforeHash", entry.beforeHash)
            put("targetHash", entry.targetHash)
            put("operationType", entry.operationType.name)
            put("state", entry.state.name)
            put("createdAt", entry.createdAt)
        }

        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun deserialize(
        bytes: ByteArray
    ): RecoveryJournalEntry {

        if (bytes.isEmpty() || bytes.size > MAX_JOURNAL_SIZE) {
            throw IOException("اندازه گزارش بازیابی معتبر نیست.")
        }

        try {
            val json = JSONObject(
                bytes.toString(Charsets.UTF_8)
            )

            if (json.optInt("version", -1) != JOURNAL_VERSION) {
                throw IOException("نسخه گزارش بازیابی پشتیبانی نمی‌شود.")
            }

            val operationType = try {
                RecoveryOperationType.valueOf(
                    json.getString("operationType")
                )
            } catch (_: Exception) {
                throw IOException("نوع عملیات بازیابی معتبر نیست.")
            }

            val state = try {
                RecoveryOperationState.valueOf(
                    json.getString("state")
                )
            } catch (_: Exception) {
                throw IOException("وضعیت عملیات بازیابی معتبر نیست.")
            }

            val entry = RecoveryJournalEntry(
                id = json.getString("id"),
                projectPath = json.getString("projectPath"),
                filePath = json.getString("filePath"),
                backupPath = json.getString("backupPath"),
                beforeHash = json.getString("beforeHash"),
                targetHash = json.getString("targetHash"),
                operationType = operationType,
                state = state,
                createdAt = json.getLong("createdAt")
            )

            if (!isValidEntry(entry)) {
                throw IOException("اطلاعات گزارش بازیابی معتبر نیست.")
            }

            return entry

        } catch (exception: IOException) {
            throw exception
        } catch (exception: Exception) {
            throw IOException(
                "خواندن اطلاعات گزارش بازیابی ناموفق بود.",
                exception
            )
        }
    }

    private fun readEntry(file: File): RecoveryJournalEntry {

        if (!file.exists() || !file.isFile) {
            throw IOException("فایل گزارش بازیابی پیدا نشد.")
        }

        val canonicalDirectory = journalDirectory.canonicalFile

        if (!isInsideDirectory(file, canonicalDirectory)) {
            throw IOException("مسیر گزارش بازیابی خارج از پوشه مجاز است.")
        }

        val atomicFile = AtomicFile(file)

        val bytes = atomicFile.openRead().use { input ->

            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)

            var total = 0

            while (true) {
                val count = input.read(buffer)

                if (count == -1) break

                total += count

                if (total > MAX_JOURNAL_SIZE) {
                    throw IOException(
                        "حجم گزارش بازیابی از حد مجاز بیشتر است."
                    )
                }

                output.write(buffer, 0, count)
            }

            output.toByteArray()
        }

        return deserialize(bytes)
    }

    private fun writeEntry(
        file: File,
        entry: RecoveryJournalEntry
    ): Boolean {

        if (!isValidEntry(entry)) return false

        return try {
            if (!journalDirectory.exists() &&
                !journalDirectory.mkdirs()
            ) {
                return false
            }

            val canonicalDirectory = journalDirectory.canonicalFile

            if (!isInsideDirectory(file, canonicalDirectory)) {
                return false
            }

            val bytes = serialize(entry)

            if (bytes.size > MAX_JOURNAL_SIZE) {
                return false
            }

            val atomicFile = AtomicFile(file)
            val output = atomicFile.startWrite()

            try {
                output.write(bytes)
                atomicFile.finishWrite(output)
                true
            } catch (exception: Exception) {
                try {
                    atomicFile.failWrite(output)
                } catch (_: Exception) {
                    // خطای اصلی حفظ می‌شود.
                }

                false
            }

        } catch (_: Exception) {
            false
        }
    }

    private suspend fun calculateSha256(
        file: File
    ): String? = withContext(Dispatchers.IO) {

        try {
            if (!file.exists() || !file.isFile) {
                return@withContext null
            }

            if (file.length() > MAX_TARGET_FILE_SIZE) {
                return@withContext null
            }

            val digest = MessageDigest.getInstance("SHA-256")

            file.inputStream().use { input ->

                val buffer = ByteArray(8192)
                var total = 0L

                while (true) {
                    val count = input.read(buffer)

                    if (count == -1) break

                    total += count

                    if (total > MAX_TARGET_FILE_SIZE) {
                        return@withContext null
                    }

                    digest.update(buffer, 0, count)
                }
            }

            digest.digest().joinToString("") {
                "%02x".format(it)
            }

        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
    }

    suspend fun create(
        projectPath: String,
        filePath: String,
        backupPath: String,
        beforeHash: String,
        targetHash: String,
        operationType: RecoveryOperationType
    ): RecoveryJournalEntry? = withContext(Dispatchers.IO) {

        try {
            if (projectPath.isBlank() ||
                filePath.isBlank() ||
                backupPath.isBlank()
            ) {
                return@withContext null
            }

            if (!isValidHash(beforeHash) ||
                !isValidHash(targetHash)
            ) {
                return@withContext null
            }

            if (!journalDirectory.exists() &&
                !journalDirectory.mkdirs()
            ) {
                return@withContext null
            }

            val canonicalProject =
                File(projectPath).canonicalFile

            val canonicalTarget =
                File(filePath).canonicalFile

            val canonicalBackup =
                File(backupPath).canonicalFile

            if (!isInsideDirectory(
                    canonicalTarget,
                    canonicalProject
                )
            ) {
                return@withContext null
            }

            val id = UUID.randomUUID().toString()

            val entry = RecoveryJournalEntry(
                id = id,
                projectPath = canonicalProject.path,
                filePath = canonicalTarget.path,
                backupPath = canonicalBackup.path,
                beforeHash = beforeHash.lowercase(),
                targetHash = targetHash.lowercase(),
                operationType = operationType,
                state = RecoveryOperationState.PREPARED,
                createdAt = System.currentTimeMillis()
            )

            val file = journalFile(id)
                ?: return@withContext null

            if (!writeEntry(file, entry)) {
                return@withContext null
            }

            entry

        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
    }

    suspend fun markCommitted(
        operationId: String
    ): Boolean = withContext(Dispatchers.IO) {

        try {
            val file = journalFile(operationId)
                ?: return@withContext false

            val entry = readEntry(file)

            if (entry.id != operationId) {
                return@withContext false
            }

            if (entry.state == RecoveryOperationState.COMMITTED) {
                return@withContext true
            }

            val updated = entry.copy(
                state = RecoveryOperationState.COMMITTED
            )

            writeEntry(file, updated)

        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            false
        }
    }

    suspend fun remove(
        operationId: String
    ): Boolean = withContext(Dispatchers.IO) {

        try {
            val file = journalFile(operationId)
                ?: return@withContext false

            AtomicFile(file).delete()

            !file.exists() &&
                !File(file.path + ".bak").exists() &&
                !File(file.path + ".new").exists()

        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getPendingEntries(
        projectPath: String
    ): List<RecoveryJournalEntry> = withContext(Dispatchers.IO) {

        if (projectPath.isBlank()) {
            throw ProjectRecoveryException(
                listOf("مسیر پروژه برای بررسی گزارش‌های بازیابی خالی است.")
            )
        }

        val canonicalProject = try {
            File(projectPath).canonicalPath
        } catch (exception: Exception) {
            throw ProjectRecoveryException(
                listOf("مسیر پروژه برای بررسی گزارش‌های بازیابی معتبر نیست.")
            )
        }

        if (!journalDirectory.exists()) {
            return@withContext emptyList()
        }

        if (!journalDirectory.isDirectory) {
            throw ProjectRecoveryException(
                listOf("مسیر گزارش‌های بازیابی یک پوشه معتبر نیست.")
            )
        }

        val files = journalDirectory.listFiles()
            ?: throw ProjectRecoveryException(
                listOf("خواندن فهرست گزارش‌های بازیابی امکان‌پذیر نیست.")
            )

        val entries = mutableListOf<RecoveryJournalEntry>()
        val errors = mutableListOf<String>()

        for (file in files) {

            if (!file.isFile ||
                !file.extension.equals("json", ignoreCase = true)
            ) {
                continue
            }

            try {
                val entry = readEntry(file)

                if (entry.projectPath == canonicalProject) {
                    entries.add(entry)
                }

            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                errors.add(
                    "گزارش ${file.name} قابل خواندن نیست: " +
                        (exception.message ?: "خطای نامشخص")
                )
            }
        }

        if (errors.isNotEmpty()) {
            throw ProjectRecoveryException(errors)
        }

        entries.sortedBy { it.createdAt }
    }

    suspend fun inspectOperation(
        entry: RecoveryJournalEntry
    ): RecoveryCheckResult = withContext(Dispatchers.IO) {

        if (!isValidEntry(entry)) {
            return@withContext RecoveryCheckResult.Invalid(
                entry.id,
                "اطلاعات عملیات بازیابی معتبر نیست."
            )
        }

        val project = try {
            File(entry.projectPath).canonicalFile
        } catch (_: Exception) {
            return@withContext RecoveryCheckResult.Invalid(
                entry.id,
                "مسیر پروژه معتبر نیست."
            )
        }

        val target = try {
            File(entry.filePath).canonicalFile
        } catch (_: Exception) {
            return@withContext RecoveryCheckResult.Invalid(
                entry.id,
                "مسیر فایل اصلی معتبر نیست."
            )
        }

        val backup = try {
            File(entry.backupPath).canonicalFile
        } catch (_: Exception) {
            return@withContext RecoveryCheckResult.Invalid(
                entry.id,
                "مسیر نسخه پشتیبان معتبر نیست."
            )
        }

        if (!isInsideDirectory(target, project)) {
            return@withContext RecoveryCheckResult.Invalid(
                entry.id,
                "فایل اصلی خارج از پوشه پروژه قرار دارد."
            )
        }

        if (!target.exists() || !target.isFile) {
            return@withContext RecoveryCheckResult.Pending(
                entry.id,
                "فایل اصلی پیدا نشد؛ بررسی دستی لازم است."
            )
        }

        if (!backup.exists() || !backup.isFile) {
            return@withContext RecoveryCheckResult.Pending(
                entry.id,
                "نسخه پشتیبان پیدا نشد؛ عملیات نیازمند بررسی است."
            )
        }

        if (backup.length() > MAX_TARGET_FILE_SIZE) {
            return@withContext RecoveryCheckResult.Invalid(
                entry.id,
                "اندازه نسخه پشتیبان از حد مجاز بیشتر است."
            )
        }

        val backupHash = calculateSha256(backup)
            ?: return@withContext RecoveryCheckResult.Pending(
                entry.id,
                "هش نسخه پشتیبان قابل محاسبه نیست."
            )

        if (!backupHash.equals(entry.beforeHash, ignoreCase = true)) {
            return@withContext RecoveryCheckResult.Pending(
                entry.id,
                "محتوای نسخه پشتیبان با هش محتوای اولیه مطابقت ندارد."
            )
        }

        val currentHash = calculateSha256(target)
            ?: return@withContext RecoveryCheckResult.Pending(
                entry.id,
                "هش فایل فعلی قابل محاسبه نیست."
            )

        val matchesBefore = currentHash.equals(
            entry.beforeHash,
            ignoreCase = true
        )

        val matchesTarget = currentHash.equals(
            entry.targetHash,
            ignoreCase = true
        )

        when (entry.state) {

            RecoveryOperationState.PREPARED -> {
                when {
                    matchesTarget -> RecoveryCheckResult.Resolved(
                        entry.id,
                        "محتوای جدید نوشته شده است؛ ثبت نهایی گزارش هنوز کامل نشده بود."
                    )

                    matchesBefore -> RecoveryCheckResult.Resolved(
                        entry.id,
                        "محتوای قبلی فایل حفظ شده است؛ تغییری اعمال نشده بود."
                    )

                    else -> RecoveryCheckResult.Pending(
                        entry.id,
                        "محتوای فایل با وضعیت ثبت‌شده مطابقت ندارد؛ بازیابی خودکار انجام نشد."
                    )
                }
            }

            RecoveryOperationState.COMMITTED -> {
                when {
                    matchesTarget -> RecoveryCheckResult.Resolved(
                        entry.id,
                        "عملیات ثبت نهایی شده و محتوای فایل با نسخه مقصد مطابقت دارد."
                    )

                    matchesBefore -> RecoveryCheckResult.Pending(
                        entry.id,
                        "گزارش عملیات ثبت نهایی شده است اما فایل هنوز محتوای قبلی را دارد."
                    )

                    else -> RecoveryCheckResult.Pending(
                        entry.id,
                        "محتوای فایل با عملیات ثبت نهایی‌شده مطابقت ندارد؛ بررسی دستی لازم است."
                    )
                }
            }
        }
    }

    suspend fun inspectAll(
        projectPath: String
    ): List<RecoveryCheckResult> {

        val entries = getPendingEntries(projectPath)

        return entries.map { entry ->
            inspectOperation(entry)
        }
    }
}
