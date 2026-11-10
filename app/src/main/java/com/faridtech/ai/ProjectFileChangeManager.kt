
package com.faridtech.ai

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class ProjectFileChangePreview(
    val projectPath: String,
    val filePath: String,
    val originalHash: String,
    val proposedContent: String,
    val originalSize: Long
)

data class ProjectBackupEntry(
    val backupPath: String,
    val projectPath: String,
    val filePath: String,
    val originalHash: String,
    val appliedHash: String,
    val createdAt: Long,
    val backupSize: Long,
    val isValid: Boolean
)

sealed class ProjectFileChangeResult {

    data class Applied(
        val backupPath: String
    ) : ProjectFileChangeResult()

    data class AppliedWithPendingRecovery(
        val backupPath: String,
        val reason: String
    ) : ProjectFileChangeResult()

    data class Rejected(
        val reason: String
    ) : ProjectFileChangeResult()

    data class RejectedWithPendingRecovery(
        val reason: String
    ) : ProjectFileChangeResult()
}

sealed class ProjectFileRestoreResult {

    data class Restored(
        val filePath: String
    ) : ProjectFileRestoreResult()

    data class RestoredWithPendingRecovery(
        val filePath: String,
        val reason: String
    ) : ProjectFileRestoreResult()

    data class Rejected(
        val reason: String
    ) : ProjectFileRestoreResult()

    data class RejectedWithPendingRecovery(
        val reason: String
    ) : ProjectFileRestoreResult()
}

class ProjectRecoveryException(
    val recoveryMessages: List<String>
) : Exception(recoveryMessages.joinToString("\n"))

class ProjectFileChangeManager(context: Context) {

    companion object {
        private const val MAX_FILE_SIZE = 256 * 1024L
        private const val MAX_MANIFEST_SIZE = 16 * 1024L

        private const val PROJECTS_DIRECTORY = "projects"
        private const val BACKUP_DIRECTORY = "project_backups"

        private val ALLOWED_EXTENSIONS = setOf(
            "kt", "java", "xml", "gradle", "kts",
            "txt", "md", "json", "html", "css",
            "js", "cpp", "h", "c", "cmake",
            "pro", "sql", "py", "dart", "swift"
        )

        private val BLOCKED_NAMES = setOf(
            ".env",
            ".env.local",
            "local.properties",
            "gradle.properties",
            "key.properties"
        )

        private val BLOCKED_EXTENSIONS = setOf(
            "keystore", "jks", "pem", "p12", "pfx"
        )

        private val globalMutex = Mutex()
    }

    private val appContext = context.applicationContext

    private val recoveryJournal =
        ProjectRecoveryJournal(appContext)

    private data class CreatedBackup(
        val backupFile: File,
        val backupPath: String,
        val originalHash: String,
        val appliedHash: String
    )

    private enum class RollbackResult {
        RESTORED_AND_JOURNAL_REMOVED,
        RESTORED_WITH_PENDING_JOURNAL,
        FAILED
    }

    private suspend fun <T> withGlobalLock(
        block: suspend () -> T
    ): T {
        globalMutex.lock()
        return try {
            block()
        } finally {
            globalMutex.unlock()
        }
    }

    private fun validateProject(project: File): File? {
        return try {
            val projectsRoot = File(
                appContext.filesDir,
                PROJECTS_DIRECTORY
            ).canonicalFile

            val projectRoot = project.canonicalFile
            val prefix = projectsRoot.path + File.separator

            if (!projectsRoot.isDirectory) return null
            if (!projectRoot.isDirectory) return null
            if (!projectRoot.path.startsWith(prefix)) return null

            projectRoot
        } catch (_: Exception) {
            null
        }
    }

    private fun validateFile(
        projectRoot: File,
        file: File
    ): File? {
        return try {
            val root = projectRoot.canonicalFile
            val target = file.canonicalFile
            val prefix = root.path + File.separator

            if (!target.path.startsWith(prefix)) return null
            if (!target.isFile || !target.canRead()) return null
            if (!target.canWrite()) return null
            if (target.length() > MAX_FILE_SIZE) return null

            val name = target.name.lowercase()
            if (name in BLOCKED_NAMES) return null

            val extension = target.extension.lowercase()
            if (extension in BLOCKED_EXTENSIONS) return null
            if (extension !in ALLOWED_EXTENSIONS) return null

            target
        } catch (_: Exception) {
            null
        }
    }

    private fun isSafeHistoricalPath(
        projectRoot: File,
        filePath: String
    ): Boolean {
        return try {
            val root = projectRoot.canonicalFile
            val target = File(filePath).canonicalFile
            val prefix = root.path + File.separator

            if (!target.path.startsWith(prefix)) return false

            val name = target.name.lowercase()
            val extension = target.extension.lowercase()

            name !in BLOCKED_NAMES &&
                extension !in BLOCKED_EXTENSIONS &&
                extension in ALLOWED_EXTENSIONS
        } catch (_: Exception) {
            false
        }
    }

