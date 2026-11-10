
package com.faridtech.ai

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * اطلاعات یک اصلاح پیشنهادی.
 *
 * ساخت این شیء به‌تنهایی هیچ فایلی را تغییر نمی‌دهد.
 */
data class ProjectFileChangePreview(
    val projectPath: String,
    val filePath: String,
    val originalHash: String,
    val proposedContent: String,
    val originalSize: Long
)

/**
 * نتیجه اعمال یا عدم اعمال اصلاح.
 */
sealed class ProjectFileChangeResult {

    data class Applied(
        val backupPath: String
    ) : ProjectFileChangeResult()

    data class Rejected(
        val reason: String
    ) : ProjectFileChangeResult()
}

/**
 * مدیریت امن تغییر فایل‌های پروژه.
 *
 * ویژگی‌ها:
 * - محدود کردن دسترسی به پوشه داخلی پروژه‌ها
 * - جلوگیری از دسترسی به فایل‌های حساس
 * - محدودیت اندازه فایل
 * - بررسی تغییر نکردن نسخه اصلی
 * - پشتیبان‌گیری پیش از جایگزینی
 * - نوشتن اتمیک فایل
 *
 * این کلاس به‌تنهایی هیچ تأیید کاربری انجام نمی‌دهد.
 * فراخوانی applyChange باید فقط از دکمه تأیید مالک انجام شود.
 */
class ProjectFileChangeManager(context: Context) {

    companion object {
        private const val MAX_FILE_SIZE = 256 * 1024L

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

    /**
     * بررسی می‌کند پروژه داخل پوشه مجاز باشد.
     */
    private fun validateProject(project: File): File? {
        return try {
            val projectsRoot = File(
                appContext.filesDir,
                PROJECTS_DIRECTORY
            ).canonicalFile

            val projectRoot = project.canonicalFile

            val allowedPrefix =
                projectsRoot.path + File.separator

            if (!projectsRoot.isDirectory) {
                return null
            }

            if (!projectRoot.isDirectory) {
                return null
            }

            if (!projectRoot.path.startsWith(allowedPrefix)) {
                return null
            }

            projectRoot
        } catch (_: Exception) {
            null
        }
    }

    /**
     * بررسی ایمنی فایل.
     */
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

            if (name in BLOCKED_NAMES) {
                return null
            }

            val extension = target.extension.lowercase()

            if (extension in BLOCKED_EXTENSIONS) {
                return null
            }

            if (extension !in ALLOWED_EXTENSIONS) {
                return null
            }

            target
        } catch (_: Exception) {
            null
        }
    }

    /**
     * محاسبه SHA-256 برای تشخیص تغییر فایل.
     */
    private fun calculateHash(bytes: ByteArray): String {

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)

        return digest.joinToString("") {
            "%02x".format(it)
        }
    }

    /**
     * آماده‌سازی اصلاح پیشنهادی.
     *
     * در این مرحله فایل اصلی تغییر نمی‌کند.
     */
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

                val originalBytes =
                    target.readBytes()

                if (originalBytes.size > MAX_FILE_SIZE) {
                    return@withContext null
                }

                val originalContent = String(
                    originalBytes,
                    Charsets.UTF_8
                )

                // جلوگیری از تغییر فایل بدون تغییر واقعی محتوا
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

    /**
     * ذخیره نسخه پشتیبان به شکل اتمیک.
     */
    private fun writeBackup(
        backupFile: File,
        originalBytes: ByteArray
    ): Boolean {

        var output: FileOutputStream? = null

        val atomicFile = AtomicFile(backupFile)

        return try {
            output = atomicFile.startWrite()

            output.write(originalBytes)
            output.fd.sync()

            atomicFile.finishWrite(output)
            output = null

            true

        } catch (_: Exception) {

            if (output != null) {
                try {
                    atomicFile.failWrite(output)
                } catch (_: Exception) {
                    // خطای ثانویه نادیده گرفته می‌شود.
                }
            }

            false
        }
    }

    /**
     * نوشتن محتوای جدید به شکل اتمیک.
     */
    private fun writeAtomically(
        target: File,
        newBytes: ByteArray
    ): Boolean {

        var output: FileOutputStream? = null

        val atomicFile = AtomicFile(target)

        return try {
            output = atomicFile.startWrite()

            output.write(newBytes)
            output.fd.sync()

            atomicFile.finishWrite(output)
            output = null

            true

        } catch (_: Exception) {

            if (output != null) {
                try {
                    atomicFile.failWrite(output)
                } catch (_: Exception) {
                    // خطای ثانویه نادیده گرفته می‌شود.
                }
            }

            false
        }
    }

    /**
     * اعمال اصلاح.
     *
     * این تابع باید فقط پس از تأیید صریح مالک فراخوانی شود.
     */
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

                    // بررسی مجدد محتوا درست پیش از اعمال تغییر
                    val originalBytes = target.readBytes()

                    if (originalBytes.size > MAX_FILE_SIZE) {
                        return@synchronized ProjectFileChangeResult.Rejected(
                            "اندازه فایل از حد مجاز بیشتر است."
                        )
                    }

                    val currentHash =
                        calculateHash(originalBytes)

                    if (currentHash != preview.originalHash) {
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

                    // ایجاد پوشه پشتیبان خارج از پوشه پروژه
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

                    val projectHash = calculateHash(
                        projectRoot.path.toByteArray(Charsets.UTF_8)
                    ).take(16)

                    val fileHash = calculateHash(
                        target.path.toByteArray(Charsets.UTF_8)
                    ).take(16)

                    val backupFile = File(
                        backupRoot,
                        "${projectHash}_${fileHash}_${System.currentTimeMillis()}.bak"
                    )

                    // نسخه اصلی باید قبل از هر تغییری ذخیره شود.
                    val backupSaved = writeBackup(
                        backupFile,
                        originalBytes
                    )

                    if (!backupSaved) {
                        return@synchronized ProjectFileChangeResult.Rejected(
                            "تهیه نسخه پشتیبان ناموفق بود؛ فایل اصلی تغییر نکرد."
                        )
                    }

                    // آخرین بررسی قبل از جایگزینی
                    val latestBytes = target.readBytes()

                    if (
                        calculateHash(latestBytes) !=
                        preview.originalHash
                    ) {
                        return@synchronized ProjectFileChangeResult.Rejected(
                            "فایل در زمان اعمال تغییر عوض شده است. اصلاح متوقف شد."
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

                    // بررسی صحت محتوای نوشته‌شده
                    val finalHash = calculateHash(
                        target.readBytes()
                    )

                    if (
                        finalHash != calculateHash(proposedBytes)
                    ) {
                        return@synchronized ProjectFileChangeResult.Rejected(
                            "بررسی نهایی فایل موفق نبود. نسخه پشتیبان موجود است."
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
}
