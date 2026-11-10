
package com.faridtech.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Date

data class ProjectBackupEntry(
    val backupId: String,
    val projectPath: String,
    val filePath: String,
    val fileName: String,
    val backupPath: String,
    val createdAt: Long,
    val originalHash: String,
    val appliedHash: String,
    val backupSize: Long,
    val isHealthy: Boolean
)

class ProjectBackupHistoryManager(context: Context) {

    companion object {
        private const val PROJECTS_DIRECTORY = "projects"
        private const val BACKUP_DIRECTORY = "project_backups"
        private const val MAX_FILE_SIZE = 256 * 1024L
        private const val MAX_MANIFEST_SIZE = 16 * 1024L

        private val ALLOWED_EXTENSIONS = setOf(
            "kt", "java", "xml", "gradle", "kts",
            "txt", "md", "json", "html", "css",
            "js", "cpp", "h", "c", "cmake",
            "pro", "sql", "py", "dart", "swift"
        )
    }

    private val appContext = context.applicationContext

    private fun calculateHash(bytes: ByteArray): String {
        val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)

        return digest.joinToString("") {
            "%02x".format(it)
        }
    }

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

    private fun isValidProjectFile(
        projectRoot: File,
        filePath: String
    ): File? {
        return try {
            val target = File(filePath).canonicalFile

            val prefix =
                projectRoot.path + File.separator

            if (!target.path.startsWith(prefix)) {
                return null
            }

            val extension = target.extension.lowercase()

            if (extension !in ALLOWED_EXTENSIONS) {
                return null
            }

            target
        } catch (_: Exception) {
            null
        }
    }

    private fun readBackupEntry(
        projectRoot: File,
        backupRoot: File,
        manifestFile: File
    ): ProjectBackupEntry? {
        return try {

            if (!manifestFile.isFile) return null

            if (
                !manifestFile.path.startsWith(
                    backupRoot.path + File.separator
                )
            ) {
                return null
            }

            if (manifestFile.length() > MAX_MANIFEST_SIZE) {
                return null
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

            val createdAt =
                manifest.optLong("createdAt", 0L)

            if (
                savedProjectPath != projectRoot.path ||
                originalHash.isBlank() ||
                appliedHash.isBlank() ||
                createdAt <= 0L ||
                backupId.isBlank()
            ) {
                return null
            }

            if (
                backupId != manifestFile.nameWithoutExtension
            ) {
                return null
            }

            val target = isValidProjectFile(
                projectRoot,
                savedFilePath
            ) ?: return null

            val backupFile = File(
                backupRoot,
                "$backupId.bak"
            ).canonicalFile

            val backupPrefix =
                backupRoot.path + File.separator

            if (
                !backupFile.path.startsWith(backupPrefix) ||
                !backupFile.isFile ||
                !backupFile.canRead() ||
                backupFile.length() <= 0L ||
                backupFile.length() > MAX_FILE_SIZE
            ) {
                return null
            }

            val backupBytes = backupFile.readBytes()

            val healthy =
                calculateHash(backupBytes) == originalHash

            ProjectBackupEntry(
                backupId = backupId,
                projectPath = projectRoot.path,
                filePath = target.path,
                fileName = target.name,
                backupPath = backupFile.absolutePath,
                createdAt = createdAt,
                originalHash = originalHash,
                appliedHash = appliedHash,
                backupSize = backupBytes.size.toLong(),
                isHealthy = healthy
            )

        } catch (_: Exception) {
            null
        }
    }

    /**
     * دریافت تاریخچه نسخه‌های پشتیبان یک پروژه
     */
    suspend fun getProjectHistory(
        project: File
    ): List<ProjectBackupEntry> =
        withContext(Dispatchers.IO) {

            try {

                val projectRoot = validateProject(project)
                    ?: return@withContext emptyList()

                val backupRoot = File(
                    appContext.filesDir,
                    BACKUP_DIRECTORY
                ).canonicalFile

                if (!backupRoot.isDirectory) {
                    return@withContext emptyList()
                }

                val manifests = backupRoot.listFiles()
                    ?.filter {
                        it.isFile &&
                        it.extension.equals(
                            "json",
                            ignoreCase = true
                        )
                    }
                    ?: return@withContext emptyList()

                manifests.mapNotNull { manifest ->
                    readBackupEntry(
                        projectRoot,
                        backupRoot,
                        manifest
                    )
                }.sortedByDescending {
                    it.createdAt
                }

            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * دریافت تاریخچه یک فایل مشخص
     */
    suspend fun getFileHistory(
        project: File,
        file: File
    ): List<ProjectBackupEntry> =
        withContext(Dispatchers.IO) {

            try {

                val projectRoot = validateProject(project)
                    ?: return@withContext emptyList()

                val target = isValidProjectFile(
                    projectRoot,
                    file.path
                ) ?: return@withContext emptyList()

                getProjectHistory(projectRoot)
                    .filter {
                        it.filePath == target.path
                    }

            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * تبدیل زمان ثبت نسخه به متن قابل نمایش
     */
    fun formatBackupDate(timestamp: Long): String {
        return try {
            val formatter = java.text.SimpleDateFormat(
                "yyyy/MM/dd - HH:mm",
                java.util.Locale.getDefault()
            )

            formatter.format(Date(timestamp))
        } catch (_: Exception) {
            "تاریخ نامشخص"
        }
    }

    /**
     * تبدیل حجم فایل به متن قابل نمایش
     */
    fun formatBackupSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 ->
                "%.1f KB".format(
                    size / 1024.0
                )
            else ->
                "%.1f MB".format(
                    size / (1024.0 * 1024.0)
                )
        }
    }
}