    private fun calculateHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)

        return digest.joinToString("") {
            "%02x".format(it)
        }
    }

    private fun isValidHash(value: String): Boolean {
        return value.matches(Regex("^[a-fA-F0-9]{64}$"))
    }

    private fun readLimitedFile(file: File): ByteArray? {
        return try {
            if (!file.exists() || !file.isFile || !file.canRead()) {
                return null
            }

            if (file.length() > MAX_FILE_SIZE) return null

            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L

            AtomicFile(file).openRead().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break

                    total += count
                    if (total > MAX_FILE_SIZE) return null

                    output.write(buffer, 0, count)
                }
            }

            output.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun writeAtomically(
        target: File,
        bytes: ByteArray
    ): Boolean {
        if (bytes.size > MAX_FILE_SIZE) return false

        val atomicFile = AtomicFile(target)
        var output: FileOutputStream? = null

        return try {
            val parent = target.parentFile ?: return false
            if (!parent.isDirectory) return false

            val stream = atomicFile.startWrite()
            output = stream

            stream.write(bytes)
            stream.fd.sync()
            atomicFile.finishWrite(stream)

            output = null
            true
        } catch (_: Exception) {
            output?.let {
                try {
                    atomicFile.failWrite(it)
                } catch (_: Exception) {
                }
            }
            false
        }
    }

    private fun writeBackup(
        backupFile: File,
        originalBytes: ByteArray
    ): Boolean {
        if (originalBytes.size > MAX_FILE_SIZE) return false
        return writeAtomically(backupFile, originalBytes)
    }

    private fun writeManifest(
        manifestFile: File,
        json: JSONObject
    ): Boolean {
        return try {
            val bytes = json.toString(2)
                .toByteArray(Charsets.UTF_8)

            if (bytes.size > MAX_MANIFEST_SIZE) return false

            writeAtomically(manifestFile, bytes)
        } catch (_: Exception) {
            false
        }
    }

    private fun createBackupRecord(
        projectRoot: File,
        target: File,
        originalBytes: ByteArray,
        appliedHash: String
    ): CreatedBackup? {
        return try {
            if (originalBytes.size > MAX_FILE_SIZE) return null
            if (!isValidHash(appliedHash)) return null

            val backupRoot = File(
                appContext.filesDir,
                BACKUP_DIRECTORY
            )

            if (!backupRoot.exists() && !backupRoot.mkdirs()) {
                return null
            }

            val canonicalRoot = backupRoot.canonicalFile
            if (!canonicalRoot.isDirectory) return null

            val id = UUID.randomUUID().toString()

            val backupFile = File(
                canonicalRoot,
                "$id.bak"
            ).canonicalFile

            val manifestFile = File(
                canonicalRoot,
                "$id.json"
            ).canonicalFile

            val prefix = canonicalRoot.path + File.separator

            if (
                !backupFile.path.startsWith(prefix) ||
                !manifestFile.path.startsWith(prefix)
            ) {
                return null
            }

            val originalHash = calculateHash(originalBytes)

            if (!writeBackup(backupFile, originalBytes)) {
                return null
            }

            val manifest = JSONObject().apply {
                put("projectPath", projectRoot.path)
                put("filePath", target.path)
                put("originalHash", originalHash)
                put("appliedHash", appliedHash)
                put("backupId", id)
                put("createdAt", System.currentTimeMillis())
            }

            if (!writeManifest(manifestFile, manifest)) {
                return null
            }

            CreatedBackup(
                backupFile = backupFile,
                backupPath = backupFile.absolutePath,
                originalHash = originalHash,
                appliedHash = appliedHash
            )
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun beginRecoveryOperation(
        projectRoot: File,
        target: File,
        backup: CreatedBackup,
        operationType: RecoveryOperationType
    ): RecoveryJournalEntry? {
        return try {
            recoveryJournal.create(
                projectPath = projectRoot.canonicalPath,
                filePath = target.canonicalPath,
                backupPath = backup.backupFile.canonicalPath,
                beforeHash = backup.originalHash,
                targetHash = backup.appliedHash,
                operationType = operationType
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun finishRecoveryOperation(
        operationId: String
    ): Boolean {
        return try {
            val committed =
                recoveryJournal.markCommitted(operationId)

            if (!committed) return false

            recoveryJournal.remove(operationId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun removeJournalIfStillOriginal(
        operationId: String,
        target: File,
        beforeHash: String
    ): Boolean {
        return try {
            val bytes = readLimitedFile(target) ?: return false

            if (calculateHash(bytes) != beforeHash) {
                return false
            }

            recoveryJournal.remove(operationId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun rollbackOperation(
        target: File,
        originalBytes: ByteArray,
        beforeHash: String,
        operationId: String?
    ): RollbackResult {
        return try {
            if (!writeAtomically(target, originalBytes)) {
                return RollbackResult.FAILED
            }

            val rollbackBytes =
                readLimitedFile(target) ?: return RollbackResult.FAILED

            if (calculateHash(rollbackBytes) != beforeHash) {
                return RollbackResult.FAILED
            }

            if (operationId == null) {
                return RollbackResult.RESTORED_AND_JOURNAL_REMOVED
            }

            if (
                removeJournalIfStillOriginal(
                    operationId,
                    target,
                    beforeHash
                )
            ) {
                RollbackResult.RESTORED_AND_JOURNAL_REMOVED
            } else {
                RollbackResult.RESTORED_WITH_PENDING_JOURNAL
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            RollbackResult.FAILED
        }
    }

    suspend fun prepareChange(
        project: File,
        file: File,
        proposedContent: String
    ): ProjectFileChangePreview? =
        withContext(Dispatchers.IO) {
            try {
                val root = validateProject(project)
                    ?: return@withContext null

                val target = validateFile(root, file)
                    ?: return@withContext null

                val proposedBytes =
                    proposedContent.toByteArray(Charsets.UTF_8)

                if (
                    proposedBytes.isEmpty() ||
                    proposedBytes.size > MAX_FILE_SIZE
                ) {
                    return@withContext null
                }

                val originalBytes =
                    readLimitedFile(target) ?: return@withContext null

                val originalContent =
                    String(originalBytes, Charsets.UTF_8)

                if (originalContent == proposedContent) {
                    return@withContext null
                }

                ProjectFileChangePreview(
                    projectPath = root.path,
                    filePath = target.path,
                    originalHash = calculateHash(originalBytes),
                    proposedContent = proposedContent,
                    originalSize = originalBytes.size.toLong()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    suspend fun applyChange(
        preview: ProjectFileChangePreview,
        ownerConfirmed: Boolean
    ): ProjectFileChangeResult =
        withContext(Dispatchers.IO) {

            if (!ownerConfirmed) {
                return@withContext ProjectFileChangeResult.Rejected(
                    "اعمال تغییر بدون تأیید مالک مجاز نیست."
                )
            }

            withGlobalLock {

                var journalEntry: RecoveryJournalEntry? = null

                try {
                    val root = validateProject(
                        File(preview.projectPath)
                    ) ?: return@withGlobalLock ProjectFileChangeResult.Rejected(
                        "مسیر پروژه معتبر نیست."
                    )

                    val target = validateFile(
                        root,
                        File(preview.filePath)
                    ) ?: return@withGlobalLock ProjectFileChangeResult.Rejected(
                        "فایل انتخاب‌شده معتبر یا قابل نوشتن نیست."
                    )

                    if (
                        target.canonicalPath !=
                        File(preview.filePath).canonicalPath
                    ) {
                        return@withGlobalLock ProjectFileChangeResult.Rejected(
                            "مسیر فایل تغییر کرده است."
                        )
                    }

                    if (!isValidHash(preview.originalHash)) {
                        return@withGlobalLock ProjectFileChangeResult.Rejected(
                            "هش نسخه اولیه معتبر نیست."
                        )
                    }

                    val originalBytes =
                        readLimitedFile(target)
                            ?: return@withGlobalLock ProjectFileChangeResult.Rejected(
                                "خواندن فایل اصلی ناموفق بود."
                            )

                    val originalHash = calculateHash(originalBytes)

                    if (originalHash != preview.originalHash) {
                        return@withGlobalLock ProjectFileChangeResult.Rejected(
                            "فایل از زمان تحلیل تغییر کرده است. ابتدا دوباره آن را بررسی کنید."
                        )
                    }

                    val proposedBytes =
                        preview.proposedContent.toByteArray(Charsets.UTF_8)

                    if (
                        proposedBytes.isEmpty() ||
                        proposedBytes.size > MAX_FILE_SIZE
                    ) {
                        return@withGlobalLock ProjectFileChangeResult.Rejected(
                            "محتوای پیشنهادی خالی یا بیش از حد مجاز است."
                        )
                    }

                    val proposedHash = calculateHash(proposedBytes)

                    val backup = createBackupRecord(
                        root,
                        target,
                        originalBytes,
                        proposedHash
                    ) ?: return@withGlobalLock ProjectFileChangeResult.Rejected(
                        "تهیه نسخه پشتیبان ناموفق بود؛ فایل اصلی تغییر نکرد."
                    )

                    val latestBytes =
                        readLimitedFile(target)
                            ?: return@withGlobalLock ProjectFileChangeResult.Rejected(
                                "خواندن مجدد فایل پیش از اصلاح ناموفق بود."
                            )

                    if (calculateHash(latestBytes) != originalHash) {
                        return@withGlobalLock ProjectFileChangeResult.Rejected(
                            "فایل هنگام آماده‌سازی تغییر کرده است."
                        )
                    }

                    journalEntry = beginRecoveryOperation(
                        root,
                        target,
                        backup,
                        RecoveryOperationType.APPLY_CHANGE
                    ) ?: return@withGlobalLock ProjectFileChangeResult.Rejected(
                        "ثبت عملیات در Recovery Journal ناموفق بود؛ فایل اصلی تغییر نکرد."
                    )

                    val entry = journalEntry!!

                    if (!writeAtomically(target, proposedBytes)) {
                        val removed = removeJournalIfStillOriginal(
                            entry.id,
                            target,
                            originalHash
                        )

                        return@withGlobalLock if (removed) {
                            ProjectFileChangeResult.Rejected(
                                "نوشتن فایل ناموفق بود؛ وضعیت اصلی تأیید شد."
                            )
                        } else {
                            ProjectFileChangeResult.RejectedWithPendingRecovery(
                                "نوشتن فایل ناموفق بود یا وضعیت آن قطعی نیست؛ Journal و نسخه پشتیبان حفظ شدند."
                            )
                        }
                    }

                    val finalBytes = readLimitedFile(target)
                    val verified = finalBytes != null &&
                        calculateHash(finalBytes) == proposedHash

                    if (!verified) {
                        return@withGlobalLock when (
                            rollbackOperation(
                                target,
                                originalBytes,
                                originalHash,
                                entry.id
                            )
                        ) {
                            RollbackResult.RESTORED_AND_JOURNAL_REMOVED ->
                                ProjectFileChangeResult.Rejected(
                                    "بررسی نهایی ناموفق بود؛ نسخه اصلی بازیابی و تأیید شد."
                                )

                            RollbackResult.RESTORED_WITH_PENDING_JOURNAL ->
                                ProjectFileChangeResult.RejectedWithPendingRecovery(
                                    "نسخه اصلی بازیابی و تأیید شد، اما پاک‌سازی Journal ناموفق بود."
                                )

                            RollbackResult.FAILED ->
                                ProjectFileChangeResult.RejectedWithPendingRecovery(
                                    "بررسی نهایی و بازگردانی ناموفق بود؛ وضعیت فایل نیازمند بررسی است و اطلاعات بازیابی حفظ شده‌اند."
                                )
                        }
                    }

                    if (!finishRecoveryOperation(entry.id)) {
                        return@withGlobalLock ProjectFileChangeResult.AppliedWithPendingRecovery(
                            backupPath = backup.backupPath,
                            reason = "تغییر فایل با موفقیت اعمال و تأیید شد، اما پاک‌سازی Recovery Journal ناموفق بود. پروژه تا بررسی مجدد قفل می‌ماند."
                        )
                    }

                    journalEntry = null

                    ProjectFileChangeResult.Applied(
                        backupPath = backup.backupPath
                    )

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (journalEntry != null) {
                        ProjectFileChangeResult.RejectedWithPendingRecovery(
                            e.message ?: "عملیات با خطا مواجه شد و رکورد بازیابی حفظ شده است."
                        )
                    } else {
                        ProjectFileChangeResult.Rejected(
                            e.message ?: "خطای نامشخص هنگام اعمال اصلاح."
                        )
                    }
                }
            }
        }

    suspend fun getProjectHistory(
        project: File
    ): List<ProjectBackupEntry> =
        withContext(Dispatchers.IO) {

            withGlobalLock {
                try {
                    val root = validateProject(project)
                        ?: return@withGlobalLock emptyList()

                    val backupRoot = File(
                        appContext.filesDir,
                        BACKUP_DIRECTORY
                    ).canonicalFile

                    if (!backupRoot.isDirectory) {
                        return@withGlobalLock emptyList()
                    }

                    val prefix = backupRoot.path + File.separator

                    val manifests = backupRoot.listFiles()
                        ?.filter {
                            it.isFile &&
                                it.extension.equals("json", true)
                        } ?: return@withGlobalLock emptyList()

                    val history = mutableListOf<ProjectBackupEntry>()

                    for (manifestFile in manifests) {
                        try {
                            if (!manifestFile.canonicalPath.startsWith(prefix)) {
                                continue
                            }

                            if (
                                !manifestFile.canRead() ||
                                manifestFile.length() > MAX_MANIFEST_SIZE
                            ) {
                                continue
                            }

                            val manifestBytes =
                                AtomicFile(manifestFile).openRead().use {
                                    it.readBytes()
                                }

                            if (manifestBytes.size > MAX_MANIFEST_SIZE) {
                                continue
                            }

                            val manifest = JSONObject(
                                String(manifestBytes, Charsets.UTF_8)
                            )

                            if (
                                manifest.optString("projectPath") != root.path
                            ) {
                                continue
                            }

                            val savedFilePath =
                                manifest.optString("filePath")

                            if (
                                savedFilePath.isBlank() ||
                                !isSafeHistoricalPath(root, savedFilePath)
                            ) {
                                continue
                            }

                            val id = manifest.optString("backupId")

                            val parsedId = try {
                                UUID.fromString(id)
                            } catch (_: Exception) {
                                continue
                            }

                            if (
                                parsedId.toString() != id ||
                                manifestFile.nameWithoutExtension != id
                            ) {
                                continue
                            }

                            val originalHash =
                                manifest.optString("originalHash")

                            val appliedHash =
                                manifest.optString("appliedHash")

                            val createdAt =
                                manifest.optLong("createdAt", 0L)

                            if (
                                !isValidHash(originalHash) ||
                                !isValidHash(appliedHash) ||
                                createdAt <= 0L
                            ) {
                                continue
                            }

                            val backupFile = File(
                                backupRoot,
                                "$id.bak"
                            ).canonicalFile

                            val safe = backupFile.path.startsWith(prefix)
                            val exists = safe &&
                                backupFile.isFile &&
                                backupFile.canRead()

                            val size = if (exists) {
                                backupFile.length()
                            } else {
                                0L
                            }

                            var valid = false

                            if (exists && size <= MAX_FILE_SIZE) {
                                val bytes = readLimitedFile(backupFile)
                                valid = bytes != null &&
                                    calculateHash(bytes) == originalHash
                            }

                            history.add(
                                ProjectBackupEntry(
                                    backupPath = if (safe) {
                                        backupFile.absolutePath
                                    } else {
                                        ""
                                    },
                                    projectPath = root.path,
                                    filePath = savedFilePath,
                                    originalHash = originalHash,
                                    appliedHash = appliedHash,
                                    createdAt = createdAt,
                                    backupSize = size,
                                    isValid = valid
                                )
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // ورودی خراب از تاریخچه کنار گذاشته می‌شود.
                        }
                    }

                    history.sortedByDescending { it.createdAt }

                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

    suspend fun restoreHistoricalBackup(
        project: File,
        file: File,
        backupPath: String,
        ownerConfirmed: Boolean
    ): ProjectFileRestoreResult =
        withContext(Dispatchers.IO) {

            if (!ownerConfirmed) {
                return@withContext ProjectFileRestoreResult.Rejected(
                    "بازگردانی بدون تأیید مالک مجاز نیست."
                )
            }

            withGlobalLock {

                var journalEntry: RecoveryJournalEntry? = null

                try {
                    val root = validateProject(project)
                        ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "مسیر پروژه معتبر نیست."
                        )

                    val target = validateFile(root, file)
                        ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "فایل مقصد معتبر یا قابل نوشتن نیست."
                        )

                    val backupRoot = File(
                        appContext.filesDir,
                        BACKUP_DIRECTORY
                    ).canonicalFile

                    if (!backupRoot.isDirectory) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "پوشه نسخه‌های پشتیبان پیدا نشد."
                        )
                    }

                    val prefix = backupRoot.path + File.separator
                    val historicalBackup = File(backupPath).canonicalFile

                    if (
                        !historicalBackup.path.startsWith(prefix) ||
                        !historicalBackup.isFile ||
                        !historicalBackup.canRead() ||
                        !historicalBackup.extension.equals("bak", true) ||
                        historicalBackup.length() > MAX_FILE_SIZE
                    ) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "مسیر یا اندازه نسخه تاریخی معتبر نیست."
                        )
                    }

                    val id = historicalBackup.nameWithoutExtension

                    val parsedId = try {
                        UUID.fromString(id)
                    } catch (_: Exception) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "شناسه نسخه تاریخی معتبر نیست."
                        )
                    }

                    if (parsedId.toString() != id) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "شناسه نسخه تاریخی معتبر نیست."
                        )
                    }

                    val manifestFile = File(
                        backupRoot,
                        "$id.json"
                    ).canonicalFile

                    if (
                        !manifestFile.path.startsWith(prefix) ||
                        !manifestFile.isFile ||
                        !manifestFile.canRead() ||
                        manifestFile.length() > MAX_MANIFEST_SIZE
                    ) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "اطلاعات نسخه تاریخی معتبر نیست."
                        )
                    }

                    val manifestBytes =
                        AtomicFile(manifestFile).openRead().use {
                            it.readBytes()
                        }

                    if (manifestBytes.size > MAX_MANIFEST_SIZE) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "اندازه اطلاعات نسخه تاریخی بیش از حد مجاز است."
                        )
                    }

                    val manifest = JSONObject(
                        String(manifestBytes, Charsets.UTF_8)
                    )

                    val savedProjectPath =
                        manifest.optString("projectPath")

                    val savedFilePath =
                        manifest.optString("filePath")

                    val originalHash =
                        manifest.optString("originalHash")

                    val appliedHash =
                        manifest.optString("appliedHash")

                    val savedId =
                        manifest.optString("backupId")

                    val createdAt =
                        manifest.optLong("createdAt", 0L)

                    if (
                        savedProjectPath != root.path ||
                        savedFilePath != target.path ||
                        savedId != id ||
                        createdAt <= 0L ||
                        !isSafeHistoricalPath(root, savedFilePath) ||
                        !isValidHash(originalHash) ||
                        !isValidHash(appliedHash)
                    ) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "این نسخه پشتیبان متعلق به این پروژه یا فایل نیست."
                        )
                    }

                    val historicalBytes =
                        readLimitedFile(historicalBackup)
                            ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                                "خواندن نسخه تاریخی ناموفق بود."
                            )

                    if (calculateHash(historicalBytes) != originalHash) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "نسخه تاریخی آسیب‌دیده یا دستکاری شده است."
                        )
                    }

                    val currentBytes =
                        readLimitedFile(target)
                            ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                                "خواندن فایل فعلی ناموفق بود."
                            )

                    val currentHash = calculateHash(currentBytes)

                    if (currentHash == originalHash) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "فایل در حال حاضر دقیقاً همین نسخه را دارد."
                        )
                    }

                    val latestBytes =
                        readLimitedFile(target)
                            ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                                "خواندن مجدد فایل ناموفق بود."
                            )

                    if (calculateHash(latestBytes) != currentHash) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "فایل هنگام آماده‌سازی بازگردانی تغییر کرده است."
                        )
                    }

                    val targetHash = calculateHash(historicalBytes)

                    val safetyBackup = createBackupRecord(
                        root,
                        target,
                        currentBytes,
                        targetHash
                    ) ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                        "تهیه پشتیبان از وضعیت فعلی ناموفق بود."
                    )

                    journalEntry = beginRecoveryOperation(
                        root,
                        target,
                        safetyBackup,
                        RecoveryOperationType.RESTORE_HISTORICAL
                    ) ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                        "ثبت عملیات بازگردانی در Journal ناموفق بود؛ فایل تغییر نکرد."
                    )

                    val entry = journalEntry!!

                    if (!writeAtomically(target, historicalBytes)) {
                        val removed = removeJournalIfStillOriginal(
                            entry.id,
                            target,
                            currentHash
                        )

                        return@withGlobalLock if (removed) {
                            ProjectFileRestoreResult.Rejected(
                                "نوشتن نسخه تاریخی ناموفق بود؛ وضعیت قبلی فایل تأیید شد."
                            )
                        } else {
                            ProjectFileRestoreResult.RejectedWithPendingRecovery(
                                "نوشتن نسخه تاریخی ناموفق بود یا وضعیت فایل قطعی نیست؛ Journal حفظ شد."
                            )
                        }
                    }

                    val finalBytes = readLimitedFile(target)
                    val verified = finalBytes != null &&
                        calculateHash(finalBytes) == targetHash

                    if (!verified) {
                        return@withGlobalLock when (
                            rollbackOperation(
                                target,
                                currentBytes,
                                currentHash,
                                entry.id
                            )
                        ) {
                            RollbackResult.RESTORED_AND_JOURNAL_REMOVED ->
                                ProjectFileRestoreResult.Rejected(
                                    "بررسی بازگردانی ناموفق بود؛ وضعیت قبلی فایل بازیابی و تأیید شد."
                                )

                            RollbackResult.RESTORED_WITH_PENDING_JOURNAL ->
                                ProjectFileRestoreResult.RejectedWithPendingRecovery(
                                    "وضعیت قبلی فایل بازیابی شد، اما پاک‌سازی Journal ناموفق بود."
                                )

                            RollbackResult.FAILED ->
                                ProjectFileRestoreResult.RejectedWithPendingRecovery(
                                    "بازگردانی و بررسی نهایی ناموفق بود؛ وضعیت فایل نیازمند بررسی است."
                                )
                        }
                    }

                    if (!finishRecoveryOperation(entry.id)) {
                        return@withGlobalLock ProjectFileRestoreResult.RestoredWithPendingRecovery(
                            filePath = target.path,
                            reason = "نسخه تاریخی با موفقیت بازگردانی شد، اما پاک‌سازی Recovery Journal ناموفق بود. پروژه تا بررسی مجدد قفل می‌ماند."
                        )
                    }

                    journalEntry = null

                    ProjectFileRestoreResult.Restored(target.path)

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (journalEntry != null) {
                        ProjectFileRestoreResult.RejectedWithPendingRecovery(
                            e.message ?: "عملیات بازگردانی با خطا مواجه شد و Journal حفظ شده است."
                        )
                    } else {
                        ProjectFileRestoreResult.Rejected(
                            e.message ?: "خطای نامشخص هنگام بازگردانی نسخه تاریخی."
                        )
                    }
                }
            }
        }

    suspend fun restoreBackup(
        project: File,
        file: File,
        backupPath: String,
        ownerConfirmed: Boolean
    ): ProjectFileRestoreResult =
        withContext(Dispatchers.IO) {

            if (!ownerConfirmed) {
                return@withContext ProjectFileRestoreResult.Rejected(
                    "بازگردانی بدون تأیید مالک مجاز نیست."
                )
            }

            withGlobalLock {

                var journalEntry: RecoveryJournalEntry? = null

                try {
                    val root = validateProject(project)
                        ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "مسیر پروژه معتبر نیست."
                        )

                    val target = validateFile(root, file)
                        ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "فایل مقصد معتبر یا قابل نوشتن نیست."
                        )

                    val backupRoot = File(
                        appContext.filesDir,
                        BACKUP_DIRECTORY
                    ).canonicalFile

                    if (!backupRoot.isDirectory) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "پوشه نسخه‌های پشتیبان پیدا نشد."
                        )
                    }

                    val prefix = backupRoot.path + File.separator
                    val backupFile = File(backupPath).canonicalFile

                    if (
                        !backupFile.path.startsWith(prefix) ||
                        !backupFile.isFile ||
                        !backupFile.canRead() ||
                        !backupFile.extension.equals("bak", true) ||
                        backupFile.length() > MAX_FILE_SIZE
                    ) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "مسیر یا اندازه نسخه پشتیبان معتبر نیست."
                        )
                    }

                    val id = backupFile.nameWithoutExtension

                    val parsedId = try {
                        UUID.fromString(id)
                    } catch (_: Exception) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "شناسه نسخه پشتیبان معتبر نیست."
                        )
                    }

                    if (parsedId.toString() != id) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "شناسه نسخه پشتیبان معتبر نیست."
                        )
                    }

                    val manifestFile = File(
                        backupRoot,
                        "$id.json"
                    ).canonicalFile

                    if (
                        !manifestFile.path.startsWith(prefix) ||
                        !manifestFile.isFile ||
                        !manifestFile.canRead() ||
                        manifestFile.length() > MAX_MANIFEST_SIZE
                    ) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "اطلاعات نسخه پشتیبان معتبر نیست."
                        )
                    }

                    val manifestBytes =
                        AtomicFile(manifestFile).openRead().use {
                            it.readBytes()
                        }

                    if (manifestBytes.size > MAX_MANIFEST_SIZE) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "اندازه اطلاعات نسخه پشتیبان بیش از حد مجاز است."
                        )
                    }

                    val manifest = JSONObject(
                        String(manifestBytes, Charsets.UTF_8)
                    )

                    val savedProjectPath =
                        manifest.optString("projectPath")

                    val savedFilePath =
                        manifest.optString("filePath")

                    val originalHash =
                        manifest.optString("originalHash")

                    val appliedHash =
                        manifest.optString("appliedHash")

                    val savedId =
                        manifest.optString("backupId")

                    if (
                        savedProjectPath != root.path ||
                        savedFilePath != target.path ||
                        savedId != id ||
                        !isValidHash(originalHash) ||
                        !isValidHash(appliedHash) ||
                        !isSafeHistoricalPath(root, savedFilePath)
                    ) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "این نسخه پشتیبان متعلق به این فایل نیست."
                        )
                    }

                    val currentBytes =
                        readLimitedFile(target)
                            ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                                "خواندن فایل فعلی ناموفق بود."
                            )

                    val currentHash = calculateHash(currentBytes)

                    if (currentHash != appliedHash) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "فایل پس از اصلاح تغییر کرده است. برای جلوگیری از حذف تغییرات جدید، بازگردانی متوقف شد."
                        )
                    }

                    val backupBytes =
                        readLimitedFile(backupFile)
                            ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                                "خواندن نسخه پشتیبان ناموفق بود."
                            )

                    if (calculateHash(backupBytes) != originalHash) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "نسخه پشتیبان آسیب‌دیده یا دستکاری شده است."
                        )
                    }

                    val latestBytes =
                        readLimitedFile(target)
                            ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                                "خواندن مجدد فایل ناموفق بود."
                            )

                    if (calculateHash(latestBytes) != currentHash) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "فایل هنگام آماده‌سازی بازگردانی تغییر کرده است."
                        )
                    }

                    val safetyBackup = createBackupRecord(
                        root,
                        target,
                        currentBytes,
                        originalHash
                    ) ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                        "تهیه پشتیبان از وضعیت فعلی ناموفق بود."
                    )

                    journalEntry = beginRecoveryOperation(
                        root,
                        target,
                        safetyBackup,
                        RecoveryOperationType.RESTORE_BACKUP
                    ) ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                        "ثبت عملیات بازگردانی در Journal ناموفق بود؛ فایل تغییر نکرد."
                    )

                    val entry = journalEntry!!

                    if (!writeAtomically(target, backupBytes)) {
                        val removed = removeJournalIfStillOriginal(
                            entry.id,
                            target,
                            currentHash
                        )

                        return@withGlobalLock if (removed) {
                            ProjectFileRestoreResult.Rejected(
                                "بازگردانی ناموفق بود؛ وضعیت قبلی فایل تأیید شد."
                            )
                        } else {
                            ProjectFileRestoreResult.RejectedWithPendingRecovery(
                                "بازگردانی ناموفق بود یا وضعیت فایل قطعی نیست؛ Journal حفظ شد."
                            )
                        }
                    }

                    val finalBytes = readLimitedFile(target)
                    val verified = finalBytes != null &&
                        calculateHash(finalBytes) == originalHash

                    if (!verified) {
                        return@withGlobalLock when (
                            rollbackOperation(
                                target,
                                currentBytes,
                                currentHash,
                                entry.id
                            )
                        ) {
                            RollbackResult.RESTORED_AND_JOURNAL_REMOVED ->
                                ProjectFileRestoreResult.Rejected(
                                    "بررسی بازگردانی ناموفق بود؛ نسخه قبلی بازیابی و تأیید شد."
                                )

                            RollbackResult.RESTORED_WITH_PENDING_JOURNAL ->
                                ProjectFileRestoreResult.RejectedWithPendingRecovery(
                                    "نسخه قبلی بازیابی شد، اما پاک‌سازی Journal ناموفق بود."
                                )

                            RollbackResult.FAILED ->
                                ProjectFileRestoreResult.RejectedWithPendingRecovery(
                                    "بررسی بازگردانی ناموفق بود و وضعیت فایل قطعی نیست؛ اطلاعات بازیابی حفظ شده‌اند."
                                )
                        }
                    }

                    if (!finishRecoveryOperation(entry.id)) {
                        return@withGlobalLock ProjectFileRestoreResult.RestoredWithPendingRecovery(
                            filePath = target.path,
                            reason = "فایل با موفقیت بازگردانی شد، اما پاک‌سازی Recovery Journal ناموفق بود. پروژه تا بررسی مجدد قفل می‌ماند."
                        )
                    }

                    journalEntry = null

                    ProjectFileRestoreResult.Restored(target.path)

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (journalEntry != null) {
                        ProjectFileRestoreResult.RejectedWithPendingRecovery(
                            e.message ?: "عملیات بازگردانی با خطا مواجه شد و Journal حفظ شده است."
                        )
                    } else {
                        ProjectFileRestoreResult.Rejected(
                            e.message ?: "خطای نامشخص هنگام بازگردانی."
                        )
                    }
                }
            }
        }

    suspend fun recoverPendingOperations(
        project: File? = null
    ): List<String> =
        withContext(Dispatchers.IO) {

            withGlobalLock {

                val messages = mutableListOf<String>()
                val problems = mutableListOf<String>()

                try {
                    if (project == null) {
                        throw ProjectRecoveryException(
                            listOf(
                                "برای بررسی عملیات بازیابی، پروژه مشخص نشده است."
                            )
                        )
                    }

                    val root = validateProject(project)
                        ?: throw ProjectRecoveryException(
                            listOf(
                                "مسیر پروژه برای بررسی بازیابی معتبر نیست."
                            )
                        )

                    val projectPath = root.canonicalPath

                    val entries =
                        recoveryJournal.getPendingEntries(projectPath)

                    if (entries.isEmpty()) {
                        return@withGlobalLock emptyList()
                    }

                    for (entry in entries) {
                        try {
                            if (entry.projectPath != projectPath) {
                                problems.add(
                                    "مسیر پروژه یک عملیات بازیابی ناسازگار است؛ اطلاعات آن حفظ شد."
                                )
                                continue
                            }

                            val target =
                                File(entry.filePath).canonicalFile

                            if (
                                target.path != entry.filePath ||
                                !isSafeHistoricalPath(root, entry.filePath) ||
                                !target.isFile
                            ) {
                                problems.add(
                                    "مسیر فایل یک عملیات بازیابی معتبر نیست؛ اطلاعات آن حفظ شد."
                                )
                                continue
                            }

                            val backupRoot = File(
                                appContext.filesDir,
                                BACKUP_DIRECTORY
                            ).canonicalFile

                            if (!backupRoot.isDirectory) {
                                problems.add(
                                    "پوشه نسخه‌های پشتیبان پیدا نشد؛ اطلاعات عملیات حفظ شد."
                                )
                                continue
                            }

                            val prefix =
                                backupRoot.path + File.separator

                            val backupFile =
                                File(entry.backupPath).canonicalFile

                            if (
                                backupFile.path != entry.backupPath ||
                                !backupFile.path.startsWith(prefix) ||
                                !backupFile.isFile ||
                                !backupFile.canRead() ||
                                !backupFile.extension.equals("bak", true) ||
                                backupFile.length() > MAX_FILE_SIZE
                            ) {
                                problems.add(
                                    "نسخه پشتیبان یک عملیات معتبر نیست؛ اطلاعات آن حفظ شد."
                                )
                                continue
                            }

                            if (
                                !isValidHash(entry.beforeHash) ||
                                !isValidHash(entry.targetHash)
                            ) {
                                problems.add(
                                    "هش‌های ثبت‌شده در یک عملیات معتبر نیستند؛ اطلاعات آن حفظ شد."
                                )
                                continue
                            }

                            val backupBytes =
                                readLimitedFile(backupFile)

                            if (
                                backupBytes == null ||
                                calculateHash(backupBytes) != entry.beforeHash
                            ) {
                                problems.add(
                                    "نسخه پشتیبان با هش وضعیت قبلی مطابقت ندارد؛ اطلاعات آن حفظ شد."
                                )
                                continue
                            }

                            when (
                                val check =
                                    recoveryJournal.inspectOperation(entry)
                            ) {
                                is RecoveryCheckResult.Resolved -> {
                                    if (
                                        recoveryJournal.remove(
                                            check.operationId
                                        )
                                    ) {
                                        messages.add(
                                            "وضعیت یک عملیات بررسی و رکورد بازیابی آن پاک‌سازی شد."
                                        )
                                    } else {
                                        problems.add(
                                            "وضعیت فایل مشخص است، اما پاک‌سازی رکورد بازیابی ناموفق بود."
                                        )
                                    }
                                }

                                is RecoveryCheckResult.Pending -> {
                                    problems.add(
                                        "وضعیت یک فایل با نسخه قبل و نسخه پیشنهادی مطابقت ندارد؛ هیچ تغییری در فایل ایجاد نشد."
                                    )
                                }

                                is RecoveryCheckResult.Invalid -> {
                                    problems.add(
                                        "اطلاعات یک عملیات بازیابی معتبر نیست؛ رکورد آن حفظ شد."
                                    )
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            problems.add(
                                "بررسی یکی از عملیات بازیابی ناموفق بود؛ اطلاعات آن حفظ شد: " +
                                    (e.message ?: "خطای نامشخص")
                            )
                        }
                    }

                    if (problems.isNotEmpty()) {
                        throw ProjectRecoveryException(
                            buildList {
                                add("بازیابی پروژه با موفقیت کامل نشد.")
                                addAll(problems)
                                add(
                                    "هیچ فایل مشکوکی به‌صورت خودکار تغییر نکرده است. اطلاعات Journal و نسخه‌های پشتیبان حفظ شده‌اند."
                                )
                                add(
                                    "تا بررسی مجدد، ادامه عملیات روی این پروژه مجاز نیست."
                                )
                            }
                        )
                    }

                    messages
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ProjectRecoveryException) {
                    throw e
                } catch (e: Exception) {
                    throw ProjectRecoveryException(
                        listOf(
                            "بررسی Recovery Journal با خطا مواجه شد.",
                            e.message ?: "خطای نامشخص هنگام بررسی بازیابی.",
                            "اطلاعات بازیابی حفظ شده‌اند؛ ادامه عملیات پروژه تا بررسی مجدد مجاز نیست."
                        )
                    )
                }
            }
        }
}
