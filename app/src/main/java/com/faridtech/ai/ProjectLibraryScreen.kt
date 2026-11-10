
package com.faridtech.ai

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ProjectLibraryItem(
    val folder: File,
    val name: String,
    val packageName: String,
    val language: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Composable
fun ProjectLibraryScreen(
    onOpenProject: (File) -> Unit,
    onCreateProject: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val projects = remember {
        mutableStateListOf<ProjectLibraryItem>()
    }

    val projectsDirectory = remember(context) {
        File(context.filesDir, "projects")
    }

    val scope = rememberCoroutineScope()

    var editingProject by remember {
        mutableStateOf<ProjectLibraryItem?>(null)
    }

    var newProjectName by remember {
        mutableStateOf("")
    }

    var errorMessage by remember {
        mutableStateOf<String?>(null)
    }

    var isRenaming by remember {
        mutableStateOf(false)
    }

    fun loadProjects() {
        projects.clear()

        val folders = projectsDirectory.listFiles()
            ?.filter {
                it.isDirectory &&
                    !it.name.startsWith(".")
            }
            ?: emptyList()

        val items = folders.map { folder ->

            val metadata = ProjectMetadataStore.load(folder)

            ProjectLibraryItem(
                folder = folder,
                name = metadata?.name?.takeIf { it.isNotBlank() }
                    ?: folder.name,
                packageName = metadata?.packageName
                    ?.takeIf { it.isNotBlank() }
                    ?: "نامشخص",
                language = metadata?.language
                    ?.takeIf { it.isNotBlank() }
                    ?: "نامشخص",
                createdAt = metadata?.createdAt
                    ?: folder.lastModified(),
                updatedAt = metadata?.updatedAt
                    ?: folder.lastModified()
            )
        }.sortedByDescending {
            it.updatedAt
        }

        projects.addAll(items)
    }

    fun startRename(project: ProjectLibraryItem) {
        editingProject = project
        newProjectName = project.name
        errorMessage = null
    }

    fun renameProject() {
        val project = editingProject ?: return
        val requestedName = newProjectName.trim()

        if (requestedName.isBlank()) {
            errorMessage = "نام پروژه نمی‌تواند خالی باشد."
            return
        }

        if (
            requestedName == "." ||
            requestedName == ".." ||
            requestedName.contains("/") ||
            requestedName.contains("\\") ||
            requestedName.contains(":")
        ) {
            errorMessage = "نام پروژه معتبر نیست."
            return
        }

        if (isRenaming) return

        isRenaming = true
        errorMessage = null

        scope.launch {
            val result = withContext(Dispatchers.IO) {

                val root = projectsDirectory.canonicalFile
                val source = project.folder.canonicalFile

                if (
                    !source.path.startsWith(root.path + File.separator) ||
                    !source.isDirectory
                ) {
                    return@withContext "مسیر پروژه معتبر نیست."
                }

                val destination = File(root, requestedName)

                if (
                    destination.exists() &&
                    destination.canonicalFile != source
                ) {
                    return@withContext "پروژه‌ای با این نام وجود دارد."
                }

                if (requestedName == project.folder.name) {
                    ProjectMetadataStore.save(
                        projectDirectory = source,
                        name = requestedName,
                        packageName = project.packageName,
                        language = project.language
                    )
                    return@withContext null
                }

                if (!source.renameTo(destination)) {
                    return@withContext "تغییر نام پوشه پروژه انجام نشد."
                }

                ProjectMetadataStore.save(
                    projectDirectory = destination,
                    name = requestedName,
                    packageName = project.packageName,
                    language = project.language
                )

                null
            }

            isRenaming = false

            if (result == null) {
                editingProject = null
                loadProjects()
            } else {
                errorMessage = result
            }
        }
    }

    LaunchedEffect(Unit) {
        loadProjects()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "کتابخانه پروژه‌ها",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            OutlinedButton(onClick = onBack) {
                Text("بازگشت")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "مدیریت پروژه‌های ساخته‌شده در FARID TECH AI",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onCreateProject,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("＋ ساخت پروژه جدید")
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = { loadProjects() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("تازه‌سازی فهرست")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (projects.isEmpty()) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "هنوز پروژه‌ای ساخته نشده است.",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "بعد از ساخت پروژه، اطلاعات آن در این قسمت نمایش داده می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

        } else {

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                items(
                    items = projects,
                    key = { it.folder.absolutePath }
                ) { project ->

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {

                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {

                            Text(
                                text = project.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "شناسه بسته: ${project.packageName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(5.dp))

                            Text(
                                text = "زبان برنامه‌نویسی: ${project.language}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(5.dp))

                            val dateFormat = SimpleDateFormat(
                                "yyyy/MM/dd - HH:mm",
                                Locale.getDefault()
                            )

                            Text(
                                text = "تاریخ ایجاد: ${
                                    dateFormat.format(Date(project.createdAt))
                                }",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(5.dp))

                            Text(
                                text = "آخرین ویرایش: ${
                                    dateFormat.format(Date(project.updatedAt))
                                }",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    onOpenProject(project.folder)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("باز کردن پروژه")
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            OutlinedButton(
                                onClick = {
                                    startRename(project)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("تغییر نام پروژه")
                            }
                        }
                    }
                }
            }
        }
    }

    if (editingProject != null) {

        AlertDialog(
            onDismissRequest = {
                if (!isRenaming) {
                    editingProject = null
                    errorMessage = null
                }
            },
            title = {
                Text("تغییر نام پروژه")
            },
            text = {
                Column {

                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = {
                            newProjectName = it
                            errorMessage = null
                        },
                        label = {
                            Text("نام جدید پروژه")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { renameProject() },
                    enabled = !isRenaming
                ) {
                    Text(
                        if (isRenaming) "در حال تغییر..." else "ذخیره"
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (!isRenaming) {
                            editingProject = null
                            errorMessage = null
                        }
                    },
                    enabled = !isRenaming
                ) {
                    Text("انصراف")
                }
            }
        )
    }
}
