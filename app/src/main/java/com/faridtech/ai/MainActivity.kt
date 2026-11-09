
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
                    security = security
                )
            }
        }
    }
}

@Composable
fun OwnerAccessScreen(security: OwnerSecurity) {

    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var unlocked by remember { mutableStateOf(false) }
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
            text = "FARID TECH AI",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (unlocked) {

            Text(
                text = "خوش آمدید",
                style = MaterialTheme.typography.headlineSmall
            )

            Text("دسترسی مالک تأیید شد.")

        } else {

            Text(
                text = if (configured)
                    "ورود مالک"
                else
                    "تنظیم رمز مالک",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    error = ""
                },
                label = {
                    Text("رمز شخصی مالک")
                },
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
                    label = {
                        Text("تکرار رمز شخصی")
                    },
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
                            unlocked = true
                            error = ""
                        } else {
                            val remaining = security.remainingLockMillis()

                            error = if (remaining > 0) {
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
                                unlocked = true
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
                Text(
                    if (configured) "ورود امن"
                    else "ثبت رمز مالک"
                )
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
