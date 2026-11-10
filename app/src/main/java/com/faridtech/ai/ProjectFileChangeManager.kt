
package com.faridtech.ai

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
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
        private val globalLock = Any()
    }

    private val appContext = context.applicationContext

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
    // اعتبارسنجی مسیر فایل در تاریخچه
    // -------------------------------------------------

    private fun isSafeHistoricalPath(
        projectRoot: File,
        filePath: String
    ): Boolean {
        return try {
            val target = File(filePath).canonicalFile

            val rootPrefix =
                projectRoot.path + File.separator

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
    // خواندن کنترل‌شده و امن فایل با AtomicFile
    // -------------------------------------------------

    private fun readLimitedFile(file: File): ByteArray? {
        return try {
            if (!file.exists() || !file.isFile || !file.canRead()) {
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
    // ذخیره اطلاعات نسخه پشتیبان
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

            } catch (_: Exception) {
                null
            }
        }

    // -------------------------------------------------
    // اعمال تغییرات با تأیید مالک
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

            synchronized(globalLock) {

                try {

                    val projectRoot = validateProject(
                        File(preview.projectPath)
                    ) ?: return@synchronized ProjectFileChangeResult.Rejected(
                        "مسیر پروژه معتبر نیست."
                    )

                    val target = validateFile(
                        projectRoot,
                        File(preview.filePath)
                    ) ?: return@synchronized ProjectFileChangeResult.Rejected(
                        "فایل انتخاب‌شده معتبر یا قابل نوشتن نیست."
                    )

                    if (
                        target.canonicalPath !=
                        File(preview.filePath).canonicalPath
                    ) {
                        return@synchronized ProjectFileChangeResult.Rejected(
                            "مسیر فایل تغییر کرده است."
                        )
                    }

                    val originalBytes = readLimitedFile(target)
                        ?: return@synchronized ProjectFileChangeResult.Rejected(
                            "خواندن فایل اصلی ناموفق بود یا اندازه آن بیش از حد مجاز است."
                        )

                    val originalHash =
                        calculateHash(originalBytes)

                    if (originalHash != preview.originalHash) {
                        return@synchronized ProjectFileChangeResult.Rejected(
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
                        return@synchronized ProjectFileChangeResult.Rejected(
                            "محتوای پیشنهادی خالی یا بیش از حد مجاز است."
                        )
                    }

                    val backupRoot = File(
                        appContext.filesDir,
                        BACKUP_DIRECTORY
                    )

                    if (
                        !backupRoot.exists() &&
                        !backupRoot.mkdirs()
                    ) {
                        return@synchronized ProjectFileChangeResult.Rejected(
                            "ساخت پوشه نسخه پشتیبان ناموفق بود."
                        )
                    }

                    val canonicalBackupRoot =
                        backupRoot.canonicalFile

                    if (!canonicalBackupRoot.isDirectory) {
                        return@synchronized ProjectFileChangeResult.Rejected(
                            "مسیر نسخه پشتیبان معتبر نیست."
                        )
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
                        return@synchronized ProjectFileChangeResult.Rejected(
                            "مسیر نسخه پشتیبان معتبر نیست."
                        )
                    }

                    if (!writeBackup(backupFile, originalBytes)) {
                        return@synchronized ProjectFileChangeResult.Rejected(
                            "تهیه نسخه پشتیبان ناموفق بود؛ فایل اصلی تغییر نکرد."
                        )
                    }

                    val manifest = JSONObject().apply {

                        put("projectPath", projectRoot.path)
                        put("filePath", target.path)
                        put("originalHash", originalHash)

                        put(
                            "appliedHash",
                            calculateHash(proposedBytes)
                        )

                        put("backupId", backupId)

                        put(
                            "createdAt",
                            System.currentTimeMillis()
                        )
                    }

                    if (!writeManifest(manifestFile, manifest)) {
                        return@synchronized ProjectFileChangeResult.Rejected(
                            "ثبت اطلاعات نسخه پشتیبان ناموفق بود؛ فایل اصلی تغییر نکرد."
                        )
                    }

                    val latestBytes = readLimitedFile(target)
                        ?: return@synchronized ProjectFileChangeResult.Rejected(
                            "خواندن مجدد فایل پیش از اصلاح ناموفق بود."
                        )

                    if (
                        calculateHash(latestBytes) != originalHash
                    ) {
                        return@synchronized ProjectFileChangeResult.Rejected(
                            "فایل هنگام آماده‌سازی تغییر کرده است. اصلاح متوقف شد."
                        )
                    }

                    val written = writeAtomically(
                        target,
                        proposedBytes
                    )

                    if (!written) {
                        return@synchronized ProjectFileChangeResult.Rejected(
                            "نوشتن فایل ناموفق بود. نسخه پشتیبان حفظ شده است."
                        )
                    }

                    val finalBytes = readLimitedFile(target)

                    val expectedHash =
                        calculateHash(proposedBytes)

                    if (
                        finalBytes == null ||
                        calculateHash(finalBytes) != expectedHash
                    ) {

                        val rollbackSucceeded = writeAtomically(
                            target,
                            originalBytes
                        )

                        if (rollbackSucceeded) {

                            val rollbackBytes =
                                readLimitedFile(target)

                            if (
                                rollbackBytes != null &&
                                calculateHash(rollbackBytes) == originalHash
                            ) {
                                return@synchronized ProjectFileChangeResult.Rejected(
                                    "بررسی نهایی فایل ناموفق بود. نسخه اصلی بازیابی شد و پشتیبان حفظ شده است."
                                )
                            }
                        }

                        return@synchronized ProjectFileChangeResult.Rejected(
                            "بررسی نهایی فایل ناموفق بود و بازیابی نسخه اصلی نیز تأیید نشد. نسخه پشتیبان حفظ شده است."
                        )
                    }

                    ProjectFileChangeResult.Applied(
                        backupPath = backupFile.absolutePath
                    )

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

            synchronized(globalLock) {

                try {

                    val projectRoot = validateProject(project)
                        ?: return@synchronized emptyList()

                    val backupRoot = File(
                        appContext.filesDir,
                        BACKUP_DIRECTORY
                    ).canonicalFile

                    if (!backupRoot.isDirectory) {
                        return@synchronized emptyList()
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
                        ?: return@synchronized emptyList()

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

                            val manifest = JSONObject(
                                manifestFile.readText(Charsets.UTF_8)
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

                            // فایل خالی نیز می‌تواند نسخه پشتیبان معتبر باشد.
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

                        } catch (_: Exception) {
                            // خرابی یک ورودی نباید کل تاریخچه را متوقف کند.
                        }
                    }

                    history.sortedByDescending {
                        it.createdAt
                    }

                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

    // -------------------------------------------------
    // بازگردانی نسخه تاریخی با پشتیبان‌گیری از وضعیت فعلی
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

            synchronized(globalLock) {

                try {

                    val projectRoot = validateProject(project)
                        ?: return@synchronized ProjectFileRestoreResult.Rejected(
                            "مسیر پروژه معتبر نیست."
                        )

                    val target = validateFile(
                        projectRoot,
                        file
                    ) ?: return@synchronized ProjectFileRestoreResult.Rejected(
                        "فایل مقصد معتبر یا قابل نوشتن نیست."
                    )

                    val backupRoot = File(
                        appContext.filesDir,
                        BACKUP_DIRECTORY
                    ).canonicalFile

                    if (!backupRoot.isDirectory) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "پوشه نسخه‌های پشتیبان پیدا نشد."
                        )
                    }

                    val backupPrefix =
                        backupRoot.path + File.separator

                    // اعتبارسنجی نسخه تاریخی انتخاب‌شده

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
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "مسیر یا اندازه نسخه تاریخی معتبر نیست."
                        )
                    }

                    val historicalId =
                        historicalBackup.nameWithoutExtension

                    val parsedId = try {
                        UUID.fromString(historicalId)
                    } catch (_: Exception) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "شناسه نسخه تاریخی معتبر نیست."
                        )
                    }

                    if (parsedId.toString() != historicalId) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
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
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "اطلاعات نسخه تاریخی پیدا نشد یا معتبر نیست."
                        )
                    }

                    val manifest = JSONObject(
                        historicalManifest.readText(Charsets.UTF_8)
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
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "این نسخه پشتیبان متعلق به این پروژه یا فایل نیست."
                        )
                    }

                    // خواندن و بررسی محتوای نسخه تاریخی

                    val historicalBytes =
                        readLimitedFile(historicalBackup)
                            ?: return@synchronized ProjectFileRestoreResult.Rejected(
                                "خواندن نسخه تاریخی ناموفق بود یا اندازه آن بیش از حد مجاز است."
                            )

                    if (
                        calculateHash(historicalBytes) != originalHash
                    ) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "نسخه تاریخی آسیب‌دیده یا دستکاری شده است."
                        )
                    }

                    // خواندن وضعیت فعلی فایل

                    val currentBytes = readLimitedFile(target)
                        ?: return@synchronized ProjectFileRestoreResult.Rejected(
                            "خواندن فایل فعلی ناموفق بود یا اندازه آن بیش از حد مجاز است."
                        )

                    val currentHash =
                        calculateHash(currentBytes)

                    if (currentHash == originalHash) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "فایل در حال حاضر دقیقاً همین نسخه را دارد."
                        )
                    }

                    // ایجاد شناسه و مسیر پشتیبان وضعیت فعلی

                    val newBackupId =
                        UUID.randomUUID().toString()

                    val safetyBackup = File(
                        backupRoot,
                        "$newBackupId.bak"
                    ).canonicalFile

                    val safetyManifest = File(
                        backupRoot,
                        "$newBackupId.json"
                    ).canonicalFile

                    if (
                        !safetyBackup.path.startsWith(backupPrefix) ||
                        !safetyManifest.path.startsWith(backupPrefix)
                    ) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "مسیر پشتیبان وضعیت فعلی معتبر نیست."
                        )
                    }

                    if (!writeBackup(safetyBackup, currentBytes)) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "تهیه پشتیبان از فایل فعلی ناموفق بود؛ بازگردانی انجام نشد."
                        )
                    }

                    // ثبت اولیه اطلاعات پشتیبان قبل از تغییر فایل.
                    // در این مرحله appliedHash برابر هش وضعیت فعلی است.

                    val safetyManifestJson = JSONObject().apply {

                        put("projectPath", projectRoot.path)
                        put("filePath", target.path)
                        put("originalHash", currentHash)
                        put("appliedHash", currentHash)
                        put("backupId", newBackupId)

                        put(
                            "createdAt",
                            System.currentTimeMillis()
                        )
                    }

                    if (
                        !writeManifest(
                            safetyManifest,
                            safetyManifestJson
                        )
                    ) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "ثبت اطلاعات پشتیبان وضعیت فعلی ناموفق بود؛ بازگردانی انجام نشد."
                        )
                    }

                    // بررسی مجدد فایل قبل از نوشتن نسخه تاریخی

                    val latestBytes = readLimitedFile(target)
                        ?: return@synchronized ProjectFileRestoreResult.Rejected(
                            "خواندن مجدد فایل قبل از بازگردانی ناموفق بود."
                        )

                    if (
                        calculateHash(latestBytes) != currentHash
                    ) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "فایل هنگام آماده‌سازی بازگردانی تغییر کرده است؛ عملیات متوقف شد."
                        )
                    }

                    // نوشتن نسخه تاریخی

                    val restored = writeAtomically(
                        target,
                        historicalBytes
                    )

                    if (!restored) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "نوشتن نسخه تاریخی ناموفق بود. پشتیبان وضعیت فعلی حفظ شده است."
                        )
                    }

                    // بررسی نتیجه بازگردانی

                    val finalBytes = readLimitedFile(target)

                    val restoreVerified =
                        finalBytes != null &&
                                calculateHash(finalBytes) == originalHash

                    if (!restoreVerified) {

                        val rollbackSucceeded = writeAtomically(
                            target,
                            currentBytes
                        )

                        if (rollbackSucceeded) {

                            val rollbackBytes =
                                readLimitedFile(target)

                            if (
                                rollbackBytes != null &&
                                calculateHash(rollbackBytes) == currentHash
                            ) {
                                return@synchronized ProjectFileRestoreResult.Rejected(
                                    "بررسی بازگردانی ناموفق بود؛ وضعیت قبلی فایل بازیابی شد."
                                )
                            }
                        }

                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "بررسی بازگردانی ناموفق بود و بازیابی وضعیت قبلی نیز تأیید نشد. پشتیبان وضعیت قبلی حفظ شده است."
                        )
                    }

                    // به‌روزرسانی Manifest پس از موفقیت بازگردانی.
                    // اگر این مرحله شکست بخورد، تلاش می‌کنیم فایل را برگردانیم.

                    safetyManifestJson.put(
                        "appliedHash",
                        originalHash
                    )

                    if (
                        !writeManifest(
                            safetyManifest,
                            safetyManifestJson
                        )
                    ) {

                        val rollbackSucceeded = writeAtomically(
                            target,
                            currentBytes
                        )

                        if (rollbackSucceeded) {

                            val rollbackBytes =
                                readLimitedFile(target)

                            if (
                                rollbackBytes != null &&
                                calculateHash(rollbackBytes) == currentHash
                            ) {
                                return@synchronized ProjectFileRestoreResult.Rejected(
                                    "ثبت تاریخچه بازگردانی ناموفق بود؛ وضعیت قبلی فایل بازیابی شد."
                                )
                            }
                        }

                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "ثبت تاریخچه ناموفق بود و بازیابی وضعیت قبلی تأیید نشد. پشتیبان وضعیت قبلی در تاریخچه حفظ شده است؛ فایل را بررسی کنید."
                        )
                    }

                    ProjectFileRestoreResult.Restored(
                        filePath = target.path
                    )

                } catch (e: Exception) {

                    ProjectFileRestoreResult.Rejected(
                        e.message ?: "خطای نامشخص هنگام بازگردانی نسخه تاریخی."
                    )
                }
            }
        }

    // -------------------------------------------------
    // بازگردانی امن نسخه پشتیبان قبلی
    // مخصوص آخرین اصلاحی که هش فعلی با آن مطابقت دارد
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

            synchronized(globalLock) {

                try {

                    val projectRoot = validateProject(project)
                        ?: return@synchronized ProjectFileRestoreResult.Rejected(
                            "مسیر پروژه معتبر نیست."
                        )

                    val target = validateFile(
                        projectRoot,
                        file
                    ) ?: return@synchronized ProjectFileRestoreResult.Rejected(
                        "فایل مقصد معتبر یا قابل نوشتن نیست."
                    )

                    val backupRoot = File(
                        appContext.filesDir,
                        BACKUP_DIRECTORY
                    ).canonicalFile

                    if (!backupRoot.isDirectory) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
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
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "مسیر یا اندازه نسخه پشتیبان معتبر نیست."
                        )
                    }

                    val backupId =
                        backupFile.nameWithoutExtension

                    val parsedId = try {
                        UUID.fromString(backupId)
                    } catch (_: Exception) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "شناسه نسخه پشتیبان معتبر نیست."
                        )
                    }

                    if (parsedId.toString() != backupId) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
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
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "اطلاعات نسخه پشتیبان معتبر نیست."
                        )
                    }

                    val manifest = JSONObject(
                        manifestFile.readText(Charsets.UTF_8)
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
                        !isValidHash(appliedHash)
                    ) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "این نسخه پشتیبان متعلق به این فایل نیست."
                        )
                    }

                    val currentBytes = readLimitedFile(target)
                        ?: return@synchronized ProjectFileRestoreResult.Rejected(
                            "خواندن فایل فعلی ناموفق بود یا اندازه آن بیش از حد مجاز است."
                        )

                    val currentHash =
                        calculateHash(currentBytes)

                    if (currentHash != appliedHash) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "فایل پس از اصلاح تغییر کرده است. برای جلوگیری از حذف تغییرات جدید، بازگردانی متوقف شد."
                        )
                    }

                    val backupBytes = readLimitedFile(backupFile)
                        ?: return@synchronized ProjectFileRestoreResult.Rejected(
                            "خواندن نسخه پشتیبان ناموفق بود یا اندازه آن بیش از حد مجاز است."
                        )

                    if (calculateHash(backupBytes) != originalHash) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "نسخه پشتیبان آسیب‌دیده یا دستکاری شده است."
                        )
                    }

                    // بررسی مجدد فایل قبل از بازگردانی

                    val latestBytes = readLimitedFile(target)
                        ?: return@synchronized ProjectFileRestoreResult.Rejected(
                            "خواندن مجدد فایل قبل از بازگردانی ناموفق بود."
                        )

                    if (
                        calculateHash(latestBytes) != currentHash
                    ) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "فایل هنگام آماده‌سازی بازگردانی تغییر کرده است."
                        )
                    }

                    val restored = writeAtomically(
                        target,
                        backupBytes
                    )

                    if (!restored) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "بازگردانی نسخه پشتیبان ناموفق بود."
                        )
                    }

                    val finalBytes = readLimitedFile(target)

                    val restoreVerified =
                        finalBytes != null &&
                                calculateHash(finalBytes) == originalHash

                    if (!restoreVerified) {

                        val rollbackSucceeded = writeAtomically(
                            target,
                            currentBytes
                        )

                        if (rollbackSucceeded) {

                            val rollbackBytes =
                                readLimitedFile(target)

                            if (
                                rollbackBytes != null &&
                                calculateHash(rollbackBytes) == currentHash
                            ) {
                                return@synchronized ProjectFileRestoreResult.Rejected(
                                    "بررسی بازگردانی ناموفق بود؛ نسخه قبلی فایل بازیابی شد."
                                )
                            }
                        }

                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "بررسی بازگردانی ناموفق بود و بازیابی نسخه قبلی نیز تأیید نشد. نسخه پشتیبان اصلی همچنان حفظ شده است."
                        )
                    }

                    ProjectFileRestoreResult.Restored(
                        filePath = target.path
                    )

                } catch (e: Exception) {

                    ProjectFileRestoreResult.Rejected(
                        e.message ?: "خطای نامشخص هنگام بازگردانی."
                    )
                }
            }
        }
}
