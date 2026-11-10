
package com.faridtech.ai

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

        private val HASH_REGEX = Regex("^[a-fA-F0-9]{64}$")
    }

    private val appContext = context.applicationContext

    private val journalDirectory =
        File(appContext.filesDir, JOURNAL_DIRECTORY)

    private fun journalFile(id: String): File? {
        if (id.isBlank() || !id.matches(Regex("^[a-fA-F0-9-]{36}$"))) {
            return null
        }

        val file = File(journalDirectory, "$id.json")

        return try {
            val root = journalDirectory.canonicalFile
            val target = file.canonicalFile

            if (target.parentFile == root) target else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidHash(hash: String): Boolean {
        return HASH_REGEX.matches(hash)
    }

    private fun isValidEntry(entry: RecoveryJournalEntry): Boolean {
        if (entry.id.isBlank()) return false
        if (!entry.id.matches(Regex("^[a-fA-F0-9-]{36}$"))) return false

        if (entry.projectPath.isBlank()) return false
        if (entry.filePath.isBlank()) return false
        if (entry.backupPath.isBlank()) return false

        if (!isValidHash(entry.beforeHash)) return false
        if (!isValidHash(entry.targetHash)) return false

        if (entry.createdAt <= 0L) return false

        return true
    }

    private fun serialize(entry: RecoveryJournalEntry): ByteArray {
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

    private fun deserialize(bytes: ByteArray): RecoveryJournalEntry? {
        if (bytes.isEmpty() || bytes.size > MAX_JOURNAL_SIZE) {
            return null
        }

        return try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))

            if (json.optInt("version", -1) != JOURNAL_VERSION) {
                return null
            }

            val operationType = runCatching {
                RecoveryOperationType.valueOf(
                    json.getString("operationType")
                )
            }.getOrNull() ?: return null

            val state = runCatching {
                RecoveryOperationState.valueOf(
                    json.getString("state")
                )
            }.getOrNull() ?: return null

            RecoveryJournalEntry(
                id = json.getString("id"),
                projectPath = json.getString("projectPath"),
                filePath = json.getString("filePath"),
                backupPath = json.getString("backupPath"),
                beforeHash = json.getString("beforeHash"),
                targetHash = json.getString("targetHash"),
                operationType = operationType,
                state = state,
                createdAt = json.getLong("createdAt")
            ).takeIf(::isValidEntry)
        } catch (_: Exception) {
            null
        }
    }

    private fun readEntry(file: File): RecoveryJournalEntry? {
        return try {
            if (!file.exists() || !file.isFile) return null
            if (file.length() > MAX_JOURNAL_SIZE) return null

            val atomicFile = AtomicFile(file)
            val bytes = atomicFile.openRead().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                var total = 0

                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break

                    total += count
                    if (total > MAX_JOURNAL_SIZE) {
                        return null
                    }

                    output.write(buffer, 0, count)
                }

                output.toByteArray()
            }

            deserialize(bytes)
        } catch (_: Exception) {
            null
        }
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

            val bytes = serialize(entry)
            if (bytes.size > MAX_JOURNAL_SIZE) return false

            val atomicFile = AtomicFile(file)
            val output = atomicFile.startWrite()

            try {
                output.write(bytes)
                atomicFile.finishWrite(output)
                true
            } catch (exception: Exception) {
                atomicFile.failWrite(output)
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun calculateSha256(file: File): String? =
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists() || !file.isFile) return@withContext null
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

        if (projectPath.isBlank() ||
            filePath.isBlank() ||
            backupPath.isBlank()
        ) {
            return@withContext null
        }

        if (!isValidHash(beforeHash) || !isValidHash(targetHash)) {
            return@withContext null
        }

        try {
            if (!journalDirectory.exists() &&
                !journalDirectory.mkdirs()
            ) {
                return@withContext null
            }

            val id = UUID.randomUUID().toString()

            val entry = RecoveryJournalEntry(
                id = id,
                projectPath = File(projectPath).canonicalPath,
                filePath = File(filePath).canonicalPath,
                backupPath = File(backupPath).canonicalPath,
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
        } catch (_: Exception) {
            null
        }
    }

    suspend fun markCommitted(operationId: String): Boolean =
        withContext(Dispatchers.IO) {

            val file = journalFile(operationId)
                ?: return@withContext false

            val entry = readEntry(file)
                ?: return@withContext false

            val updated = entry.copy(
                state = RecoveryOperationState.COMMITTED
            )

            writeEntry(file, updated)
        }

    suspend fun remove(operationId: String): Boolean =
        withContext(Dispatchers.IO) {

            val file = journalFile(operationId)
                ?: return@withContext false

            if (!file.exists()) {
                return@withContext true
            }

            try {
                AtomicFile(file).delete()
                !file.exists()
            } catch (_: Exception) {
                false
            }
        }

    suspend fun getPendingEntries(
        projectPath: String
    ): List<RecoveryJournalEntry> = withContext(Dispatchers.IO) {

        if (projectPath.isBlank()) {
            return@withContext emptyList()
        }

        val canonicalProject = try {
            File(projectPath).canonicalPath
        } catch (_: Exception) {
            return@withContext emptyList()
        }

        if (!journalDirectory.exists() ||
            !journalDirectory.isDirectory
        ) {
            return@withContext emptyList()
        }

        journalDirectory.listFiles()
            ?.asSequence()
            ?.filter {
                it.isFile &&
                    it.extension.equals("json", ignoreCase = true)
            }
            ?.mapNotNull(::readEntry)
            ?.filter { it.projectPath == canonicalProject }
            ?.sortedBy { it.createdAt }
            ?.toList()
            ?: emptyList()
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

        val currentHash = calculateSha256(target)
            ?: return@withContext RecoveryCheckResult.Pending(
                entry.id,
                "هش فایل فعلی قابل محاسبه نیست."
            )

        when (currentHash) {
            entry.targetHash -> RecoveryCheckResult.Resolved(
                entry.id,
                "محتوای جدید با موفقیت ثبت شده است."
            )

            entry.beforeHash -> RecoveryCheckResult.Resolved(
                entry.id,
                "محتوای قبلی فایل حفظ شده است."
            )

            else -> RecoveryCheckResult.Pending(
                entry.id,
                "محتوای فایل با وضعیت ثبت‌شده مطابقت ندارد؛ بازیابی خودکار انجام نشد."
            )
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
