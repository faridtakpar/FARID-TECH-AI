package com.faridtech.ai

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val ProgrammingGreen = Color(0xFF19C37D)

private val programmingLanguages = listOf(
    "Kotlin", "Java", "Python", "C++", "C",
    "HTML", "CSS", "JavaScript", "PHP",
    "SQL", "Dart", "Swift"
)

private val programmingTasks = listOf(
    "تولید کد",
    "اصلاح کد",
    "رفع خطا",
    "توضیح کد"
)

private val allowedProjectExtensions = setOf(
    "kt", "java", "xml", "gradle", "kts",
    "txt", "md", "json", "html", "css",
    "js", "cpp", "h", "c", "cmake",
    "pro", "sql", "py", "dart", "swift"
)

private val blockedProjectNames = setOf(
    ".env",
    ".env.local",
    "local.properties",
    "gradle.properties",
    "key.properties"
)

private fun isSafeProjectFile(
    projectRoot: File,
    file: File
): Boolean {
    return try {
        val root = projectRoot.canonicalFile
        val target = file.canonicalFile

        val rootPath = root.path + File.separator

        if (!target.path.startsWith(rootPath)) {
            return false
        }

        if (!target.isFile || !target.canRead()) {
            return false
        }

        if (target.length() > 256 * 1024) {
            return false
        }

        val name = target.name.lowercase()

        if (name in blockedProjectNames) {
            return false
        }

        if (
            name.endsWith(".keystore") ||
            name.endsWith(".jks") ||
            name.endsWith(".pem") ||
            name.endsWith(".p12") ||
            name.endsWith(".pfx")
        ) {
            return false
        }

        val extension = target.extension.lowercase()

        extension in allowedProjectExtensions
    } catch (_: Exception) {
        false
    }
}

private suspend fun scanSafeProjectFiles(
    context: Context,
    project: File
): List<File> = withContext(Dispatchers.IO) {

    val projectsDirectory = File(
        context.filesDir,
        "projects"
    ).canonicalFile

    val projectRoot = project.canonicalFile

    val projectsPath =
        projectsDirectory.path + File.separator

    if (
        !projectRoot.isDirectory ||
        !projectRoot.path.startsWith(projectsPath)
    ) {
        return@withContext emptyList()
    }

    val result = mutableListOf<File>()

    projectRoot.walkTopDown()
        .onEnter { directory ->

            if (directory == projectRoot) {
                true
            } else {
                val name = directory.name.lowercase()

                !name.startsWith(".") &&
                    name != "build" &&
                    name != ".gradle" &&
                    name != ".idea" &&
                    name != "node_modules"
            }
        }
        .forEach { file ->

            if (result.size >= 200) {
                return@forEach
            }

            if (
                file.isFile &&
                isSafeProjectFile(projectRoot, file)
            ) {
                result.add(file)
            }
        }

    result.sortedBy {
        it.relativeTo(projectRoot).path
    }
}

