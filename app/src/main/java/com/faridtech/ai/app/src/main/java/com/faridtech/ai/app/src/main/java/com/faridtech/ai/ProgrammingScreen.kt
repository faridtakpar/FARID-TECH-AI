
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

private const val AI_CODE_START = "<<<FARID_CODE_START>>>"
private const val AI_CODE_END = "<<<FARID_CODE_END>>>"

private fun isSafeProjectFile(
    projectRoot: File,
    file: File
): Boolean {
    return try {
        val root = projectRoot.canonicalFile
        val target = file.canonicalFile
        val rootPath = root.path + File.separator

        if (!target.path.startsWith(rootPath)) return false
        if (!target.isFile || !target.canRead()) return false
        if (target.length() > 256 * 1024) return false

        val name = target.name.lowercase()

        if (name in blockedProjectNames) return false

        if (
            name.endsWith(".keystore") ||
            name.endsWith(".jks") ||
            name.endsWith(".pem") ||
            name.endsWith(".p12") ||
            name.endsWith(".pfx")
        ) return false

        target.extension.lowercase() in allowedProjectExtensions
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
    val projectsPath = projectsDirectory.path + File.separator

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
            if (
                result.size < 200 &&
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

private fun extractProposedCode(response: String): String? {
    val start = response.indexOf(AI_CODE_START)
    val end = response.indexOf(AI_CODE_END)

    if (start < 0 || end <= start) return null

    var content = response.substring(
        start + AI_CODE_START.length,
        end
    ).trim()

    if (content.startsWith("```")) {
        val firstNewLine = content.indexOf('\n')
        if (firstNewLine >= 0) {
            content = content.substring(firstNewLine + 1)
        }
    }

    if (content.endsWith("```")) {
        content = content.dropLast(3).trimEnd()
    }

    return content.takeIf { it.isNotBlank() }
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

    val changeManager = remember(context) {
        ProjectFileChangeManager(context.applicationContext)
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

    var pendingChange by remember {
        mutableStateOf<ProjectFileChangePreview?>(null)
    }

    var originalPreviewContent by remember {
        mutableStateOf("")
    }

    var showChangeReview by remember {
        mutableStateOf(false)
    }

    var applyingChange by remember {
        mutableStateOf(false)
    }

    // اطلاعات آخرین نسخه پشتیبان برای بازگردانی
    var lastBackupPath by remember {
        mutableStateOf<String?>(null)
    }

    var undoTargetFile by remember {
        mutableStateOf<File?>(null)
    }

    var showRestoreConfirmation by remember {
        mutableStateOf(false)
    }

    var restoringBackup by remember {
        mutableStateOf(false)
    }

    val projectRoot = remember(project) {
        project?.absoluteFile
    }

    val busy = generating ||
        applyingChange ||
        restoringBackup ||
        loadingFile

    LaunchedEffect(engine) {
        checkingModel = true

        try {
            modelAvailable = engine.checkModel()
        } catch (_: Exception) {
            modelAvailable = false
        }

        checkingModel = false
    }

    LaunchedEffect(projectRoot) {
        projectFiles.clear()
        selectedProjectFile = null
        pendingChange = null
        showChangeReview = false
        projectError = ""

        // پشتیبان آخر را فقط تا زمانی که همین صفحه و پروژه فعال است نگه می‌داریم.
        lastBackupPath = null
        undoTargetFile = null

        val currentProject = projectRoot
            ?: return@LaunchedEffect

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
        if (busy) return

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

                selectedLanguage = when (
                    file.extension.lowercase()
                ) {
                    "kt" -> "Kotlin"
                    "java" -> "Java"
                    "py" -> "Python"
                    "cpp" -> "C++"
                    "c", "h" -> "C"
                    "html" -> "HTML"
                    "css" -> "CSS"
                    "js" -> "JavaScript"
                    "sql" -> "SQL"
                    "dart" -> "Dart"
                    "swift" -> "Swift"
                    else -> selectedLanguage
                }

                prompt =
                    "فایل انتخاب‌شده را از نظر ساختار، خطاها و کیفیت کد بررسی کن."

                result = ""
                pendingChange = null
                showChangeReview = false

            } catch (e: Exception) {
                projectError =
                    e.message ?: "خواندن فایل انجام نشد."
            } finally {
                loadingFile = false
            }
        }
    }

    fun sendRequest() {
        if (busy) return

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

        val isFileCorrection =
            selectedProjectFile != null &&
                projectRoot != null &&
                (
                    selectedTask == "اصلاح کد" ||
                    selectedTask == "رفع خطا"
                )

        val submittedCode = code
        val submittedPrompt = prompt
        val submittedFile = selectedProjectFile
        val submittedRoot = projectRoot

        if (isFileCorrection && submittedCode.isBlank()) {
            errorMessage = "محتوای فایل برای اصلاح خالی است."
            return
        }

        val selectedPath = submittedFile?.let { file ->
            val root = submittedRoot

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

            if (submittedRoot != null) {
                appendLine("The user selected a local project.")
            }

            if (selectedPath != null) {
                appendLine("Selected file: $selectedPath")
            }

            appendLine()
            appendLine("User request:")
            appendLine(
                submittedPrompt.ifBlank {
                    "Please analyze the provided code."
                }
            )

            appendLine()
            appendLine("User code:")
            appendLine("```$selectedLanguage")
            appendLine(submittedCode)
            appendLine("```")

            if (isFileCorrection) {
                appendLine()
                appendLine("IMPORTANT FILE CORRECTION RULES:")
                appendLine(
                    "Return the COMPLETE corrected file, not a partial snippet."
                )
                appendLine(
                    "Do not omit imports, classes, functions, or unchanged sections."
                )
                appendLine(
                    "Do not add explanations inside the code."
                )
                appendLine(
                    "Do not claim that you modified the local file."
                )
                appendLine(
                    "If you cannot safely produce the complete file, explain the limitation and do not produce a code block."
                )
                appendLine()
                appendLine(
                    "If you can provide the complete corrected file, format your response exactly like this:"
                )
                appendLine(AI_CODE_START)
                appendLine("COMPLETE FILE CONTENT HERE")
                appendLine(AI_CODE_END)
                appendLine(
                    "Replace the placeholder with the complete source code."
                )
            } else {
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
        }

        generating = true
        errorMessage = ""
        result = ""
        pendingChange = null
        showChangeReview = false

        scope.launch {
            try {
                val response = engine.generate(formattedPrompt)

                if (response.isBlank()) {
                    errorMessage = "مدل پاسخ خالی برگرداند."
                    return@launch
                }

                if (!isFileCorrection) {
                    result = response
                    return@launch
                }

                val proposedContent =
                    extractProposedCode(response)

                if (proposedContent == null) {
                    result = response
                    errorMessage =
                        "مدل کد کامل را در قالب موردنیاز برنگرداند؛ هیچ فایلی تغییر نکرد."
                    return@launch
                }

                val root = submittedRoot
                val file = submittedFile

                if (root == null || file == null) {
                    errorMessage =
                        "پروژه یا فایل انتخاب‌شده معتبر نیست."
                    return@launch
                }

                val diskContent = withContext(Dispatchers.IO) {
                    if (!isSafeProjectFile(root, file)) {
                        throw SecurityException(
                            "فایل دیگر در مسیر مجاز قرار ندارد."
                        )
                    }

                    file.readText(Charsets.UTF_8)
                }

                if (diskContent != submittedCode) {
                    errorMessage =
                        "محتوای فایل از زمان بارگذاری تغییر کرده است. فایل را دوباره انتخاب و تحلیل کن."
                    return@launch
                }

                val preview = changeManager.prepareChange(
                    project = root,
                    file = file,
                    proposedContent = proposedContent
                )

                if (preview == null) {
                    errorMessage =
                        "ساخت پیشنهاد اصلاح امن ممکن نشد. فایل اصلی تغییر نکرد."
                    return@launch
                }

                originalPreviewContent = submittedCode
                pendingChange = preview
                result =
                    "پیشنهاد اصلاح آماده است. نسخه‌ها را در پنجره بازبینی بررسی کن."
                showChangeReview = true

            } catch (e: Exception) {
                errorMessage =
                    "خطا در اجرای درخواست: ${
                        e.message ?: "خطای نامشخص"
                    }"
            } finally {
                generating = false
            }
        }
    }

    fun confirmProjectChange() {
        val preview = pendingChange ?: return

        if (applyingChange || restoringBackup) return

        scope.launch {
            applyingChange = true
            errorMessage = ""

            try {
                val changeResult = changeManager.applyChange(
                    preview = preview,
                    ownerConfirmed = true
                )

                when (changeResult) {
                    is ProjectFileChangeResult.Applied -> {
                        code = preview.proposedContent

                        // ثبت پشتیبان آخرین اصلاح
                        lastBackupPath = changeResult.backupPath
                        undoTargetFile = File(preview.filePath)

                        result =
                            "اصلاح با موفقیت اعمال شد. نسخه پشتیبان ذخیره شد؛ امکان بازگردانی وجود دارد."

                        projectError =
                            "نسخه پشتیبان آخرین اصلاح آماده بازگردانی است."

                        pendingChange = null
                        showChangeReview = false
                    }

                    is ProjectFileChangeResult.Rejected -> {
                        errorMessage = changeResult.reason
                    }
                }

            } catch (e: Exception) {
                errorMessage =
                    e.message ?: "اعمال اصلاح ناموفق بود."
            } finally {
                applyingChange = false
            }
        }
    }

    fun restoreLastChange() {
        val backup = lastBackupPath ?: return
        val target = undoTargetFile ?: return
        val root = projectRoot ?: return

        if (restoringBackup || applyingChange) return

        scope.launch {
            restoringBackup = true
            errorMessage = ""

            try {
                val restoreResult = changeManager.restoreBackup(
                    project = root,
                    file = target,
                    backupPath = backup,
                    ownerConfirmed = true
                )

                when (restoreResult) {
                    is ProjectFileRestoreResult.Restored -> {
                        val restoredContent =
                            withContext(Dispatchers.IO) {
                                target.readText(Charsets.UTF_8)
                            }

                        // فقط اگر همان فایل هنوز در ویرایشگر انتخاب شده باشد،
                        // محتوای ویرایشگر را به نسخه بازگردانی‌شده تغییر می‌دهیم.
                        if (
                            selectedProjectFile?.canonicalPath ==
                            target.canonicalPath
                        ) {
                            code = restoredContent
                        }

                        result =
                            "بازگردانی موفق بود. فایل به نسخه قبل از آخرین اصلاح برگشت."

                        projectError =
                            "آخرین اصلاح بازگردانی شد. نسخه پشتیبان همچنان حفظ شده است."

                        lastBackupPath = null
                        undoTargetFile = null
                        showRestoreConfirmation = false
                    }

                    is ProjectFileRestoreResult.Rejected -> {
                        errorMessage = restoreResult.reason
                        showRestoreConfirmation = false
                    }
                }

            } catch (e: Exception) {
                errorMessage =
                    e.message ?: "بازگردانی نسخه پشتیبان ناموفق بود."
            } finally {
                restoringBackup = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                enabled = !busy
            ) {
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

            if (projectRoot != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
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
                            if (loadingProjectFiles) {
                                "در حال بررسی فایل‌های پروژه..."
                            } else {
                                "${projectFiles.size} فایل مجاز برای بررسی پیدا شد."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )

                        OutlinedButton(
                            onClick = { showProjectFiles = true },
                            enabled = !loadingProjectFiles && !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("انتخاب فایل از پروژه")
                        }

                        selectedProjectFile?.let { file ->
                            Text(
                                "فایل انتخاب‌شده: ${file.name}",
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        // دکمه بازگردانی آخرین اصلاح
                        if (lastBackupPath != null) {
                            HorizontalDivider()

                            Text(
                                "نسخه پشتیبان آخرین اصلاح آماده است.",
                                color = ProgrammingGreen,
                                style = MaterialTheme.typography.bodySmall
                            )

                            Button(
                                onClick = {
                                    showRestoreConfirmation = true
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFB45309),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("↶  بازگردانی آخرین اصلاح")
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
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
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
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
                            enabled = !busy,
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
                            enabled = !busy,
                            label = { Text(task) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

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
                    enabled = !busy,
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
                                pendingChange = null
                                showChangeReview = false
                            },
                            enabled = !busy,
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

            Button(
                onClick = { sendRequest() },
                enabled = !busy &&
                    !checkingModel,
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
                        shape = RoundedCornerShape(18.dp)
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
                                    pendingChange = null
                                    showChangeReview = false
                                },
                                enabled = !busy,
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

    if (showProjectFiles) {
        AlertDialog(
            onDismissRequest = {
                if (!busy) showProjectFiles = false
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
                        verticalArrangement = Arrangement.spacedBy(6.dp)
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
                                enabled = !busy,
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

    // پنجره بازبینی و تأیید اصلاح
    val preview = pendingChange

    if (showChangeReview && preview != null) {
        ProjectChangeReviewDialog(
            fileName = selectedProjectFile?.name ?: "فایل پروژه",
            originalContent = originalPreviewContent,
            proposedContent = preview.proposedContent,
            applying = applyingChange,
            onConfirm = {
                confirmProjectChange()
            },
            onCancel = {
                if (!applyingChange) {
                    showChangeReview = false
                    pendingChange = null
                    result =
                        "پیشنهاد اصلاح رد شد؛ فایل اصلی بدون تغییر باقی ماند."
                }
            }
        )
    }

    // تأیید جداگانه برای بازگردانی نسخه پشتیبان
    if (showRestoreConfirmation) {
        AlertDialog(
            onDismissRequest = {
                if (!restoringBackup) {
                    showRestoreConfirmation = false
                }
            },
            title = {
                Text("بازگردانی آخرین اصلاح؟")
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "فایل زیر به نسخه قبل از آخرین اصلاح برمی‌گردد:"
                    )

                    Text(
                        undoTargetFile?.name ?: "فایل پروژه",
                        color = ProgrammingGreen,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "اگر فایل پس از اصلاح تغییر کرده باشد، عملیات برای جلوگیری از حذف تغییرات جدید متوقف می‌شود."
                    )

                    Text(
                        "نسخه پشتیبان پس از بازگردانی حذف نخواهد شد.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        restoreLastChange()
                    },
                    enabled = !restoringBackup,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB45309)
                    )
                ) {
                    if (restoringBackup) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("تأیید و بازگردانی")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirmation = false
                    },
                    enabled = !restoringBackup
                ) {
                    Text("انصراف")
                }
            }
        )
    }
}
