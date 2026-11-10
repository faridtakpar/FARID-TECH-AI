
package com.faridtech.ai

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

data class ProjectMetadata(
    val name: String,
    val packageName: String,
    val language: String,
    val createdAt: Long,
    val updatedAt: Long
)

object ProjectMetadataStore {

    private const val FILE_NAME = ".farid-project.json"

    fun save(
        projectDirectory: File,
        name: String,
        packageName: String,
        language: String,
        createdAt: Long = System.currentTimeMillis()
    ): Boolean {
        return try {
            if (!projectDirectory.exists() ||
                !projectDirectory.isDirectory
            ) return false

            val metadataFile = File(projectDirectory, FILE_NAME)

            val previous = load(projectDirectory)

            val json = JSONObject().apply {
                put("name", name.trim())
                put("packageName", packageName.trim())
                put("language", language.trim())
                put(
                    "createdAt",
                    previous?.createdAt ?: createdAt
                )
                put("updatedAt", System.currentTimeMillis())
            }

            val atomicFile = AtomicFile(metadataFile)
            var output: java.io.FileOutputStream? = null

            try {
                output = atomicFile.startWrite()
                output.write(json.toString(2).toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(output)
                output = null
            } catch (error: Exception) {
                if (output != null) {
                    atomicFile.failWrite(output)
                }
                throw error
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    fun load(projectDirectory: File): ProjectMetadata? {
        return try {
            if (!projectDirectory.exists() ||
                !projectDirectory.isDirectory
            ) return null

            val metadataFile = File(projectDirectory, FILE_NAME)

            if (!metadataFile.isFile) return null

            val json = JSONObject(
                metadataFile.readText(Charsets.UTF_8)
            )

            ProjectMetadata(
                name = json.optString("name", ""),
                packageName = json.optString("packageName", ""),
                language = json.optString("language", ""),
                createdAt = json.optLong("createdAt", 0L),
                updatedAt = json.optLong("updatedAt", 0L)
            )
        } catch (_: Exception) {
            null
        }
    }
}
