
package com.faridtech.ai

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProjectLibraryScreen(
    onOpenProject: (File) -> Unit,
    onCreateProject: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val projects = remember { mutableStateListOf<File>() }
    val projectsDirectory = remember(context) {
        File(context.filesDir, "projects")
    }

    fun loadProjects() {
        projects.clear()

        val folders = projectsDirectory.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        projects.addAll(folders)
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
            text = "پروژه‌های ساخته‌شده در FARID TECH AI",
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

        Spacer(modifier = Modifier.height(12.dp))

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
                    text = "بعد از ساخت پروژه، آن را در این قسمت مشاهده می‌کنی.",
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
                    key = { it.absolutePath }
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

                            Spacer(modifier = Modifier.height(6.dp))

                            val modifiedDate = SimpleDateFormat(
                                "yyyy/MM/dd - HH:mm",
                                Locale.getDefault()
                            ).format(Date(project.lastModified()))

                            Text(
                                text = "آخرین تغییر: $modifiedDate",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = { onOpenProject(project) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("باز کردن پروژه")
                            }
                        }
                    }
                }
            }
        }
    }
}
