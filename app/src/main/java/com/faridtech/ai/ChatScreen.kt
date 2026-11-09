
package com.faridtech.ai

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class ChatMessage(
    val text: String,
    val isUser: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    context: Context,
    onBack: () -> Unit
) {
    val prefs = remember {
        context.getSharedPreferences("farid_chat", Context.MODE_PRIVATE)
    }

    val downloader = remember {
        ModelDownloader(context)
    }

    val engine = remember {
        LocalAIEngine(context)
    }

    val scope = rememberCoroutineScope()

    val messages = remember {
        mutableStateListOf<ChatMessage>().apply {
            val saved = prefs.getString("history", "") ?: ""

            saved.lines()
                .filter { it.isNotBlank() }
                .forEach { line ->
                    try {
                        val parts = line.split(":", limit = 2)

                        if (parts.size == 2) {
                            val decoded = String(
                                Base64.decode(
                                    parts[1],
                                    Base64.NO_WRAP
                                ),
                                Charsets.UTF_8
                            )

                            add(ChatMessage(decoded, parts[0] == "U"))
                        }
                    } catch (_: Exception) {
                    }
                }
        }
    }

    var input by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadStatus by remember {
        mutableStateOf("در حال بررسی مدل...")
    }

    val listState = rememberLazyListState()

    fun saveHistory() {
        val data = messages.joinToString("\n") { message ->
            val encoded = Base64.encodeToString(
                message.text.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )

            (if (message.isUser) "U:" else "A:") + encoded
        }

        prefs.edit().putString("history", data).apply()
    }

    LaunchedEffect(Unit) {
        modelReady = engine.checkModel()
        downloadStatus = if (modelReady) {
            "مدل روی گوشی نصب است."
        } else {
            "برای استفاده آفلاین، ابتدا مدل را دانلود کنید."
        }
    }

    fun startDownload() {
        if (downloading || modelReady) return

        scope.launch {
            downloading = true
            downloadStatus = "در حال دانلود مدل..."
            downloadProgress = 0

            try {
                val file = downloader.download { downloaded, total ->
                    if (total > 0) {
                        downloadProgress =
                            ((downloaded * 100) / total)
                                .toInt()
                                .coerceIn(0, 100)
                    }
                }

                modelReady = engine.checkModel()
                downloadProgress = 100
                downloadStatus = if (modelReady) {
                    "مدل با موفقیت دانلود شد."
                } else {
                    "فایل دریافت شد اما معتبر نیست."
                }

            } catch (e: Exception) {
                downloadStatus =
                    "دانلود ناموفق بود: ${e.message ?: "خطای نامشخص"}"
            } finally {
                downloading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FARID TECH AI") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("بازگشت")
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text("پیام خود را بنویسید...")
                    }
                )

                Button(
                    onClick = {
                        if (input.isNotBlank()) {
                            messages.add(
                                ChatMessage(input.trim(), true)
                            )
                            input = ""
                            saveHistory()
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) {
                    Text("ارسال")
                }
            }
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {

            Text(
                "دستیار هوشمند شخصی",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {

                    Text(
                        "موتور هوش مصنوعی آفلاین",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(downloadStatus)

                    if (downloading) {
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("$downloadProgress٪")
                    }

                    Button(
                        onClick = { startDownload() },
                        enabled = !downloading && !modelReady,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (modelReady) {
                                "مدل آماده است"
                            } else if (downloading) {
                                "در حال دانلود..."
                            } else {
                                "دانلود مدل آفلاین"
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (message.isUser) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Text(
                            text = message.text,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
}
