
package com.faridtech.ai

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class ProjectTreeEntry(
    val file: File,
    val depth: Int
)

@Composable
fun AppBuilderScreen(
    context: Context,
    onBack: () -> Unit,
    initialProject: File? = null
) {
    val scope = rememberCoroutineScope()

    var projectName by remember { mutableStateOf("") }
    var packageName by remember {
        mutableStateOf("com.example.myapp")
    }
    var language by remember { mutableStateOf("Kotlin") }

    var projectFolder by remember { mutableStateOf<File?>(null) }
    var currentDirectory by remember { mutableStateOf<File?>(null) }
    var selectedFile by remember { mutableStateOf<File?>(null) }

    var editorText by remember { mutableStateOf("") }
    var entries by remember {
        mutableStateOf<List<ProjectTreeEntry>>(emptyList())
    }

    var newItemName by remember { mutableStateOf("") }
    var newItemIsFolder by remember { mutableStateOf(false) }

    var dialogTarget by remember { mutableStateOf<File?>(null) }
    var renameValue by remember { mutableStateOf("") }

    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    val packageRegex = Regex(
        "^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"
    )

    val editableExtensions = setOf(
        "kt", "java", "xml", "gradle", "kts",
        "properties", "txt", "md", "json",
        "html", "css", "js", "cpp", "h",
        "c", "cmake", "pro", "sql", "py"
    )

    fun isEditable(file: File): Boolean {
        return file.extension.lowercase() in editableExtensions
    }

    fun isInsideProject(file: File, root: File): Boolean {
        return try {
            file.canonicalFile.toPath()
                .startsWith(root.canonicalFile.toPath())
        } catch (_: Exception) {
            false
        }
    }

    suspend fun scanProject(root: File): List<ProjectTreeEntry> {
        return withContext(Dispatchers.IO) {
            val result = mutableListOf<ProjectTreeEntry>()

            fun scan(directory: File, depth: Int) {
                val children = directory.listFiles()
                    ?.filter { !it.name.startsWith(".") }
                    ?.sortedWith(
                        compareBy<File> { !it.isDirectory }
                            .thenBy { it.name.lowercase() }
                    )
                    ?: return

                children.forEach { child ->
                    result.add(ProjectTreeEntry(child, depth))

                    if (child.isDirectory) {
                        scan(child, depth + 1)
                    }
                }
            }

            scan(root, 0)
            result
        }
    }

    // بازکردن پروژه‌ای که از کتابخانه انتخاب شده است
    LaunchedEffect(initialProject) {
        val folder = initialProject ?: return@LaunchedEffect

        try {
            val projectsRoot = withContext(Dispatchers.IO) {
                File(context.filesDir, "projects").canonicalFile
            }

            val validFolder = withContext(Dispatchers.IO) {
                folder.canonicalFile
            }

            if (
                !validFolder.isDirectory ||
                !validFolder.toPath().startsWith(projectsRoot.toPath())
            ) {
                error = "مسیر پروژه معتبر نیست."
                return@LaunchedEffect
            }

            projectFolder = validFolder
            currentDirectory = validFolder
            projectName = validFolder.name
            selectedFile = null
            editorText = ""
            entries = scanProject(validFolder)

            message = "پروژه قبلی با موفقیت باز شد."
            error = ""

        } catch (_: Exception) {
            error = "بازکردن پروژه ناموفق بود."
        }
    }

    fun openFile(file: File) {
        val root = projectFolder ?: return

        if (!isInsideProject(file, root)) {
            error = "مسیر فایل معتبر نیست."
            return
        }

        if (!file.isFile || !isEditable(file)) {
            error = "این نوع فایل برای ویرایش متنی پشتیبانی نمی‌شود."
            return
        }

        scope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    file.readText()
                }

                selectedFile = file
                editorText = content
                error = ""
                message = ""
            } catch (_: Exception) {
                error = "خواندن فایل ناموفق بود."
            }
        }
    }

    fun saveFile() {
        val file = selectedFile ?: return
        val root = projectFolder ?: return

        if (!isInsideProject(file, root)) {
            error = "مسیر فایل معتبر نیست."
            return
        }

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    file.writeText(editorText)
                }

                message = "تغییرات فایل ذخیره شد."
                error = ""
            } catch (_: Exception) {
                error = "ذخیره فایل ناموفق بود."
            }
        }
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
                    AndroidProjectTemplate.create(
                        context = context,
                        projectName = projectName.trim(),
                        packageName = packageName.trim(),
                        language = language
                    )
                }

                projectFolder = folder
                currentDirectory = folder
                selectedFile = null
                editorText = ""

                entries = scanProject(folder)

                message = "پروژه با قالب استاندارد ساخته شد."

            } catch (e: Exception) {
                error = e.message ?: "ساخت پروژه ناموفق بود."
            } finally {
                loading = false
            }
        }
    }

    fun createItem() {
        val root = projectFolder ?: return
        val parent = currentDirectory ?: root
        val name = newItemName.trim()

        if (!isInsideProject(parent, root)) {
            error = "مسیر پوشه معتبر نیست."
            return
        }

        if (
            name.isBlank() ||
            name.contains("/") ||
            name.contains("\\") ||
            name == "." ||
            name == ".." ||
            name.startsWith(".") ||
            !name.matches(Regex("[a-zA-Z0-9_.-]+"))
        ) {
            error = "نام فایل یا پوشه معتبر نیست."
            return
        }

        val target = File(parent, name)

        if (target.exists()) {
            error = "این نام از قبل وجود دارد."
            return
        }

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val success = if (newItemIsFolder) {
                        target.mkdirs()
                    } else {
                        target.createNewFile()
                    }

                    check(success) {
                        "ساخت فایل یا پوشه ناموفق بود."
                    }
                }

                entries = scanProject(root)
                newItemName = ""

                message = if (newItemIsFolder) {
                    "پوشه جدید ساخته شد."
                } else {
                    "فایل جدید ساخته شد."
                }

                error = ""

                if (!newItemIsFolder && isEditable(target)) {
                    selectedFile = target
                    editorText = ""
                }

            } catch (e: Exception) {
                error = e.message ?: "عملیات ناموفق بود."
            }
        }
    }

    fun renameItem() {
        val root = projectFolder ?: return
        val target = dialogTarget ?: return
        val newName = renameValue.trim()

        if (!isInsideProject(target, root)) {
            error = "مسیر موردنظر معتبر نیست."
            showRenameDialog = false
            return
        }

        if (
            newName.isBlank() ||
            newName.contains("/") ||
            newName.contains("\\") ||
            newName == "." ||
            newName == ".." ||
            newName.startsWith(".") ||
            !newName.matches(Regex("[a-zA-Z0-9_.-]+"))
        ) {
            error = "نام جدید معتبر نیست."
            return
        }

        val destination = File(target.parentFile, newName)

        if (destination.exists()) {
            error = "فایل یا پوشه‌ای با این نام وجود دارد."
            return
        }

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(target.renameTo(destination)) {
                        "تغییر نام انجام نشد."
                    }
                }

                selectedFile = null
                editorText = ""

                currentDirectory = currentDirectory?.let {
                    if (it == target) destination else it
                }

                entries = scanProject(root)

                showRenameDialog = false
                dialogTarget = null
                message = "تغییر نام با موفقیت انجام شد."
                error = ""

            } catch (e: Exception) {
                error = e.message ?: "تغییر نام ناموفق بود."
            }
        }
    }

    fun deleteItem() {
        val root = projectFolder ?: return
        val target = dialogTarget ?: return

        if (!isInsideProject(target, root)) {
            error = "حذف این مسیر مجاز نیست."
            showDeleteDialog = false
            return
        }

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(target.deleteRecursively()) {
                        "حذف فایل یا پوشه ناموفق بود."
                    }
                }

                selectedFile = null
                editorText = ""

                val directory = currentDirectory

                if (
                    directory != null &&
                    isInsideProject(directory, root) &&
                    (
                        directory == target ||
                        directory.toPath().startsWith(target.toPath())
                    )
                ) {
                    currentDirectory = root
                }

                entries = scanProject(root)

                showDeleteDialog = false
                dialogTarget = null
                message = "حذف با موفقیت انجام شد."
                error = ""

            } catch (e: Exception) {
                error = e.message ?: "حذف انجام نشد."
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
            Text("← بازگشت")
        }

        Text(
            "ساخت اپلیکیشن",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Text(
            "ساخت پروژه اندروید و مدیریت فایل‌ها",
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
                        onValueChange = { projectName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("نام پروژه") },
                        placeholder = { Text("MyFirstApp") },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("شناسه بسته") },
                        placeholder = { Text("com.example.myapp") },
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
                            onClick = { language = "Kotlin" },
                            label = { Text("Kotlin") }
                        )

                        FilterChip(
                            selected = language == "Java",
                            onClick = { language = "Java" },
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

            val root = projectFolder!!

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
                        "قالب استاندارد Android Gradle",
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedButton(
                        onClick = {
                            projectFolder = null
                            currentDirectory = null
                            selectedFile = null
                            entries = emptyList()
                            editorText = ""
                            error = ""
                            message = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("بازگشت به ایجاد پروژه")
                    }
                }
            }

            Text(
                "ساختار پروژه",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                OutlinedButton(
                    onClick = {
                        currentDirectory = root
                        selectedFile = null
                        editorText = ""
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("📁 ریشه پروژه")
                }

                OutlinedButton(
                    onClick = {
                        val parent = currentDirectory?.parentFile

                        if (
                            parent != null &&
                            (parent == root || isInsideProject(parent, root))
                        ) {
                            currentDirectory = parent
                            selectedFile = null
                            editorText = ""
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("↑ پوشه بالاتر")
                }
            }

            Text(
                "مسیر فعلی: " +
                    (
                        currentDirectory
                            ?.relativeToOrNull(root)
                            ?.path
                            ?: "ریشه پروژه"
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {

                    entries.forEach { entry ->

                        val file = entry.file

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selectedFile == file) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(
                                    start = (8 + entry.depth * 12).dp,
                                    end = 6.dp,
                                    top = 5.dp,
                                    bottom = 5.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            TextButton(
                                onClick = {
                                    if (file.isDirectory) {
                                        currentDirectory = file
                                        selectedFile = null
                                        editorText = ""
                                    } else {
                                        openFile(file)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    (if (file.isDirectory) "📁 " else "📄 ") +
                                        file.name,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            TextButton(
                                onClick = {
                                    dialogTarget = file
                                    renameValue = file.name
                                    showRenameDialog = true
                                    error = ""
                                }
                            ) {
                                Text("تغییر نام")
                            }

                            TextButton(
                                onClick = {
                                    dialogTarget = file
                                    showDeleteDialog = true
                                    error = ""
                                }
                            ) {
                                Text(
                                    "حذف",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    if (entries.isEmpty()) {
                        Text(
                            "این پروژه هنوز فایلی ندارد.",
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider()

            Text(
                "ایجاد فایل یا پوشه",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = newItemName,
                onValueChange = { newItemName = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        if (newItemIsFolder) {
                            "نام پوشه جدید"
                        } else {
                            "نام فایل جدید"
                        }
                    )
                },
                placeholder = {
                    Text(
                        if (newItemIsFolder) "utils" else "MyFile.kt"
                    )
                },
                singleLine = true
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !newItemIsFolder,
                    onClick = { newItemIsFolder = false },
                    label = { Text("📄 فایل") }
                )

                FilterChip(
                    selected = newItemIsFolder,
                    onClick = { newItemIsFolder = true },
                    label = { Text("📁 پوشه") }
                )
            }

            Button(
                onClick = { createItem() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("＋ ایجاد")
            }

            HorizontalDivider()

            Text(
                selectedFile?.name ?: "ویرایشگر کد",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(330.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                BasicTextField(
                    value = editorText,
                    onValueChange = { editorText = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    decorationBox = { innerTextField ->
                        Box {
                            if (editorText.isEmpty()) {
                                Text(
                                    "کد برنامه را اینجا وارد کنید...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            innerTextField()
                        }
                    }
                )
            }

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
                        "مرحله ساخت APK",
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "قالب استاندارد پروژه متصل شده است. اجرای Gradle و تولید APK هنوز به سیستم ساخت متصل نشده است.",
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

        if (error.isNotEmpty()) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    if (showRenameDialog && dialogTarget != null) {
        AlertDialog(
            onDismissRequest = {
                showRenameDialog = false
                dialogTarget = null
            },
            title = { Text("تغییر نام") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text("نام جدید") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { renameItem() }) {
                    Text("ذخیره")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRenameDialog = false
                        dialogTarget = null
                    }
                ) {
                    Text("انصراف")
                }
            }
        )
    }

    if (showDeleteDialog && dialogTarget != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                dialogTarget = null
            },
            title = { Text("تأیید حذف") },
            text = {
                Text(
                    "آیا از حذف «${dialogTarget?.name}» مطمئن هستید؟ اگر پوشه باشد، تمام محتویات آن نیز حذف می‌شود."
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteItem() }) {
                    Text(
                        "حذف",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        dialogTarget = null
                    }
                ) {
                    Text("انصراف")
                }
            }
        )
    }
}

private fun File.relativeToOrNull(base: File): File? {
    return try {
        relativeTo(base)
    } catch (_: Exception) {
        null
    }
}
