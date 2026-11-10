
package com.faridtech.ai

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AppBuilderScreen(
    context: Context,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var projectName by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("com.example.myapp") }
    var language by remember { mutableStateOf("Kotlin") }

    var projectFolder by remember { mutableStateOf<File?>(null) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var editorText by remember { mutableStateOf("") }

    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var newFileName by remember { mutableStateOf("") }

    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    val packageRegex = Regex(
        "^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"
    )

    fun refreshFiles(folder: File) {
        files = folder.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun createProject() {
        if (projectName.isBlank()) {
            error = "نام پروژه را وارد کنید."
            return
        }

        if (!packageRegex.matches(packageName)) {
            error = "شناسه بسته معتبر نیست."
            return
        }

        loading = true
        error = ""
        message = ""

        scope.launch {
            try {
                val folder = withContext(Dispatchers.IO) {
                    val root = File(
                        context.filesDir,
                        "projects"
                    )

                    if (!root.exists()) {
                        root.mkdirs()
                    }

                    val safeName = projectName
                        .trim()
                        .replace(Regex("[^a-zA-Z0-9_-]"), "_")

                    val project = File(root, safeName)

                    if (project.exists()) {
                        throw IllegalStateException(
                            "پروژه‌ای با این نام از قبل وجود دارد."
                        )
                    }

                    if (!project.mkdirs()) {
                        throw IllegalStateException(
                            "ساخت پوشه پروژه ناموفق بود."
                        )
                    }

                    val sourceFolder = File(
                        project,
                        "app/src/main"
                    )

                    val javaFolder = File(
                        sourceFolder,
                        "java/" + packageName.replace(".", "/")
                    )

                    val resFolder = File(
                        sourceFolder,
                        "res"
                    )

                    javaFolder.mkdirs()
                    resFolder.mkdirs()

                    val activityFile = File(
                        javaFolder,
                        if (language == "Kotlin") {
                            "MainActivity.kt"
                        } else {
                            "MainActivity.java"
                        }
                    )

                    val activityCode = if (language == "Kotlin") {
                        """
package $packageName

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "Hello from FARID TECH AI"
            textSize = 22f
            setPadding(32, 32, 32, 32)
        }

        setContentView(title)
    }
}
                        """.trimIndent()
                    } else {
                        """
package $packageName;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView title = new TextView(this);
        title.setText("Hello from FARID TECH AI");
        title.setTextSize(22);
        title.setPadding(32, 32, 32, 32);

        setContentView(title);
    }
}
                        """.trimIndent()
                    }

                    activityFile.writeText(activityCode)

                    File(
                        sourceFolder,
                        "AndroidManifest.xml"
                    ).writeText(
                        """
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:label="$projectName"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name="$packageName.MainActivity"
            android:exported="true">

            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

        </activity>

    </application>

</manifest>
                        """.trimIndent()
                    )

                    File(
                        project,
                        "build.gradle.kts"
                    ).writeText(
                        """
plugins {
    id("com.android.application")
    ${if (language == "Kotlin") "id(\"org.jetbrains.kotlin.android\")" else ""}
}

android {
    namespace = "$packageName"
    compileSdk = 35

    defaultConfig {
        applicationId = "$packageName"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
                        """.trimIndent()
                    )

                    File(
                        project,
                        "settings.gradle.kts"
                    ).writeText(
                        """
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(
        RepositoriesMode.FAIL_ON_PROJECT_REPOS
    )
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "$safeName"
include(":app")
                        """.trimIndent()
                    )

                    File(
                        project,
                        "gradle.properties"
                    ).writeText(
                        """
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
android.useAndroidX=true
                        """.trimIndent()
                    )

                    project
                }

                projectFolder = folder
                refreshFiles(folder)

                val mainFile = files.firstOrNull {
                    it.name == if (language == "Kotlin") {
                        "MainActivity.kt"
                    } else {
                        "MainActivity.java"
                    }
                }

                selectedFile = mainFile

                editorText = mainFile?.readText() ?: ""

                message = "پروژه با موفقیت ایجاد شد."
            } catch (e: Exception) {
                error = e.message ?: "خطا در ساخت پروژه."
            } finally {
                loading = false
            }
        }
    }

    fun openFile(file: File) {
        scope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    file.readText()
                }

                selectedFile = file
                editorText = content
                error = ""
                message = ""
            } catch (e: Exception) {
                error = "خواندن فایل ناموفق بود."
            }
        }
    }

    fun saveFile() {
        val file = selectedFile ?: return

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    file.writeText(editorText)
                }

                message = "تغییرات فایل ذخیره شد."
                error = ""
            } catch (e: Exception) {
                error = "ذخیره فایل ناموفق بود."
            }
        }
    }

    fun createNewFile() {
        val folder = projectFolder ?: return

        val name = newFileName.trim()

        if (name.isBlank()) {
            error = "نام فایل را وارد کنید."
            return
        }

        if (
            name.contains("/") ||
            name.contains("\\") ||
            name.contains("..") ||
            name.startsWith(".")
        ) {
            error = "نام فایل معتبر نیست."
            return
        }

        if (!name.matches(Regex("[a-zA-Z0-9_.-]+"))) {
            error = "فقط حروف انگلیسی، عدد، نقطه و خط تیره مجاز است."
            return
        }

        val file = File(folder, name)

        if (file.exists()) {
            error = "این فایل از قبل وجود دارد."
            return
        }

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    file.writeText("")
                }

                refreshFiles(folder)
                selectedFile = file
                editorText = ""
                newFileName = ""
                message = "فایل جدید ساخته شد."
                error = ""
            } catch (e: Exception) {
                error = "ساخت فایل ناموفق بود."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        TextButton(onClick = onBack) {
            Text("← بازگشت به داشبورد")
        }

        Text(
            "ساخت اپلیکیشن",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Text(
            "محیط ایجاد و مدیریت پروژه‌های اندروید",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (projectFolder == null) {

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        "پروژه جدید",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = projectName,
                        onValueChange = {
                            projectName = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("نام پروژه") },
                        placeholder = { Text("MyFirstApp") },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = packageName,
                        onValueChange = {
                            packageName = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("شناسه بسته") },
                        placeholder = {
                            Text("com.example.myapp")
                        },
                        singleLine = true
                    )

                    Text(
                        "زبان برنامه‌نویسی",
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = language == "Kotlin",
                            onClick = {
                                language = "Kotlin"
                            },
                            label = { Text("Kotlin") }
                        )

                        FilterChip(
                            selected = language == "Java",
                            onClick = {
                                language = "Java"
                            },
                            label = { Text("Java") }
                        )
                    }

                    if (error.isNotEmpty()) {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Button(
                        onClick = { createProject() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = !loading
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(21.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("＋ ایجاد پروژه")
                        }
                    }
                }
            }

        } else {

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        projectName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        packageName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        "زبان: $language",
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        "مسیر ذخیره: فضای داخلی برنامه",
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedButton(
                        onClick = {
                            projectFolder = null
                            selectedFile = null
                            editorText = ""
                            files = emptyList()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("بازگشت به ایجاد پروژه")
                    }
                }
            }

            Text(
                "فایل‌های پروژه",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            files.forEach { file ->
                Card(
                    onClick = { openFile(file) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedFile == file) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📄")
                        Spacer(Modifier.width(10.dp))
                        Text(
                            file.name,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Medium
                        )
                        Text("›")
                    }
                }
            }

            OutlinedTextField(
                value = newFileName,
                onValueChange = { newFileName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("نام فایل جدید") },
                placeholder = { Text("MyFile.kt") },
                singleLine = true
            )

            OutlinedButton(
                onClick = { createNewFile() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("＋ ساخت فایل جدید")
            }

            HorizontalDivider()

            Text(
                selectedFile?.name ?: "فایلی انتخاب نشده",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = editorText,
                onValueChange = {
                    editorText = it
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(330.dp),
                placeholder = {
                    Text("کد برنامه را اینجا وارد کنید...")
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            )

            Button(
                onClick = { saveFile() },
                enabled = selectedFile != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("💾 ذخیره تغییرات فایل")
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "مرحله آماده‌سازی APK",
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "ساخت واقعی APK هنوز فعال نیست. ابتدا مدیریت پروژه و فایل‌ها را تکمیل می‌کنیم؛ سپس ابزار ساخت و خروجی APK را به این محیط متصل خواهیم کرد.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (message.isNotEmpty()) {
            Text(
                message,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }

        if (error.isNotEmpty() && projectFolder != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
