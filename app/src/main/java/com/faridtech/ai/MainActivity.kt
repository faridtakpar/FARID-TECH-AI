
package com.faridtech.ai

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
                    context = this,
                    unlocked = ownerUnlocked,
                    onUnlock = {
                        ownerUnlocked = true
                    },
                    onLock = {
                        ownerUnlocked = false
                    }
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
    context: android.content.Context,
    unlocked: Boolean,
    onUnlock: () -> Unit,
    onLock: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var configured by remember {
        mutableStateOf(security.isConfigured())
    }
    var selectedSection by remember {
        mutableStateOf<String?>(null)
    }

    LaunchedEffect(unlocked) {
        if (!unlocked) {
            selectedSection = null
        }
    }

    if (unlocked) {

        when (selectedSection) {

            "هوش مصنوعی محلی",
            "هوش مصنوعی آنلاین" -> {
                ChatScreen(
                    context = context,
                    onBack = {
                        selectedSection = null
                    }
                )
            }

            null -> {
                OwnerDashboard(
                    onSelect = {
                        selectedSection = it
                    },
                    onLock = onLock
                )
            }

            else -> {
                SectionScreen(
                    title = selectedSection ?: "",
                    onBack = {
                        selectedSection = null
                    }
                )
            }
        }

    } else {

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

            Text(
                if (configured) "ورود مالک"
                else "تنظیم رمز مالک",
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
                            onUnlock()
                            error = ""
                        } else {
                            error = if (
                                security.remainingLockMillis() > 0
                            ) {
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
                                error = "تکرار رمز مطابقت ندارد."

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

@Composable
fun OwnerDashboard(
    onSelect: (String) -> Unit,
    onLock: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            "FARID TECH AI",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            "کنسول اختصاصی مالک",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        DashboardCard(
            "🔐 امنیت و مدیریت مالک",
            "مدیریت دسترسی و وضعیت امنیتی",
            onClick = {
                onSelect("امنیت و مدیریت مالک")
            }
        )

        DashboardCard(
            "🧠 هوش مصنوعی محلی",
            "ورود به محیط گفت‌وگو و آماده‌سازی مدل آفلاین",
            onClick = {
                onSelect("هوش مصنوعی محلی")
            }
        )

        DashboardCard(
            "🌐 هوش مصنوعی آنلاین",
            "ورود به محیط گفت‌وگو و آماده‌سازی اتصال آنلاین",
            onClick = {
                onSelect("هوش مصنوعی آنلاین")
            }
        )

        DashboardCard(
            "💻 برنامه‌نویسی و توسعه",
            "ابزارهای ساخت اپلیکیشن و وب‌سایت",
            onClick = {
                onSelect("برنامه‌نویسی و توسعه")
            }
        )

        DashboardCard(
            "🛡 ابزارهای امنیتی مجاز",
            "بررسی امنیت سامانه‌های مجاز",
            onClick = {
                onSelect("ابزارهای امنیتی مجاز")
            }
        )

        DashboardCard(
            "⚙ تنظیمات و حافظه هوشمند",
            "تنظیمات برنامه و مدیریت حافظه",
            onClick = {
                onSelect("تنظیمات و حافظه هوشمند")
            }
        )

        Spacer(modifier = Modifier.height(20.dp))

        OutlinedButton(onClick = onLock) {
            Text("قفل برنامه")
        }
    }
}

@Composable
fun DashboardCard(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                description,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun SectionScreen(
    title: String,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            title,
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "این بخش در مراحل بعدی توسعه فعال خواهد شد."
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onBack) {
            Text("بازگشت به داشبورد")
        }
    }
}
