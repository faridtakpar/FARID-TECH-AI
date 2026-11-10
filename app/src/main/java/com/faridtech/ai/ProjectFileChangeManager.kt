
package com.faridtech.ai

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
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

    data class Rejected(
        val reason: String
    ) : ProjectFileChangeResult()
}

sealed class ProjectFileRestoreResult {

    data class Restored(
        val filePath: String
    ) : ProjectFileRestoreResult()

    data class Rejected(
        val reason: String
    ) : ProjectFileRestoreResult()
}

/**
 * این استثنا زمانی صادر می‌شود که بررسی Recovery Journal
 * نتواند تمام عملیات باقی‌مانده را با اطمینان تعیین تکلیف کند.
 *
 * در این وضعیت، صفحه پروژه نباید اجازه ادامه عملیات بدهد.
 */
class ProjectRecoveryException(
    val recoveryMessages: List<String>
) : Exception(
    recoveryMessages.joinToString("\n")
)

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
            "keystore",
            "jks",
            "pem",
            "p12",
            "pfx"
        )

        // قفل مشترک بین تمام نمونه‌های این کلاس
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

    // -------------------------------------------------
    // قفل امن برای عملیات suspend
    // -------------------------------------------------

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

    // -------------------------------------------------
    // اعتبارسنجی مسیر پروژه
    // -------------------------------------------------

    private fun validateProject(project: File): File? {
        return try {
            val projectsRoot = File(
                appContext.filesDir,
                PROJECTS_DIRECTORY
            ).canonicalFile

            val projectRoot = project.canonicalFile

            val allowedPrefix =
                projectsRoot.path + File.separator

            if (!projectsRoot.isDirectory) return null
            if (!projectRoot.isDirectory) return null

            if (!projectRoot.path.startsWith(allowedPrefix)) {
                return null
            }

            projectRoot

        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------
    // اعتبارسنجی فایل
    // -------------------------------------------------

    private fun validateFile(
        projectRoot: File,
        file: File
    ): File? {
        return try {
            val root = projectRoot.canonicalFile
            val target = file.canonicalFile

            val rootPrefix =
                root.path + File.separator

            if (!target.path.startsWith(rootPrefix)) {
                return null
            }

            if (!target.isFile || !target.canRead()) {
                return null
            }

            if (!target.canWrite()) {
                return null
            }

            if (target.length() > MAX_FILE_SIZE) {
                return null
            }

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

    // -------------------------------------------------
    // اعتبارسنجی مسیر فایل در تاریخچه و بازیابی
    // -------------------------------------------------

    private fun isSafeHistoricalPath(
        projectRoot: File,
        filePath: String
    ): Boolean {
        return try {
            val root = projectRoot.canonicalFile
            val target = File(filePath).canonicalFile

            val rootPrefix =
                root.path + File.separator

            if (!target.path.startsWith(rootPrefix)) {
                return false
            }

            val name = target.name.lowercase()
            val extension = target.extension.lowercase()

            name !in BLOCKED_NAMES &&
                    extension !in BLOCKED_EXTENSIONS &&
                    extension in ALLOWED_EXTENSIONS

        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------
    // محاسبه هش فایل
    // -------------------------------------------------

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

    // -------------------------------------------------
    // خواندن کنترل‌شده فایل
    // -------------------------------------------------

    private fun readLimitedFile(file: File): ByteArray? {
        return try {
            if (!file.exists() || !file.isFile || !file.canRead()) {
                return null
            }

            if (file.length() > MAX_FILE_SIZE) {
                return null
            }

            val atomicFile = AtomicFile(file)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)

            var totalBytes = 0L

            atomicFile.openRead().use { input ->

                while (true) {
                    val count = input.read(buffer)

                    if (count == -1) {
                        break
                    }

                    totalBytes += count

                    if (totalBytes > MAX_FILE_SIZE) {
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

    // -------------------------------------------------
    // نوشتن اتمیک فایل
    // -------------------------------------------------

    private fun writeAtomically(
        target: File,
        bytes: ByteArray
    ): Boolean {

        if (bytes.size > MAX_FILE_SIZE) {
            return false
        }

        val atomicFile = AtomicFile(target)

        var output: FileOutputStream? = null

        return try {
            val parent = target.parentFile
                ?: return false

            if (!parent.isDirectory) {
                return false
            }

            val stream = atomicFile.startWrite()

            output = stream

            stream.write(bytes)
            stream.fd.sync()

            atomicFile.finishWrite(stream)

            output = null

            true

        } catch (_: Exception) {

            output?.let { stream ->
                try {
                    atomicFile.failWrite(stream)
                } catch (_: Exception) {
                }
            }

            false
        }
    }

    // -------------------------------------------------
    // ذخیره نسخه پشتیبان
    // -------------------------------------------------

    private fun writeBackup(
        backupFile: File,
        originalBytes: ByteArray
    ): Boolean {

        if (originalBytes.size > MAX_FILE_SIZE) {
            return false
        }

        return writeAtomically(
            backupFile,
            originalBytes
        )
    }

    // -------------------------------------------------
    // ذخیره Manifest
    // -------------------------------------------------

    private fun writeManifest(
        manifestFile: File,
        json: JSONObject
    ): Boolean {

        return try {
            val bytes = json.toString(2)
                .toByteArray(Charsets.UTF_8)

            if (bytes.size > MAX_MANIFEST_SIZE) {
                return false
            }

            writeAtomically(
                manifestFile,
                bytes
            )

        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------
    // ساخت نسخه پشتیبان از وضعیت فعلی
    // -------------------------------------------------

    private fun createBackupRecord(
        projectRoot: File,
        target: File,
        originalBytes: ByteArray,
        appliedHash: String
    ): CreatedBackup? {

        return try {
            if (originalBytes.size > MAX_FILE_SIZE) {
                return null
            }

            if (!isValidHash(appliedHash)) {
                return null
            }

            val backupRoot = File(
                appContext.filesDir,
                BACKUP_DIRECTORY
            )

            if (
                !backupRoot.exists() &&
                !backupRoot.mkdirs()
            ) {
                return null
            }

            val canonicalBackupRoot =
                backupRoot.canonicalFile

            if (!canonicalBackupRoot.isDirectory) {
                return null
            }

            val backupId = UUID.randomUUID().toString()

            val backupFile = File(
                canonicalBackupRoot,
                "$backupId.bak"
            ).canonicalFile

            val manifestFile = File(
                canonicalBackupRoot,
                "$backupId.json"
            ).canonicalFile

            val backupPrefix =
                canonicalBackupRoot.path + File.separator

            if (
                !backupFile.path.startsWith(backupPrefix) ||
                !manifestFile.path.startsWith(backupPrefix)
            ) {
                return null
            }

            val originalHash =
                calculateHash(originalBytes)

            if (!writeBackup(backupFile, originalBytes)) {
                return null
            }

            val manifest = JSONObject().apply {
                put("projectPath", projectRoot.path)
                put("filePath", target.path)
                put("originalHash", originalHash)
                put("appliedHash", appliedHash)
                put("backupId", backupId)
                put(
                    "createdAt",
                    System.currentTimeMillis()
                )
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

    // -------------------------------------------------
    // ثبت عملیات در Recovery Journal
    // -------------------------------------------------

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

    // -------------------------------------------------
    // ثبت پایان موفق عملیات
    // -------------------------------------------------

    private suspend fun finishRecoveryOperation(
        operationId: String
    ): Boolean {

        return try {
            val committed =
                recoveryJournal.markCommitted(operationId)

            if (!committed) {
                return false
            }

            recoveryJournal.remove(operationId)

        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------
    // پاک‌کردن Journal فقط در صورت تأیید وضعیت اصلی
    // -------------------------------------------------

    private suspend fun removeJournalIfStillOriginal(
        operationId: String,
        target: File,
        beforeHash: String
    ): Boolean {

        return try {
            val currentBytes = readLimitedFile(target)
                ?: return false

            if (calculateHash(currentBytes) != beforeHash) {
                return false
            }

            recoveryJournal.remove(operationId)

        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------
    // تلاش برای بازگرداندن وضعیت قبلی
    // -------------------------------------------------

    private suspend fun rollbackOperation(
        target: File,
        originalBytes: ByteArray,
        beforeHash: String,
        operationId: String?
    ): Boolean {

        return try {
            val rollbackWritten = writeAtomically(
                target,
                originalBytes
            )

            if (!rollbackWritten) {
                return false
            }

            val rollbackBytes = readLimitedFile(target)
                ?: return false

            val rollbackVerified =
                calculateHash(rollbackBytes) == beforeHash

            if (!rollbackVerified) {
                return false
            }

            if (operationId != null) {
                removeJournalIfStillOriginal(
                    operationId,
                    target,
                    beforeHash
                )
            }

            true

        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------
    // آماده‌سازی اصلاح فایل
    // -------------------------------------------------

    suspend fun prepareChange(
        project: File,
        file: File,
        proposedContent: String
    ): ProjectFileChangePreview? =
        withContext(Dispatchers.IO) {

            try {
                val projectRoot = validateProject(project)
                    ?: return@withContext null

                val target = validateFile(
                    projectRoot,
                    file
                ) ?: return@withContext null

                val proposedBytes =
                    proposedContent.toByteArray(Charsets.UTF_8)

                if (proposedBytes.isEmpty()) {
                    return@withContext null
                }

                if (proposedBytes.size > MAX_FILE_SIZE) {
                    return@withContext null
                }

                val originalBytes = readLimitedFile(target)
                    ?: return@withContext null

                val originalContent = String(
                    originalBytes,
                    Charsets.UTF_8
                )

                if (originalContent == proposedContent) {
                    return@withContext null
                }

                ProjectFileChangePreview(
                    projectPath = projectRoot.path,
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

    // -------------------------------------------------
    // اعمال تغییرات با تأیید مالک و Recovery Journal
    // -------------------------------------------------

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

                try {
                    val projectRoot = validateProject(
                        File(preview.projectPath)
                    ) ?: return@withGlobalLock ProjectFileChangeResult.Rejected(
                        "مسیر پروژه معتبر نیست."
                    )

                    val target = validateFile(
                        projectRoot,
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

                    val originalBytes = readLimitedFile(target)
                        ?: return@withGlobalLock ProjectFileChangeResult.Rejected(
                            "خواندن فایل اصلی ناموفق بود یا اندازه آن بیش از حد مجاز است."
                        )

                    val originalHash =
                        calculateHash(originalBytes)

                    if (originalHash != preview.originalHash) {
                        return@withGlobalLock ProjectFileChangeResult.Rejected(
                            "فایل از زمان تحلیل تغییر کرده است. ابتدا دوباره آن را بررسی کنید."
                        )
                    }

                    val proposedBytes =
                        preview.proposedContent.toByteArray(
                            Charsets.UTF_8
                        )

                    if (
                        proposedBytes.isEmpty() ||
                        proposedBytes.size > MAX_FILE_SIZE
                    ) {
                        return@withGlobalLock ProjectFileChangeResult.Rejected(
                            "محتوای پیشنهادی خالی یا بیش از حد مجاز است."
                        )
                    }

                    val proposedHash =
                        calculateHash(proposedBytes)

                    val backup = createBackupRecord(
                        projectRoot = projectRoot,
                        target = target,
                        originalBytes = originalBytes,
                        appliedHash = proposedHash
                    ) ?: return@withGlobalLock ProjectFileChangeResult.Rejected(
                        "تهیه نسخه پشتیبان و ثبت اطلاعات آن ناموفق بود؛ فایل اصلی تغییر نکرد."
                    )

                    val latestBytes = readLimitedFile(target)
                        ?: return@withGlobalLock ProjectFileChangeResult.Rejected(
                            "خواندن مجدد فایل پیش از اصلاح ناموفق بود."
                        )

                    if (calculateHash(latestBytes) != originalHash) {
                        return@withGlobalLock ProjectFileChangeResult.Rejected(
                            "فایل هنگام آماده‌سازی تغییر کرده است. اصلاح متوقف شد."
                        )
                    }

                    val journalEntry = beginRecoveryOperation(
                        projectRoot = projectRoot,
                        target = target,
                        backup = backup,
                        operationType = RecoveryOperationType.APPLY_CHANGE
                    ) ?: return@withGlobalLock ProjectFileChangeResult.Rejected(
                        "ثبت عملیات در Recovery Journal ناموفق بود؛ فایل اصلی تغییر نکرد."
                    )

                    val written = writeAtomically(
                        target,
                        proposedBytes
                    )

                    if (!written) {
                        val removed = removeJournalIfStillOriginal(
                            journalEntry.id,
                            target,
                            originalHash
                        )

                        return@withGlobalLock ProjectFileChangeResult.Rejected(
                            if (removed) {
                                "نوشتن فایل ناموفق بود؛ وضعیت اصلی تأیید شد و نسخه پشتیبان حفظ شده است."
                            } else {
                                "نوشتن فایل ناموفق بود یا وضعیت آن قطعی نیست؛ اطلاعات بازیابی حفظ شده است."
                            }
                        )
                    }

                    val finalBytes = readLimitedFile(target)

                    val verified =
                        finalBytes != null &&
                                calculateHash(finalBytes) == proposedHash

                    if (!verified) {
                        val rollbackSucceeded = rollbackOperation(
                            target = target,
                            originalBytes = originalBytes,
                            beforeHash = originalHash,
                            operationId = journalEntry.id
                        )

                        return@withGlobalLock ProjectFileChangeResult.Rejected(
                            if (rollbackSucceeded) {
                                "بررسی نهایی فایل ناموفق بود؛ نسخه اصلی بازیابی و تأیید شد."
                            } else {
                                "بررسی نهایی فایل ناموفق بود و وضعیت بازیابی قطعی نیست؛ اطلاعات Journal و نسخه پشتیبان حفظ شده‌اند."
                            }
                        )
                    }

                    // اگر ثبت COMMITTED یا حذف Journal شکست بخورد،
                    // Journal باقی می‌ماند تا در اجرای بعدی بررسی شود.
                    finishRecoveryOperation(journalEntry.id)

                    ProjectFileChangeResult.Applied(
                        backupPath = backup.backupPath
                    )

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ProjectFileChangeResult.Rejected(
                        e.message ?: "خطای نامشخص هنگام اعمال اصلاح."
                    )
                }
            }
        }

    // -------------------------------------------------
    // دریافت تاریخچه نسخه‌های پشتیبان پروژه
    // -------------------------------------------------

    suspend fun getProjectHistory(
        project: File
    ): List<ProjectBackupEntry> =
        withContext(Dispatchers.IO) {

            withGlobalLock {

                try {
                    val projectRoot = validateProject(project)
                        ?: return@withGlobalLock emptyList()

                    val backupRoot = File(
                        appContext.filesDir,
                        BACKUP_DIRECTORY
                    ).canonicalFile

                    if (!backupRoot.isDirectory) {
                        return@withGlobalLock emptyList()
                    }

                    val backupPrefix =
                        backupRoot.path + File.separator

                    val manifests = backupRoot.listFiles()
                        ?.filter {
                            it.isFile &&
                                    it.extension.equals(
                                        "json",
                                        ignoreCase = true
                                    )
                        }
                        ?: return@withGlobalLock emptyList()

                    val history = mutableListOf<ProjectBackupEntry>()

                    for (manifestFile in manifests) {
                        try {
                            if (
                                !manifestFile.canonicalPath.startsWith(
                                    backupPrefix
                                )
                            ) {
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
                                String(
                                    manifestBytes,
                                    Charsets.UTF_8
                                )
                            )

                            val savedProjectPath =
                                manifest.optString("projectPath")

                            if (savedProjectPath != projectRoot.path) {
                                continue
                            }

                            val savedFilePath =
                                manifest.optString("filePath")

                            if (
                                savedFilePath.isBlank() ||
                                !isSafeHistoricalPath(
                                    projectRoot,
                                    savedFilePath
                                )
                            ) {
                                continue
                            }

                            val backupId =
                                manifest.optString("backupId")

                            if (backupId.isBlank()) {
                                continue
                            }

                            val parsedId = try {
                                UUID.fromString(backupId)
                            } catch (_: Exception) {
                                continue
                            }

                            if (parsedId.toString() != backupId) {
                                continue
                            }

                            if (
                                manifestFile.nameWithoutExtension != backupId
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
                                "$backupId.bak"
                            ).canonicalFile

                            val backupPathIsSafe =
                                backupFile.path.startsWith(backupPrefix)

                            val backupExists =
                                backupPathIsSafe &&
                                        backupFile.isFile &&
                                        backupFile.canRead()

                            val backupSize = if (backupExists) {
                                backupFile.length()
                            } else {
                                0L
                            }

                            var isValid = false

                            if (
                                backupExists &&
                                backupSize <= MAX_FILE_SIZE
                            ) {
                                val backupBytes =
                                    readLimitedFile(backupFile)

                                isValid =
                                    backupBytes != null &&
                                            calculateHash(backupBytes) ==
                                            originalHash
                            }

                            history.add(
                                ProjectBackupEntry(
                                    backupPath = if (backupPathIsSafe) {
                                        backupFile.absolutePath
                                    } else {
                                        ""
                                    },
                                    projectPath = projectRoot.path,
                                    filePath = savedFilePath,
                                    originalHash = originalHash,
                                    appliedHash = appliedHash,
                                    createdAt = createdAt,
                                    backupSize = backupSize,
                                    isValid = isValid
                                )
                            )

                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // خرابی یک ورودی نباید کل تاریخچه را متوقف کند.
                        }
                    }

                    history.sortedByDescending {
                        it.createdAt
                    }

                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

    // -------------------------------------------------
    // بازگردانی نسخه تاریخی با Recovery Journal
    // -------------------------------------------------

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

                try {
                    val projectRoot = validateProject(project)
                        ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "مسیر پروژه معتبر نیست."
                        )

                    val target = validateFile(
                        projectRoot,
                        file
                    ) ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
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

                    val backupPrefix =
                        backupRoot.path + File.separator

                    val historicalBackup =
                        File(backupPath).canonicalFile

                    if (
                        !historicalBackup.path.startsWith(backupPrefix) ||
                        !historicalBackup.isFile ||
                        !historicalBackup.canRead() ||
                        !historicalBackup.extension.equals(
                            "bak",
                            ignoreCase = true
                        ) ||
                        historicalBackup.length() > MAX_FILE_SIZE
                    ) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "مسیر یا اندازه نسخه تاریخی معتبر نیست."
                        )
                    }

                    val historicalId =
                        historicalBackup.nameWithoutExtension

                    val parsedId = try {
                        UUID.fromString(historicalId)
                    } catch (_: Exception) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "شناسه نسخه تاریخی معتبر نیست."
                        )
                    }

                    if (parsedId.toString() != historicalId) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "شناسه نسخه تاریخی معتبر نیست."
                        )
                    }

                    val historicalManifest = File(
                        backupRoot,
                        "$historicalId.json"
                    ).canonicalFile

                    if (
                        !historicalManifest.path.startsWith(backupPrefix) ||
                        !historicalManifest.isFile ||
                        !historicalManifest.canRead() ||
                        historicalManifest.length() > MAX_MANIFEST_SIZE
                    ) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "اطلاعات نسخه تاریخی پیدا نشد یا معتبر نیست."
                        )
                    }

                    val manifestBytes =
                        AtomicFile(historicalManifest).openRead().use {
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

                    val savedBackupId =
                        manifest.optString("backupId")

                    val createdAt =
                        manifest.optLong("createdAt", 0L)

                    if (
                        savedProjectPath != projectRoot.path ||
                        savedFilePath != target.path ||
                        savedBackupId != historicalId ||
                        createdAt <= 0L ||
                        !isSafeHistoricalPath(
                            projectRoot,
                            savedFilePath
                        ) ||
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
                                "خواندن نسخه تاریخی ناموفق بود یا اندازه آن بیش از حد مجاز است."
                            )

                    if (
                        calculateHash(historicalBytes) != originalHash
                    ) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "نسخه تاریخی آسیب‌دیده یا دستکاری شده است."
                        )
                    }

                    val currentBytes = readLimitedFile(target)
                        ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "خواندن فایل فعلی ناموفق بود یا اندازه آن بیش از حد مجاز است."
                        )

                    val currentHash =
                        calculateHash(currentBytes)

                    if (currentHash == originalHash) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "فایل در حال حاضر دقیقاً همین نسخه را دارد."
                        )
                    }

                    val latestBytes = readLimitedFile(target)
                        ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "خواندن مجدد فایل قبل از بازگردانی ناموفق بود."
                        )

                    if (calculateHash(latestBytes) != currentHash) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "فایل هنگام آماده‌سازی بازگردانی تغییر کرده است."
                        )
                    }

                    val targetHash =
                        calculateHash(historicalBytes)

                    val safetyBackup = createBackupRecord(
                        projectRoot = projectRoot,
                        target = target,
                        originalBytes = currentBytes,
                        appliedHash = targetHash
                    ) ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                        "تهیه پشتیبان از وضعیت فعلی ناموفق بود؛ بازگردانی انجام نشد."
                    )

                    val journalEntry = beginRecoveryOperation(
                        projectRoot = projectRoot,
                        target = target,
                        backup = safetyBackup,
                        operationType = RecoveryOperationType.RESTORE_HISTORICAL
                    ) ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                        "ثبت عملیات بازگردانی در Recovery Journal ناموفق بود؛ فایل اصلی تغییر نکرد."
                    )

                    val restored = writeAtomically(
                        target,
                        historicalBytes
                    )

                    if (!restored) {
                        val removed = removeJournalIfStillOriginal(
                            journalEntry.id,
                            target,
                            currentHash
                        )

                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            if (removed) {
                                "نوشتن نسخه تاریخی ناموفق بود؛ وضعیت قبلی فایل تأیید شد."
                            } else {
                                "نوشتن نسخه تاریخی ناموفق بود یا وضعیت فایل قطعی نیست؛ اطلاعات بازیابی حفظ شده است."
                            }
                        )
                    }

                    val finalBytes = readLimitedFile(target)

                    val restoreVerified =
                        finalBytes != null &&
                                calculateHash(finalBytes) == targetHash

                    if (!restoreVerified) {
                        val rollbackSucceeded = rollbackOperation(
                            target = target,
                            originalBytes = currentBytes,
                            beforeHash = currentHash,
                            operationId = journalEntry.id
                        )

                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            if (rollbackSucceeded) {
                                "بررسی بازگردانی ناموفق بود؛ وضعیت قبلی فایل بازیابی و تأیید شد."
                            } else {
                                "بررسی بازگردانی ناموفق بود و وضعیت فایل قطعی نیست؛ Journal و نسخه پشتیبان حفظ شده‌اند."
                            }
                        )
                    }

                    finishRecoveryOperation(journalEntry.id)

                    ProjectFileRestoreResult.Restored(
                        filePath = target.path
                    )

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ProjectFileRestoreResult.Rejected(
                        e.message ?: "خطای نامشخص هنگام بازگردانی نسخه تاریخی."
                    )
                }
            }
        }

    // -------------------------------------------------
    // بازگردانی امن نسخه پشتیبان قبلی با Recovery Journal
    // -------------------------------------------------

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

                try {
                    val projectRoot = validateProject(project)
                        ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "مسیر پروژه معتبر نیست."
                        )

                    val target = validateFile(
                        projectRoot,
                        file
                    ) ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
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

                    val backupPrefix =
                        backupRoot.path + File.separator

                    val backupFile = File(backupPath).canonicalFile

                    if (
                        !backupFile.path.startsWith(backupPrefix) ||
                        !backupFile.isFile ||
                        !backupFile.canRead() ||
                        !backupFile.extension.equals(
                            "bak",
                            ignoreCase = true
                        ) ||
                        backupFile.length() > MAX_FILE_SIZE
                    ) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "مسیر یا اندازه نسخه پشتیبان معتبر نیست."
                        )
                    }

                    val backupId =
                        backupFile.nameWithoutExtension

                    val parsedId = try {
                        UUID.fromString(backupId)
                    } catch (_: Exception) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "شناسه نسخه پشتیبان معتبر نیست."
                        )
                    }

                    if (parsedId.toString() != backupId) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "شناسه نسخه پشتیبان معتبر نیست."
                        )
                    }

                    val manifestFile = File(
                        backupRoot,
                        "$backupId.json"
                    ).canonicalFile

                    if (
                        !manifestFile.path.startsWith(backupPrefix) ||
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

                    val savedBackupId =
                        manifest.optString("backupId")

                    if (
                        savedProjectPath != projectRoot.path ||
                        savedFilePath != target.path ||
                        savedBackupId != backupId ||
                        !isValidHash(originalHash) ||
                        !isValidHash(appliedHash) ||
                        !isSafeHistoricalPath(
                            projectRoot,
                            savedFilePath
                        )
                    ) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "این نسخه پشتیبان متعلق به این فایل نیست."
                        )
                    }

                    val currentBytes = readLimitedFile(target)
                        ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "خواندن فایل فعلی ناموفق بود یا اندازه آن بیش از حد مجاز است."
                        )

                    val currentHash =
                        calculateHash(currentBytes)

                    if (currentHash != appliedHash) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "فایل پس از اصلاح تغییر کرده است. برای جلوگیری از حذف تغییرات جدید، بازگردانی متوقف شد."
                        )
                    }

                    val backupBytes = readLimitedFile(backupFile)
                        ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "خواندن نسخه پشتیبان ناموفق بود یا اندازه آن بیش از حد مجاز است."
                        )

                    if (calculateHash(backupBytes) != originalHash) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "نسخه پشتیبان آسیب‌دیده یا دستکاری شده است."
                        )
                    }

                    val latestBytes = readLimitedFile(target)
                        ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "خواندن مجدد فایل قبل از بازگردانی ناموفق بود."
                        )

                    if (calculateHash(latestBytes) != currentHash) {
                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            "فایل هنگام آماده‌سازی بازگردانی تغییر کرده است."
                        )
                    }

                    // از وضعیت فعلی فایل پشتیبان مستقل می‌گیریم.
                    // این پشتیبان برای Recovery Journal استفاده می‌شود.

                    val safetyBackup = createBackupRecord(
                        projectRoot = projectRoot,
                        target = target,
                        originalBytes = currentBytes,
                        appliedHash = originalHash
                    ) ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                        "تهیه پشتیبان از وضعیت فعلی ناموفق بود؛ بازگردانی انجام نشد."
                    )

                    val journalEntry = beginRecoveryOperation(
                        projectRoot = projectRoot,
                        target = target,
                        backup = safetyBackup,
                        operationType = RecoveryOperationType.RESTORE_BACKUP
                    ) ?: return@withGlobalLock ProjectFileRestoreResult.Rejected(
                        "ثبت عملیات بازگردانی در Recovery Journal ناموفق بود؛ فایل اصلی تغییر نکرد."
                    )

                    val restored = writeAtomically(
                        target,
                        backupBytes
                    )

                    if (!restored) {
                        val removed = removeJournalIfStillOriginal(
                            journalEntry.id,
                            target,
                            currentHash
                        )

                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            if (removed) {
                                "بازگردانی ناموفق بود؛ وضعیت قبلی فایل تأیید شد."
                            } else {
                                "بازگردانی ناموفق بود یا وضعیت فایل قطعی نیست؛ اطلاعات بازیابی حفظ شده است."
                            }
                        )
                    }

                    val finalBytes = readLimitedFile(target)

                    val restoreVerified =
                        finalBytes != null &&
                                calculateHash(finalBytes) == originalHash

                    if (!restoreVerified) {
                        val rollbackSucceeded = rollbackOperation(
                            target = target,
                            originalBytes = currentBytes,
                            beforeHash = currentHash,
                            operationId = journalEntry.id
                        )

                        return@withGlobalLock ProjectFileRestoreResult.Rejected(
                            if (rollbackSucceeded) {
                                "بررسی بازگردانی ناموفق بود؛ نسخه قبلی فایل بازیابی و تأیید شد."
                            } else {
                                "بررسی بازگردانی ناموفق بود و وضعیت فایل قطعی نیست؛ Journal و نسخه پشتیبان حفظ شده‌اند."
                            }
                        )
                    }

                    finishRecoveryOperation(journalEntry.id)

                    ProjectFileRestoreResult.Restored(
                        filePath = target.path
                    )

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ProjectFileRestoreResult.Rejected(
                        e.message ?: "خطای نامشخص هنگام بازگردانی."
                    )
                }
            }
        }

    // -------------------------------------------------
    // بررسی عملیات باقی‌مانده در Recovery Journal
    //
    // اگر حتی یک عملیات حل‌نشده یا نامعتبر باشد،
    // ProjectRecoveryException صادر می‌شود.
    //
    // این متد هیچ فایل اصلی را بازنویسی یا ترمیم نمی‌کند.
    // -------------------------------------------------

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

                    val projectRoot = validateProject(project)
                        ?: throw ProjectRecoveryException(
                            listOf(
                                "مسیر پروژه برای بررسی بازیابی معتبر نیست."
                            )
                        )

                    val canonicalProjectPath =
                        projectRoot.canonicalPath

                    val entries =
                        recoveryJournal.getPendingEntries(
                            canonicalProjectPath
                        )

                    if (entries.isEmpty()) {
                        return@withGlobalLock emptyList()
                    }

                    for (entry in entries) {

                        try {

                            // بررسی تعلق عملیات به همین پروژه

                            if (
                                entry.projectPath != canonicalProjectPath
                            ) {
                                problems.add(
                                    "یک عملیات با مسیر پروژه ناسازگار پیدا شد؛ اطلاعات آن حفظ شد."
                                )
                                continue
                            }

                            // بررسی مسیر فایل هدف

                            val target =
                                File(entry.filePath).canonicalFile

                            if (
                                target.path != entry.filePath ||
                                !isSafeHistoricalPath(
                                    projectRoot,
                                    entry.filePath
                                ) ||
                                !target.isFile
                            ) {
                                problems.add(
                                    "مسیر فایل یک عملیات بازیابی معتبر نیست؛ اطلاعات آن حفظ شد."
                                )
                                continue
                            }

                            // بررسی مسیر نسخه پشتیبان

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

                            val backupPrefix =
                                backupRoot.path + File.separator

                            val backupFile =
                                File(entry.backupPath).canonicalFile

                            if (
                                backupFile.path != entry.backupPath ||
                                !backupFile.path.startsWith(backupPrefix) ||
                                !backupFile.isFile ||
                                !backupFile.canRead() ||
                                !backupFile.extension.equals(
                                    "bak",
                                    ignoreCase = true
                                ) ||
                                backupFile.length() > MAX_FILE_SIZE
                            ) {
                                problems.add(
                                    "نسخه پشتیبان یک عملیات معتبر نیست؛ اطلاعات آن حفظ شد."
                                )
                                continue
                            }

                            // بررسی هش‌های ثبت‌شده

                            if (
                                !isValidHash(entry.beforeHash) ||
                                !isValidHash(entry.targetHash)
                            ) {
                                problems.add(
                                    "هش‌های ثبت‌شده در یک عملیات معتبر نیستند؛ اطلاعات آن حفظ شد."
                                )
                                continue
                            }

                            // بررسی سلامت نسخه پشتیبان

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

                            // بررسی وضعیت فایل فعلی با Journal

                            val checkResult =
                                recoveryJournal.inspectOperation(entry)

                            when (checkResult) {

                                is RecoveryCheckResult.Resolved -> {

                                    val removed =
                                        recoveryJournal.remove(
                                            checkResult.operationId
                                        )

                                    if (removed) {

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

                    // اگر حتی یک مشکل وجود داشته باشد،
                    // بازیابی موفق اعلام نمی‌شود.

                    if (problems.isNotEmpty()) {

                        val failureMessages = buildList {

                            add(
                                "بازیابی پروژه با موفقیت کامل نشد."
                            )

                            addAll(problems)

                            add(
                                "هیچ فایل مشکوکی به‌صورت خودکار تغییر نکرده است. " +
                                        "اطلاعات Journal و نسخه‌های پشتیبان حفظ شده‌اند."
                            )

                            add(
                                "تا بررسی مجدد، ادامه عملیات روی این پروژه مجاز نیست."
                            )
                        }

                        throw ProjectRecoveryException(
                            failureMessages
                        )
                    }

                    messages

                } catch (e: CancellationException) {

                    throw e

                } catch (e: ProjectRecoveryException) {

                    throw e

                } catch (e: Exception) {

                    val failureMessages = listOf(
                        "بررسی Recovery Journal با خطا مواجه شد.",
                        e.message ?: "خطای نامشخص هنگام بررسی بازیابی.",
                        "اطلاعات بازیابی حفظ شده‌اند؛ ادامه عملیات پروژه تا بررسی مجدد مجاز نیست."
                    )

                    throw ProjectRecoveryException(
                        failureMessages
                    )
                }
            }
        }
}
