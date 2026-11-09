
package com.faridtech.ai

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.faridtech.ai.security.OwnerSecurity

class MainActivity : ComponentActivity() {

    private var ownerUnlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val security = OwnerSecurity(this)

        setContent {
            MaterialTheme {
                OwnerAccessScreen(
                    security = security,
                    unlocked = ownerUnlocked,
                    onUnlock = { ownerUnlocked = true },
                    onLock = { ownerUnlocked = false }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        ownerUnlocked = false
    }
}

@Composable
fun OwnerAccessScreen(
    security: OwnerSecurity,
    unlocked: Boolean,
    onUnlock: () -> Unit,
    onLock: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(security.isConfigured()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "FARID TECH AI",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (unlocked) {
            Text(
                "کنسول مالک",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text("دسترسی مالک تأیید شده است.")

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onLock) {
                Text("قفل برنامه")
            }
        } else {
            Text(
                if (configured) "ورود مالک" else "تنظیم رمز مالک",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    error = ""
                },
                label = { Text("رمز شخصی مالک") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password
                ),
                singleLine = true
            )

            if (!configured) {
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        error = ""
                    },
                    label = { Text("تکرار رمز شخصی") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password
                    ),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (configured) {
                        if (security.verifyPassword(password)) {
                            onUnlock()
                            error = ""
                        } else {
                            error = if (security.remainingLockMillis() > 0) {
                                "ورود موقتاً قفل شده است."
                            } else {
                                "رمز واردشده صحیح نیست."
                            }
                        }
                    } else {
                        when {
                            password.length < 12 ->
                                error = "رمز باید حداقل ۱۲ کاراکتر باشد."

                            password != confirmPassword ->
                                error = "تکرار رمز با رمز اصلی مطابقت ندارد."

                            security.setupPassword(password) -> {
                                configured = true
                                onUnlock()
                                error = ""
                            }

                            else ->
                                error = "تنظیم رمز انجام نشد."
                        }
                    }

                    password = ""
                    confirmPassword = ""
                }
            ) {
                Text(if (configured) "ورود امن" else "ثبت رمز مالک")
            }

            if (error.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
