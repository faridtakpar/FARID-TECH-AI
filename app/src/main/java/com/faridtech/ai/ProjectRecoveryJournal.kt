
package com.faridtech.ai

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
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
        private const val JOURNAL_DIRECTORY =
            "project_recovery"

        private const val MAX_JOURNAL_SIZE =
            16 * 1024L

        private const val MAX_FILE_SIZE =
            256 * 1024L

        private const val BUFFER_SIZE =
            8192
    }

    private val appContext =
        context.applicationContext

    private val journalRoot: File
        get() = File(
            appContext.filesDir,
            JOURNAL_DIRECTORY
        )

    private fun isValidHash(value: String): Boolean {
        return value.matches(
            Regex("^[a-fA-F0-9]{64}$")
        )
    }

    private fun calculateHash(bytes: ByteArray): String {
        val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)

        return digest.joinToString("") {
            "%02x".format(it)
        }
    }

    private fun validateJournalRoot(): File? {
        return try {
            val root = journalRoot.canonicalFile
            val filesRoot = appContext.filesDir.canonicalFile

            val prefix =
                filesRoot.path + File.separator

            if (!root.path.startsWith(prefix)) {
                return null
            }

            if (!root.exists() && !root.mkdirs()) {
                return null
            }

            if (!root.isDirectory) {
                return null
            }

            root

        } catch (_: Exception) {
            null
        }
    }

    private fun validateJournalFile(
        root: File,
        file: File
    ): File? {
        return try {
            val canonicalRoot = root.canonicalFile
            val canonicalFile = file.canonicalFile

            val prefix =
                canonicalRoot.path + File.separator

            if (!canonicalFile.path.startsWith(prefix)) {
                return null
            }

            canonicalFile

        } catch (_: Exception) {
            null
        }
    }

    private fun readLimited(
        file: File,
        maxSize: Long
    ): ByteArray? {
        return try {
            if (!file.isFile || !file.canRead()) {
                return null
            }

            if (file.length() > maxSize) {
                return null
            }

            val output = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L

            AtomicFile(file).openRead().use { input ->

                while (true) {
                    val count = input.read(buffer)

                    if (count == -1) {
                        break
                    }

                    total += count

                    if (total > maxSize) {
                        return null
                    }

                    output.write(buffer, 0, count)
                }
            }

            output.toByteArray()

        } catch (_: Exception) {
            null
        }
    }

    private fun writeAtomically(
        file: File,
        bytes: ByteArray
    ): Boolean {

        var stream: java.io.FileOutputStream? = null
        val atomicFile = AtomicFile(file)

        return try {
            if (bytes.size > MAX_JOURNAL_SIZE) {
                return false
            }

            val parent = file.parentFile
                ?: return false

            if (!parent.isDirectory) {
                return false
            }

            val output = atomicFile.startWrite()
            stream = output

            output.write(bytes)
            output.fd.sync()

            atomicFile.finishWrite(output)
            stream = null

            true

        } catch (_: Exception) {

            stream?.let {
                try {
                    atomicFile.failWrite(it)
                } catch (_: Exception) {
                }
            }

            false
        }
    }

    private fun serialize(
        entry: RecoveryJournalEntry
    ): ByteArray {

        val json = JSONObject().apply {
            put("id", entry.id)
            put("projectPath", entry.projectPath)
            put("filePath", entry.filePath)
            put("backupPath", entry.backupPath)
            put("beforeHash", entry.beforeHash)
            put("targetHash", entry.targetHash)
            put("operationType", entry.operationType.name)
            put("state", entry.state.name)
            put("createdAt", entry.createdAt)
            put("version", 1)
        }

        return json.toString(2)
            .toByteArray(Charsets.UTF_8)
    }

    private fun parse(
        file: File,
        bytes: ByteArray
    ): RecoveryJournalEntry? {
        return try {
            if (bytes.size > MAX_JOURNAL_SIZE) {
                return null
            }

            val json = JSONObject(
                String(bytes, Charsets.UTF_8)
            )

            if (json.optInt("version", -1) != 1) {
                return null
            }

            val id = json.optString("id")
            val projectPath = json.optString("projectPath")
            val filePath = json.optString("filePath")
            val backupPath = json.optString("backupPath")
            val beforeHash = json.optString("beforeHash")
            val targetHash = json.optString("targetHash")
            val operationName = json.optString("operationType")
            val stateName = json.optString("state")
            val createdAt = json.optLong("createdAt", 0L)

            val parsedId = try {
                UUID.fromString(id)
            } catch (_: Exception) {
                return null
            }

            if (parsedId.toString() != id) {
                return null
            }

            if (file.nameWithoutExtension != id) {
                return null
            }

            if (
                projectPath.isBlank() ||
                filePath.isBlank() ||
                backupPath.isBlank()
            ) {
                return null
            }

            if (
                !isValidHash(beforeHash) ||
                !isValidHash(targetHash)
            ) {
                return null
            }

            if (createdAt <= 0L) {
                return null
            }

            val operationType = try {
                RecoveryOperationType.valueOf(operationName)
            } catch (_: Exception) {
                return null
            }

            val state = try {
                RecoveryOperationState.valueOf(stateName)
            } catch (_: Exception) {
                return null
            }

            RecoveryJournalEntry(
                id = id,
                projectPath = projectPath,
                filePath = filePath,
                backupPath = backupPath,
                beforeHash = beforeHash,
                targetHash = targetHash,
                operationType = operationType,
                state = state,
                createdAt = createdAt
            )

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
    ): RecoveryJournalEntry? =
        withContext(Dispatchers.IO) {

            synchronized(this@ProjectRecoveryJournal) {

                try {
                    if (
                        !isValidHash(beforeHash) ||
                        !isValidHash(targetHash)
                    ) {
                        return@synchronized null
                    }

                    if (
                        projectPath.isBlank() ||
                        filePath.isBlank() ||
                        backupPath.isBlank()
                    ) {
                        return@synchronized null
                    }

                    val root = validateJournalRoot()
                        ?: return@synchronized null

                    val id = UUID.randomUUID().toString()

                    val file = File(
                        root,
                        "$id.json"
                    )

                    val safeFile = validateJournalFile(
                        root,
                        file
                    ) ?: return@synchronized null

                    val entry = RecoveryJournalEntry(
                        id = id,
                        projectPath = projectPath,
                        filePath = filePath,
                        backupPath = backupPath,
                        beforeHash = beforeHash,
                        targetHash = targetHash,
                        operationType = operationType,
                        state = RecoveryOperationState.PREPARED,
                        createdAt = System.currentTimeMillis()
                    )

                    if (!writeAtomically(safeFile, serialize(entry))) {
                        return@synchronized null
                    }

                    entry

                } catch (_: Exception) {
                    null
                }
            }
        }

    suspend fun markCommitted(
        operationId: String
    ): Boolean =
        withContext(Dispatchers.IO) {

            synchronized(this@ProjectRecoveryJournal) {

                try {
                    val root = validateJournalRoot()
                        ?: return@synchronized false

                    val file = validateJournalFile(
                        root,
                        File(root, "$operationId.json")
                    ) ?: return@synchronized false

                    if (!file.isFile || file.length() > MAX_JOURNAL_SIZE) {
                        return@synchronized false
                    }

                    val bytes = readLimited(
                        file,
                        MAX_JOURNAL_SIZE
                    ) ?: return@synchronized false

                    val entry = parse(file, bytes)
                        ?: return@synchronized false

                    if (entry.id != operationId) {
                        return@synchronized false
                    }

                    val updated = entry.copy(
                        state = RecoveryOperationState.COMMITTED
                    )

                    writeAtomically(
                        file,
                        serialize(updated)
                    )

                } catch (_: Exception) {
                    false
                }
            }
        }

    suspend fun remove(
        operationId: String
    ): Boolean =
        withContext(Dispatchers.IO) {

            synchronized(this@ProjectRecoveryJournal) {

                try {
                    val root = validateJournalRoot()
                        ?: return@synchronized false

                    val file = validateJournalFile(
                        root,
                        File(root, "$operationId.json")
                    ) ?: return@synchronized false

                    if (!file.isFile) {
                        return@synchronized true
                    }

                    val bytes = readLimited(
                        file,
                        MAX_JOURNAL_SIZE
                    ) ?: return@synchronized false

                    val entry = parse(file, bytes)
                        ?: return@synchronized false

                    if (entry.id != operationId) {
                        return@synchronized false
                    }

                    AtomicFile(file).delete()

                    !file.exists()

                } catch (_: Exception) {
                    false
                }
            }
        }

    suspend fun getPendingEntries(
        projectPath: String
    ): List<RecoveryJournalEntry> =
        withContext(Dispatchers.IO) {

            synchronized(this@ProjectRecoveryJournal) {

                try {
                    val root = validateJournalRoot()
                        ?: return@synchronized emptyList()

                    val files = root.listFiles()
                        ?: return@synchronized emptyList()

                    val result = mutableListOf<RecoveryJournalEntry>()

                    for (file in files) {
                        try {
                            if (
                                !file.isFile ||
                                !file.extension.equals(
                                    "json",
                                    ignoreCase = true
                                ) ||
                                file.length() > MAX_JOURNAL_SIZE
                            ) {
                                continue
                            }

                            val safeFile = validateJournalFile(
                                root,
                                file
                            ) ?: continue

                            val bytes = readLimited(
                                safeFile,
                                MAX_JOURNAL_SIZE
                            ) ?: continue

                            val entry = parse(
                                safeFile,
                                bytes
                            ) ?: continue

                            if (entry.projectPath == projectPath) {
                                result.add(entry)
                            }

                        } catch (_: Exception) {
                            // خرابی یک ورودی نباید سایر ورودی‌ها را متوقف کند.
                        }
                    }

                    result.sortedByDescending {
                        it.createdAt
                    }

                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

    suspend fun inspectOperation(
        entry: RecoveryJournalEntry
    ): RecoveryCheckResult =
        withContext(Dispatchers.IO) {

            synchronized(this@ProjectRecoveryJournal) {

                try {
                    val target = File(entry.filePath).canonicalFile

                    if (!target.isFile || !target.canRead()) {
                        return@synchronized RecoveryCheckResult.Pending(
                            entry.id,
                            "فایل مقصد در دسترس نیست؛ عملیات برای بررسی بعدی حفظ شد."
                        )
                    }

                    val currentBytes = readLimited(
                        target,
                        MAX_FILE_SIZE
                    ) ?: return@synchronized RecoveryCheckResult.Pending(
                        entry.id,
                        "خواندن فایل مقصد ممکن نیست؛ Journal حذف نشد."
                    )

                    val currentHash = calculateHash(currentBytes)

                    when (currentHash) {

                        entry.targetHash -> {
                            RecoveryCheckResult.Resolved(
                                entry.id,
                                "محتوای مقصد با نسخه مورد انتظار مطابقت دارد."
                            )
                        }

                        entry.beforeHash -> {
                            RecoveryCheckResult.Resolved(
                                entry.id,
                                "فایل هنوز در وضعیت پیش از عملیات قرار دارد."
                            )
                        }

                        else -> {
                            RecoveryCheckResult.Pending(
                                entry.id,
                                "محتوای فایل با هیچ‌یک از هش‌های ثبت‌شده مطابقت ندارد؛ از بازنویسی خودکار جلوگیری شد."
                            )
                        }
                    }

                } catch (_: Exception) {
                    RecoveryCheckResult.Pending(
                        entry.id,
                        "بررسی عملیات ناموفق بود؛ اطلاعات آن حفظ شد."
                    )
                }
            }
        }

    suspend fun inspectAll(
        projectPath: String
    ): List<RecoveryCheckResult> {

        val entries = getPendingEntries(projectPath)

        val results = mutableListOf<RecoveryCheckResult>()

        for (entry in entries) {
            results.add(inspectOperation(entry))
        }

        return results
    }
}