@Composable
fun ProgrammingScreen(
    context: Context,
    onBack: () -> Unit,
    project: File? = null
) {
    val engine = remember(context) {
        LocalAIEngine(context.applicationContext)
    }

    val scope = rememberCoroutineScope()

    var selectedLanguage by remember {
        mutableStateOf("Kotlin")
    }

    var selectedTask by remember {
        mutableStateOf("تولید کد")
    }

    var prompt by remember {
        mutableStateOf("")
    }

    var code by remember {
        mutableStateOf("")
    }

    var languageMenuExpanded by remember {
        mutableStateOf(false)
    }

    var errorMessage by remember {
        mutableStateOf("")
    }

    var result by remember {
        mutableStateOf("")
    }

    var generating by remember {
        mutableStateOf(false)
    }

    var checkingModel by remember {
        mutableStateOf(true)
    }

    var modelAvailable by remember {
        mutableStateOf(false)
    }

    // Project integration

    val projectFiles = remember {
        mutableStateListOf<File>()
    }

    var selectedProjectFile by remember {
        mutableStateOf<File?>(null)
    }

    var showProjectFiles by remember {
        mutableStateOf(false)
    }

    var loadingProjectFiles by remember {
        mutableStateOf(false)
    }

    var loadingFile by remember {
        mutableStateOf(false)
    }

    var projectError by remember {
        mutableStateOf("")
    }

    val projectRoot = remember(project) {
        project?.absoluteFile
    }

    LaunchedEffect(engine) {
        checkingModel = true

        try {
            modelAvailable = engine.checkModel()
        } catch (_: Exception) {
            modelAvailable = false
        }

        checkingModel = false
    }

    // Load safe project file list

    LaunchedEffect(projectRoot) {

        projectFiles.clear()
        selectedProjectFile = null
        projectError = ""

        val currentProject = projectRoot ?: return@LaunchedEffect

        loadingProjectFiles = true

        try {
            val files = scanSafeProjectFiles(
                context,
                currentProject
            )

            projectFiles.addAll(files)

            if (files.isEmpty()) {
                projectError =
                    "فایل متنی قابل بررسی در پروژه پیدا نشد."
            }

        } catch (e: Exception) {
            projectError =
                e.message ?: "خواندن فهرست پروژه ناموفق بود."
        } finally {
            loadingProjectFiles = false
        }
    }

    fun selectProjectFile(file: File) {

        if (loadingFile || generating) return

        val root = projectRoot ?: return

        scope.launch {

            loadingFile = true
            errorMessage = ""
            projectError = ""

            try {
                val content = withContext(Dispatchers.IO) {

                    if (!isSafeProjectFile(root, file)) {
                        throw SecurityException(
                            "این فایل اجازه خواندن ندارد."
                        )
                    }

                    file.readText(Charsets.UTF_8)
                }

                code = content
                selectedProjectFile = file

                val extension = file.extension.lowercase()

                selectedLanguage = when (extension) {
                    "kt" -> "Kotlin"
                    "java" -> "Java"
                    "py" -> "Python"
                    "cpp" -> "C++"
                    "c", "h" -> "C"
                    "html" -> "HTML"
                    "css" -> "CSS"
                    "js" -> "JavaScript"
                    "php" -> "PHP"
                    "sql" -> "SQL"
                    "dart" -> "Dart"
                    "swift" -> "Swift"
                    else -> selectedLanguage
                }

                prompt =
                    "فایل انتخاب‌شده را از نظر ساختار، خطاها و کیفیت کد بررسی کن."

                result = ""
                showProjectFiles = false

            } catch (e: Exception) {
                projectError =
                    e.message ?: "خواندن فایل انجام نشد."
            } finally {
                loadingFile = false
            }
        }
    }

    fun sendRequest() {

        if (generating) return

        if (prompt.isBlank() && code.isBlank()) {
            errorMessage =
                "لطفاً توضیح درخواست یا کد خود را وارد کنید."
            return
        }

        if (!modelAvailable) {
            errorMessage =
                "مدل هوش مصنوعی نصب نشده است. ابتدا از بخش مدیریت مدل آن را دریافت کنید."
            return
        }

        val selectedPath = selectedProjectFile?.let { file ->

            val root = projectRoot

            if (root != null) {
                try {
                    file.canonicalFile
                        .relativeTo(root.canonicalFile)
                        .path
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }

        val formattedPrompt = buildString {

            appendLine(
                "You are a professional programming assistant."
            )

            appendLine(
                "Answer in Persian unless code requires otherwise."
            )

            appendLine("Programming language: $selectedLanguage")
            appendLine("Task: $selectedTask")

            if (projectRoot != null) {
                appendLine()
                appendLine("Project context:")
                appendLine(
                    "The user selected a local project for analysis."
                )
            }

            if (selectedPath != null) {
                appendLine("Selected file: $selectedPath")
            }

            appendLine()
            appendLine("User request:")
            appendLine(
                prompt.ifBlank {
                    "Please analyze the provided code."
                }
            )

            if (code.isNotBlank()) {
                appendLine()
                appendLine("User code:")
                appendLine("```$selectedLanguage")
                appendLine(code)
                appendLine("```")
            }

            appendLine()
            appendLine(
                "Analyze carefully and provide technically accurate advice."
            )

            appendLine(
                "If code changes are needed, explain exactly which file should change."
            )

            appendLine(
                "Do not claim that any local file has been modified."
            )

            appendLine(
                "Never request passwords, private keys, or secret credentials."
            )

            appendLine(
                "When generating or fixing code, provide complete code where practical."
            )
        }

        generating = true
        errorMessage = ""
        result = ""

        scope.launch {

            try {
                result = engine.generate(formattedPrompt)

                if (result.isBlank()) {
                    errorMessage = "مدل پاسخ خالی برگرداند."
                }

            } catch (e: Exception) {

                errorMessage =
                    "خطا در اجرای هوش مصنوعی: ${
                        e.message ?: "خطای نامشخص"
                    }"

            } finally {
                generating = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {

        // Header

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            IconButton(onClick = onBack) {
                Text("←", fontSize = 27.sp)
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "دستیار برنامه‌نویسی",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )

                Text(
                    "FARID TECH AI",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Surface(
                color = ProgrammingGreen.copy(alpha = 0.13f),
                shape = RoundedCornerShape(50.dp)
            ) {
                Text(
                    "AI",
                    modifier = Modifier.padding(
                        horizontal = 14.dp,
                        vertical = 8.dp
                    ),
                    color = ProgrammingGreen,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {

            // Welcome

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF173D36)
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("👨‍💻", fontSize = 32.sp)

                    Text(
                        "همراه هوشمند برنامه‌نویسی",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )

                    Text(
                        "کد تولید کن، خطاها را پیدا کن و پروژه‌هایت را توسعه بده.",
                        color = Color(0xFFD9EEE5),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Selected project

            if (projectRoot != null) {

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor =
                            MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {

                        Text(
                            "📁 پروژه متصل",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )

                        Text(
                            projectRoot.name,
                            color = ProgrammingGreen,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            when {
                                loadingProjectFiles ->
                                    "در حال بررسی فایل‌های پروژه..."

                                else ->
                                    "${projectFiles.size} فایل مجاز برای بررسی پیدا شد."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )

                        OutlinedButton(
                            onClick = {
                                showProjectFiles = true
                            },
                            enabled = !loadingProjectFiles &&
                                !loadingFile &&
                                !generating,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("انتخاب فایل از پروژه")
                        }

                        if (selectedProjectFile != null) {
                            Text(
                                "فایل انتخاب‌شده: ${
                                    selectedProjectFile!!.name
                                }",
                                color = ProgrammingGreen,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (loadingFile) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (projectError.isNotBlank()) {
                            Text(
                                projectError,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // Model status

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Text(
                        when {
                            checkingModel -> "⏳"
                            modelAvailable -> "🟢"
                            else -> "⚠️"
                        },
                        fontSize = 20.sp
                    )

                    Spacer(Modifier.width(10.dp))

                    Column {
                        Text(
                            "وضعیت مدل هوش مصنوعی",
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            when {
                                checkingModel ->
                                    "در حال بررسی فایل مدل..."

                                modelAvailable ->
                                    "مدل محلی شناسایی شد"

                                else ->
                                    "مدل نصب نشده است"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Language

            Column(
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {

                Text(
                    "زبان برنامه‌نویسی",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )

                Box {
                    OutlinedButton(
                        onClick = {
                            languageMenuExpanded = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {
                            Text(selectedLanguage)
                            Text("⌄")
                        }
                    }

                    DropdownMenu(
                        expanded = languageMenuExpanded,
                        onDismissRequest = {
                            languageMenuExpanded = false
                        }
                    ) {
                        programmingLanguages.forEach { language ->
                            DropdownMenuItem(
                                text = { Text(language) },
                                onClick = {
                                    selectedLanguage = language
                                    languageMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Task

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {

                Text(
                    "نوع درخواست",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    programmingTasks.take(2).forEach { task ->
                        FilterChip(
                            selected = selectedTask == task,
                            onClick = { selectedTask = task },
                            label = { Text(task) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    programmingTasks.drop(2).forEach { task ->
                        FilterChip(
                            selected = selectedTask == task,
                            onClick = { selectedTask = task },
                            label = { Text(task) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Prompt

            Column(
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {

                Text(
                    "توضیح درخواست",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )

                OutlinedTextField(
                    value = prompt,
                    onValueChange = {
                        prompt = it
                        errorMessage = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "مثلاً خطاهای این فایل را پیدا و توضیح بده..."
                        )
                    },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(16.dp)
                )
            }

            // Code editor

            Column(
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "کد شما",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        selectedLanguage,
                        color = ProgrammingGreen,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF151A22)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("●", color = Color(0xFFFF6B6B))
                            Spacer(Modifier.width(6.dp))
                            Text("●", color = Color(0xFFFFC857))
                            Spacer(Modifier.width(6.dp))
                            Text("●", color = ProgrammingGreen)
                            Spacer(Modifier.width(12.dp))

                            Text(
                                "Code Editor",
                                color = Color(0xFFB5BAC4),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        HorizontalDivider(
                            color = Color(0xFF343B47)
                        )

                        BasicTextField(
                            value = code,
                            onValueChange = {
                                code = it
                                errorMessage = ""
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 150.dp),
                            textStyle = TextStyle(
                                color = Color(0xFFE5EAF0),
                                fontSize = 13.sp,
                                lineHeight = 21.sp
                            ),
                            cursorBrush = SolidColor(ProgrammingGreen),
                            decorationBox = { innerTextField ->

                                Box {
                                    if (code.isEmpty()) {
                                        Text(
                                            "کد خود را اینجا وارد کنید...",
                                            color = Color(0xFF78818F),
                                            fontSize = 13.sp
                                        )
                                    }

                                    innerTextField()
                                }
                            }
                        )
                    }
                }
            }

            if (errorMessage.isNotEmpty()) {
                Text(
                    errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Submit

            Button(
                onClick = { sendRequest() },
                enabled = !generating &&
                    !checkingModel &&
                    !loadingFile,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ProgrammingGreen,
                    contentColor = Color(0xFF062A1C)
                )
            ) {

                if (generating) {

                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color(0xFF062A1C),
                        strokeWidth = 2.dp
                    )

                    Spacer(Modifier.width(10.dp))

                    Text("هوش مصنوعی در حال پردازش...")

                } else {

                    Text(
                        "ارسال به هوش مصنوعی  ➜",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            // Result

            if (result.isNotBlank()) {

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {

                    Text(
                        "پاسخ هوش مصنوعی",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {

                            Text(
                                result,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium
                            )

                            OutlinedButton(
                                onClick = {
                                    prompt = ""
                                    code = ""
                                    result = ""
                                    errorMessage = ""
                                    selectedProjectFile = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("درخواست جدید")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }

    // Project file picker

    if (showProjectFiles) {

        AlertDialog(
            onDismissRequest = {
                showProjectFiles = false
            },
            title = {
                Text("انتخاب فایل پروژه")
            },
            text = {

                if (projectFiles.isEmpty()) {

                    Text("فایل قابل بررسی وجود ندارد.")

                } else {

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(6.dp)
                    ) {

                        items(
                            items = projectFiles,
                            key = { it.absolutePath }
                        ) { file ->

                            val relativePath = try {
                                file.relativeTo(
                                    projectRoot ?: file.parentFile!!
                                ).path
                            } catch (_: Exception) {
                                file.name
                            }

                            TextButton(
                                onClick = {
                                    selectProjectFile(file)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        file.name,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Text(
                                        relativePath,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showProjectFiles = false
                    }
                ) {
                    Text("بستن")
                }
            }
        )
    }
}
