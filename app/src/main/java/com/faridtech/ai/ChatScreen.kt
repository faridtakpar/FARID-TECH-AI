
package com.faridtech.ai

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class ChatMessage(
    val text: String,
    val isUser: Boolean
)

@Composable
fun ChatScreen(
    context: Context,
    onBack: () -> Unit
) {
    val prefs = remember {
        context.getSharedPreferences("farid_chat", Context.MODE_PRIVATE)
    }

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
                                Base64.decode(parts[1], Base64.NO_WRAP),
                                Charsets.UTF_8
                            )
                            add(
                                ChatMessage(
                                    decoded,
                                    parts[0] == "U"
                                )
                            )
                        }
                    } catch (_: Exception) {
                    }
                }
        }
    }

    var input by remember { mutableStateOf("") }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("FARID TECH AI")
                },
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
                    modifier = Modifier.align(
                        androidx.compose.ui.Alignment.CenterVertically
                    )
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

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                "زیرساخت گفت‌وگو آماده است؛ موتور هوش مصنوعی هنوز متصل نشده است.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )

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
                            containerColor = if (message.isUser)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
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
