
package com.faridtech.ai

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
    }

    private val appContext = context.applicationContext
    private val lock = Any()

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
    // نوشتن اتمیک فایل
    // -------------------------------------------------

    private fun writeAtomically(
        target: File,
        bytes: ByteArray
    ): Boolean {

        var output: FileOutputStream? = null

        val atomicFile = AtomicFile(target)

        return try {
            output = atomicFile.startWrite()

            output.write(bytes)
            output.fd.sync()

            atomicFile.finishWrite(output)

            output = null

            true

        } catch (_: Exception) {

            if (output != null) {
                try {
                    atomicFile.failWrite(output)
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

        return writeAtomically(
            manifestFile,
            json.toString(2).toByteArray(Charsets.UTF_8)
        )
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

                val originalBytes = target.readBytes()

                if (originalBytes.size > MAX_FILE_SIZE) {
                    return@withContext null
                }

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

            synchronized(lock) {

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

                    val originalBytes = target.readBytes()

                    if (originalBytes.size > MAX_FILE_SIZE) {
                        return@synchronized ProjectFileChangeResult.Rejected(
                            "اندازه فایل از حد مجاز بیشتر است."
                        )
                    }

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

                    if (!backupRoot.isDirectory) {
                        return@synchronized ProjectFileChangeResult.Rejected(
                            "مسیر نسخه پشتیبان معتبر نیست."
                        )
                    }

                    val backupId = UUID.randomUUID().toString()

                    val backupFile = File(
                        backupRoot,
                        "$backupId.bak"
                    ).canonicalFile

                    val manifestFile = File(
                        backupRoot,
                        "$backupId.json"
                    ).canonicalFile

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

                    val latestBytes = target.readBytes()

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

                    val finalHash = calculateHash(
                        target.readBytes()
                    )

                    val expectedHash =
                        calculateHash(proposedBytes)

                    if (finalHash != expectedHash) {

                        val rollbackSucceeded = writeAtomically(
                            target,
                            originalBytes
                        )

                        if (rollbackSucceeded) {
                            return@synchronized ProjectFileChangeResult.Rejected(
                                "بررسی نهایی فایل ناموفق بود. نسخه اصلی بازیابی شد و پشتیبان حفظ شده است."
                            )
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

            synchronized(lock) {

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
                                    backupRoot.path + File.separator
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

                            val backupPrefix =
                                backupRoot.path + File.separator

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
                                backupSize > 0L &&
                                backupSize <= MAX_FILE_SIZE
                            ) {

                                val backupBytes =
                                    backupFile.readBytes()

                                isValid =
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
                            // یک ورودی خراب نباید کل تاریخچه را متوقف کند.
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
    // بازگردانی امن نسخه پشتیبان
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

            synchronized(lock) {

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

                    val backupFile = File(backupPath).canonicalFile

                    val backupPrefix =
                        backupRoot.path + File.separator

                    if (
                        !backupFile.path.startsWith(backupPrefix) ||
                        !backupFile.isFile ||
                        !backupFile.canRead() ||
                        backupFile.length() > MAX_FILE_SIZE
                    ) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "مسیر یا اندازه نسخه پشتیبان معتبر نیست."
                        )
                    }

                    val manifestFile = File(
                        backupFile.parentFile,
                        backupFile.nameWithoutExtension + ".json"
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

                    val backupId =
                        manifest.optString("backupId")

                    if (
                        savedProjectPath != projectRoot.path ||
                        savedFilePath != target.path ||
                        backupId != backupFile.nameWithoutExtension ||
                        originalHash.isBlank() ||
                        appliedHash.isBlank() ||
                        !isValidHash(originalHash) ||
                        !isValidHash(appliedHash)
                    ) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "این نسخه پشتیبان متعلق به این فایل نیست."
                        )
                    }

                    val currentBytes = target.readBytes()

                    if (currentBytes.size > MAX_FILE_SIZE) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "اندازه فایل فعلی بیش از حد مجاز است."
                        )
                    }

                    val currentHash =
                        calculateHash(currentBytes)

                    if (currentHash != appliedHash) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "فایل پس از اصلاح تغییر کرده است. برای جلوگیری از حذف تغییرات جدید، بازگردانی متوقف شد."
                        )
                    }

                    val backupBytes = backupFile.readBytes()

                    if (
                        backupBytes.isEmpty() ||
                        backupBytes.size > MAX_FILE_SIZE ||
                        calculateHash(backupBytes) != originalHash
                    ) {
                        return@synchronized ProjectFileRestoreResult.Rejected(
                            "نسخه پشتیبان خالی، آسیب‌دیده یا دستکاری شده است."
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

                    val finalBytes = target.readBytes()

                    val restoreVerified =
                        finalBytes.size <= MAX_FILE_SIZE &&
                                calculateHash(finalBytes) == originalHash

                    if (!restoreVerified) {

                        val rollbackSucceeded = writeAtomically(
                            target,
                            currentBytes
                        )

                        if (rollbackSucceeded) {

                            val rollbackBytes = target.readBytes()

                            if (
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
