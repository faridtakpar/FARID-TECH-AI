
package com.faridtech.ai

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    onBack: () -> Unit,
    onSubmit: (
        prompt: String,
        code: String,
        language: String,
        task: String
    ) -> Unit
) {
    var selectedLanguage by remember { mutableStateOf("Kotlin") }
    var selectedTask by remember { mutableStateOf("تولید کد") }

    var prompt by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    var languageMenuExpanded by remember {
        mutableStateOf(false)
    }

    var errorMessage by remember {
        mutableStateOf("")
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
                    fontSize = 27.sp,
                    color = MaterialTheme.colorScheme.onSurface
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
                    Text(
                        "👨‍💻",
                        fontSize = 32.sp
                    )

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

            // Language selector

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
                                text = {
                                    Text(language)
                                },
                                onClick = {
                                    selectedLanguage = language
                                    languageMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Task selection

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "نوع درخواست",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        programmingTasks.take(2).forEach { task ->
                            FilterChip(
                                selected = selectedTask == task,
                                onClick = {
                                    selectedTask = task
                                },
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
                                onClick = {
                                    selectedTask = task
                                },
                                label = { Text(task) },
                                modifier = Modifier.weight(1f)
                            )
                        }
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
                            "مثلاً یک صفحه ورود امن با Kotlin برای من بساز..."
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
                            Text(
                                "●",
                                color = Color(0xFFFF6B6B)
                            )

                            Spacer(Modifier.width(6.dp))

                            Text(
                                "●",
                                color = Color(0xFFFFC857)
                            )

                            Spacer(Modifier.width(6.dp))

                            Text(
                                "●",
                                color = ProgrammingGreen
                            )

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
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                ProgrammingGreen
                            ),
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

            // Error message

            if (errorMessage.isNotEmpty()) {
                Text(
                    errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Submit

            Button(
                onClick = {
                    if (
                        prompt.isBlank() &&
                        code.isBlank()
                    ) {
                        errorMessage =
                            "لطفاً توضیح درخواست یا کد خود را وارد کنید."
                    } else {
                        errorMessage = ""

                        onSubmit(
                            prompt.trim(),
                            code,
                            selectedLanguage,
                            selectedTask
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ProgrammingGreen,
                    contentColor = Color(0xFF062A1C)
                )
            ) {
                Text(
                    "ارسال به هوش مصنوعی  ➜",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Text(
                "اتصال به موتور هوش مصنوعی در مرحله بعد فعال می‌شود.",
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )

            Spacer(Modifier.height(12.dp))
        }
    }
}
