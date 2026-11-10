
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
            val targetPath = file.canonicalFile.toPath()
            val rootPath = root.canonicalFile.toPath()
            targetPath.startsWith(rootPath)
        } catch (_: Exception) {
            false
        }
    }

    fun isSameOrInside(file: File, parent: File): Boolean {
        return try {
            val filePath = file.canonicalFile.toPath()
            val parentPath = parent.canonicalFile.toPath()
            filePath.startsWith(parentPath)
        } catch (_: Exception) {
            false
        }
    }

    fun validItemName(name: String): Boolean {
        return name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            !name.startsWith(".") &&
            !name.contains("/") &&
            !name.contains("\\") &&
            !name.contains(":") &&
            name.matches(Regex("[\\p{L}\\p{N}_. -]+"))
    }

    suspend fun scanProject(root: File): List<ProjectTreeEntry> {
        return withContext(Dispatchers.IO) {
            val result = mutableListOf<ProjectTreeEntry>()
            val canonicalRoot = root.canonicalFile

            fun scan(directory: File, depth: Int) {
                if (depth > 12) return

                val children = directory.listFiles()
                    ?.filter { child ->
                        !child.name.startsWith(".") &&
                            child.name !in setOf("build", ".gradle") &&
                            isInsideProject(child, canonicalRoot)
                    }
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

            scan(canonicalRoot, 0)
            result
        }
    }

    suspend fun saveProjectMetadata(root: File) {
        val name = projectName
        val packageId = packageName
        val projectLanguage = language

        withContext(Dispatchers.IO) {
            check(
                ProjectMetadataStore.save(
                    projectDirectory = root,
                    name = name,
                    packageName = packageId,
                    language = projectLanguage
                )
            ) {
                "ذخیره اطلاعات پروژه ناموفق بود."
            }

            root.setLastModified(System.currentTimeMillis())
        }
    }

    suspend fun refreshProject(root: File) {
        entries = scanProject(root)
    }

    // بازیابی پروژه انتخاب‌شده از کتابخانه
    LaunchedEffect(initialProject) {
        val folder = initialProject ?: return@LaunchedEffect

        loading = true

        try {
            val projectsRoot = withContext(Dispatchers.IO) {
                File(context.filesDir, "projects").canonicalFile
            }

            val validFolder = withContext(Dispatchers.IO) {
                folder.canonicalFile
            }

            val valid = withContext(Dispatchers.IO) {
                validFolder.isDirectory &&
                    validFolder.toPath().startsWith(
                        projectsRoot.toPath()
                    ) &&
                    validFolder != projectsRoot
            }

            if (!valid) {
                error = "مسیر پروژه معتبر نیست."
                return@LaunchedEffect
            }

            val metadata = withContext(Dispatchers.IO) {
                ProjectMetadataStore.load(validFolder)
            }

            projectFolder = validFolder
            currentDirectory = validFolder

            projectName = metadata?.name
                ?.takeIf { it.isNotBlank() }
                ?: validFolder.name

            packageName = metadata?.packageName
                ?.takeIf { it.isNotBlank() }
                ?: "com.example.myapp"

            language = metadata?.language
                ?.takeIf { it.isNotBlank() }
                ?: "Kotlin"

            selectedFile = null
            editorText = ""

            entries = scanProject(validFolder)

            message = "پروژه با موفقیت باز شد."
            error = ""

        } catch (e: Exception) {
            error = e.message ?: "بازکردن پروژه ناموفق بود."
        } finally {
            loading = false
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
        val content = editorText

        if (!isInsideProject(file, root)) {
            error = "مسیر فایل معتبر نیست."
            return
        }

        if (!file.isFile || !isEditable(file)) {
            error = "ذخیره این نوع فایل مجاز نیست."
            return
        }

        if (loading) return
        loading = true

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    file.writeText(content)
                }

                saveProjectMetadata(root)
                refreshProject(root)

                message = "فایل و اطلاعات آخرین ویرایش ذخیره شدند."
                error = ""

            } catch (e: Exception) {
                error = e.message ?: "ذخیره فایل ناموفق بود."
            } finally {
                loading = false
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

        if (loading) return

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

                saveProjectMetadata(folder)
                refreshProject(folder)

                message = "پروژه با موفقیت ساخته و اطلاعات آن ذخیره شد."

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

        if (!isInsideProject(parent, root) || !parent.isDirectory) {
            error = "مسیر پوشه معتبر نیست."
            return
        }

        if (!validItemName(name)) {
            error = "نام فایل یا پوشه معتبر نیست."
            return
        }

        val target = File(parent, name)

        if (target.exists()) {
            error = "این نام از قبل وجود دارد."
            return
        }

        if (loading) return
        loading = true

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(
                        if (newItemIsFolder) {
                            target.mkdirs()
                        } else {
                            target.createNewFile()
                        }
                    ) {
                        "ساخت فایل یا پوشه ناموفق بود."
                    }
                }

                saveProjectMetadata(root)
                refreshProject(root)

                if (!newItemIsFolder && isEditable(target)) {
                    selectedFile = target
                    editorText = ""
                }

                newItemName = ""
                message = if (newItemIsFolder) {
                    "پوشه جدید ساخته شد."
                } else {
                    "فایل جدید ساخته شد."
                }

                error = ""

            } catch (e: Exception) {
                error = e.message ?: "ساخت فایل یا پوشه ناموفق بود."
            } finally {
                loading = false
            }
        }
    }

    fun renameItem() {
        val root = projectFolder ?: return
        val target = dialogTarget ?: return
        val newName = renameValue.trim()

        if (
            !isInsideProject(target, root) ||
            target.canonicalFile == root.canonicalFile
        ) {
            error = "تغییر نام این مسیر مجاز نیست."
            showRenameDialog = false
            return
        }

        if (!validItemName(newName)) {
            error = "نام جدید معتبر نیست."
            return
        }

        val destination = File(target.parentFile, newName)

        if (destination.exists()) {
            error = "فایل یا پوشه‌ای با این نام وجود دارد."
            return
        }

        if (loading) return
        loading = true

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(isInsideProject(target, root)) {
                        "مسیر موردنظر معتبر نیست."
                    }

                    check(target.renameTo(destination)) {
                        "تغییر نام انجام نشد."
                    }
                }

                // حفظ مسیر فایل یا پوشه انتخاب‌شده پس از تغییر نام
                selectedFile = selectedFile?.let { selected ->
                    if (isSameOrInside(selected, target)) {
                        File(
                            destination,
                            selected.relativeTo(target).path
                        )
                    } else {
                        selected
                    }
                }

                currentDirectory = currentDirectory?.let { current ->
                    if (isSameOrInside(current, target)) {
                        File(
                            destination,
                            current.relativeTo(target).path
                        )
                    } else {
                        current
                    }
                }

                saveProjectMetadata(root)
                refreshProject(root)

                showRenameDialog = false
                dialogTarget = null
                message = "تغییر نام با موفقیت انجام شد."
                error = ""

            } catch (e: Exception) {
                error = e.message ?: "تغییر نام ناموفق بود."
            } finally {
                loading = false
            }
        }
    }

    fun deleteItem() {
        val root = projectFolder ?: return
        val target = dialogTarget ?: return

        if (
            !isInsideProject(target, root) ||
            target.canonicalFile == root.canonicalFile
        ) {
            error = "حذف این مسیر مجاز نیست."
            showDeleteDialog = false
            return
        }

        if (loading) return
        loading = true

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(isInsideProject(target, root)) {
                        "مسیر موردنظر معتبر نیست."
                    }

                    check(target.deleteRecursively()) {
                        "حذف فایل یا پوشه ناموفق بود."
                    }
                }

                if (
                    selectedFile != null &&
                    isSameOrInside(selectedFile!!, target)
                ) {
                    selectedFile = null
                    editorText = ""
                }

                if (
                    currentDirectory != null &&
                    isSameOrInside(currentDirectory!!, target)
                ) {
                    currentDirectory = root
                }

                saveProjectMetadata(root)
                refreshProject(root)

                showDeleteDialog = false
                dialogTarget = null
                message = "حذف با موفقیت انجام شد."
                error = ""

            } catch (e: Exception) {
                error = e.message ?: "حذف انجام نشد."
            } finally {
                loading = false
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
                            isInsideProject(parent, root)
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
                                },
                                enabled = !loading
                            ) {
                                Text("تغییر نام")
                            }

                            TextButton(
                                onClick = {
                                    dialogTarget = file
                                    showDeleteDialog = true
                                    error = ""
                                },
                                enabled = !loading
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
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading
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
                enabled = selectedFile != null && !loading,
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
                if (!loading) {
                    showRenameDialog = false
                    dialogTarget = null
                }
            },
            title = { Text("تغییر نام") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameValue,
                        onValueChange = { renameValue = it },
                        label = { Text("نام جدید") },
                        singleLine = true
                    )

                    if (error.isNotEmpty()) {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { renameItem() },
                    enabled = !loading
                ) {
                    Text("ذخیره")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (!loading) {
                            showRenameDialog = false
                            dialogTarget = null
                        }
                    },
                    enabled = !loading
                ) {
                    Text("انصراف")
                }
            }
        )
    }

    if (showDeleteDialog && dialogTarget != null) {
        AlertDialog(
            onDismissRequest = {
                if (!loading) {
                    showDeleteDialog = false
                    dialogTarget = null
                }
            },
            title = { Text("تأیید حذف") },
            text = {
                Text(
                    "آیا از حذف «${dialogTarget?.name}» مطمئن هستید؟ اگر پوشه باشد، تمام محتویات آن نیز حذف می‌شود."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { deleteItem() },
                    enabled = !loading
                ) {
                    Text(
                        "حذف",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (!loading) {
                            showDeleteDialog = false
                            dialogTarget = null
                        }
                    },
                    enabled = !loading
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
