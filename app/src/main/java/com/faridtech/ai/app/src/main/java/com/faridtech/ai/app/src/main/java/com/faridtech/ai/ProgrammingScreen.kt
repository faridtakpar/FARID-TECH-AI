
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val ProgrammingGreen = Color(0xFF19C37D)

private val programmingLanguages = listOf(
    "Kotlin",
    "Java",
    "Python",
    "C++",
    "C",
    "HTML",
    "CSS",
    "JavaScript",
    "PHP",
    "SQL",
    "Dart",
    "Swift"
)

private val programmingTasks = listOf(
    "تولید کد",
    "اصلاح کد",
    "رفع خطا",
    "توضیح کد"
)

@Composable
fun ProgrammingScreen(
    context: Context,
    onBack: () -> Unit
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

    LaunchedEffect(engine) {
        checkingModel = true

        try {
            modelAvailable = engine.checkModel()
        } catch (e: Exception) {
            modelAvailable = false
        }

        checkingModel = false
    }

    fun sendRequest() {
        if (generating) return

        if (prompt.isBlank() && code.isBlank()) {
            errorMessage = "لطفاً توضیح درخواست یا کد خود را وارد کنید."
            return
        }

        if (!modelAvailable) {
            errorMessage = "مدل هوش مصنوعی نصب نشده است. ابتدا از بخش مدیریت مدل آن را دریافت کنید."
            return
        }

        val formattedPrompt = buildString {
            appendLine("You are a professional programming assistant.")
            appendLine("Answer in Persian unless code requires otherwise.")
            appendLine("Programming language: $selectedLanguage")
            appendLine("Task: $selectedTask")
            appendLine()
            appendLine("User request:")
            appendLine(prompt.ifBlank { "Please analyze the provided code." })

            if (code.isNotBlank()) {
                appendLine()
                appendLine("User code:")
                appendLine("```$selectedLanguage")
                appendLine(code)
                appendLine("```")
            }

            appendLine()
            appendLine("Provide a clear, technically accurate answer.")
            appendLine("When generating or fixing code, provide complete code where practical.")
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
                errorMessage = "خطا در اجرای هوش مصنوعی: ${
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
                Text(
                    "←",
                    fontSize = 27.sp
                )
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
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Text(
                            when {
                                checkingModel -> "در حال بررسی فایل مدل..."
                                modelAvailable -> "مدل محلی شناسایی شد"
                                else -> "مدل نصب نشده است"
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
                        Text("مثلاً یک صفحه ورود امن با Kotlin برای من بساز...")
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

            // Error

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
                enabled = !generating && !checkingModel,
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
}
