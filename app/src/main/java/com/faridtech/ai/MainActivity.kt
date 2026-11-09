package com.faridtech.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                FaridTechAIApp()
            }
        }
    }
}

@Composable
fun FaridTechAIApp() {
    var message by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf("سلام! من FARID TECH AI هستم.")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FARID TECH AI") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = "دستیار هوشمند شخصی",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = item,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("پیام خود را بنویسید...") }
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (message.isNotBlank()) {
                            messages = messages + message.trim()
                            message = ""
                        }
                    }
                ) {
                    Text("ارسال")
                }
            }
        }
    }
}
