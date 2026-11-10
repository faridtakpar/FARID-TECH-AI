
package com.faridtech.ai

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.faridtech.ai.security.OwnerSecurity

private val BrandGreen = Color(0xFF19C37D)

private val DarkColors = darkColorScheme(
    primary = BrandGreen,
    onPrimary = Color(0xFF062A1C),
    secondary = Color(0xFF9ADFC1),
    background = Color(0xFF111318),
    surface = Color(0xFF1B1E25),
    surfaceVariant = Color(0xFF292E37),
    onBackground = Color(0xFFF2F3F5),
    onSurface = Color(0xFFF2F3F5),
    onSurfaceVariant = Color(0xFFB5BAC4)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF087D53),
    onPrimary = Color.White,
    secondary = Color(0xFF287D60),
    background = Color(0xFFF5F7F8),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9EEF0),
    onBackground = Color(0xFF171B20),
    onSurface = Color(0xFF171B20),
    onSurfaceVariant = Color(0xFF5F6872)
)

@Composable
private fun FaridTechTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}

class MainActivity : ComponentActivity() {

    private var ownerUnlocked by mutableStateOf(false)
    private var darkTheme by mutableStateOf(true)

    internal var externalPickerOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val preferences = getSharedPreferences(
            "farid_tech_settings",
            MODE_PRIVATE
        )

        darkTheme = preferences.getBoolean("dark_theme", true)

        val security = OwnerSecurity(this)

        setContent {
            FaridTechTheme(darkTheme = darkTheme) {
                OwnerAccessScreen(
                    security = security,
                    context = this,
                    unlocked = ownerUnlocked,
                    darkTheme = darkTheme,
                    onToggleTheme = {
                        darkTheme = !darkTheme
                        preferences.edit()
                            .putBoolean("dark_theme", darkTheme)
                            .apply()
                    },
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

        if (!externalPickerOpen) {
            ownerUnlocked = false
        }
    }
}

@Composable
fun OwnerAccessScreen(
    security: OwnerSecurity,
    context: android.content.Context,
    unlocked: Boolean,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onUnlock: () -> Unit,
    onLock: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(security.isConfigured()) }
    var selectedSection by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(unlocked) {
        if (!unlocked) {
            selectedSection = null
            password = ""
            confirmPassword = ""
            error = ""
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (unlocked) {
            when (selectedSection) {
                "هوش مصنوعی محلی" -> {
                    ChatScreen(
                        context = context,
                        onBack = { selectedSection = null },
                        onExternalPickerStateChanged = { isOpen ->
                            (context as? MainActivity)?.externalPickerOpen = isOpen
                        }
                    )
                }

                null -> {
                    OwnerDashboard(
                        darkTheme = darkTheme,
                        onToggleTheme = onToggleTheme,
                        onSelect = { selectedSection = it },
                        onLock = onLock
                    )
                }

                else -> {
                    SectionScreen(
                        title = selectedSection ?: "",
                        onBack = { selectedSection = null }
                    )
                }
            }
        } else {
            OwnerLoginScreen(
                password = password,
                onPasswordChange = {
                    password = it
                    error = ""
                },
                confirmPassword = confirmPassword,
                onConfirmPasswordChange = {
                    confirmPassword = it
                    error = ""
                },
                configured = configured,
                error = error,
                onSubmit = {
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
            )
        }
    }
}

@Composable
private fun OwnerLoginScreen(
    password: String,
    onPasswordChange: (String) -> Unit,
    confirmPassword: String,
    onConfirmPasswordChange: (String) -> Unit,
    configured: Boolean,
    error: String,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(BrandGreen),
            contentAlignment = Alignment.Center
        ) {
            Text("FT", color = Color.White, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineMedium)
        }

        Spacer(Modifier.height(20.dp))

        Text(
            "FARID TECH AI",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            if (configured) "ورود امن مالک" else "تنظیم رمز مالک",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(28.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("رمز شخصی مالک") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password
                    ),
                    singleLine = true
                )

                if (!configured) {
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = onConfirmPasswordChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("تکرار رمز شخصی") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password
                        ),
                        singleLine = true
                    )
                }

                if (error.isNotEmpty()) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Button(
                    onClick = onSubmit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        if (configured) "ورود امن" else "ثبت رمز مالک"
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            "دسترسی اختصاصی و محافظت‌شده",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun OwnerDashboard(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onSelect: (String) -> Unit,
    onLock: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(BrandGreen),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "FT",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "FARID TECH AI",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "دستیار هوشمند شخصی",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            TextButton(onClick = onToggleTheme) {
                Text(if (darkTheme) "☀️" else "🌙")
            }

            TextButton(onClick = onLock) {
                Text("🔒")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF126B4C),
                            Color(0xFF173D36),
                            Color(0xFF202A35)
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "سلام، خوش آمدید",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "چه کاری می‌خواهید انجام دهید؟",
                    color = Color(0xFFD9EEE5),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.height(4.dp))

                Surface(
                    color = Color.White.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(50.dp)
                ) {
                    Text(
                        "●  کنسول اختصاصی مالک",
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 7.dp
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "ابزارهای هوشمند",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "امکانات اختصاصی خود را انتخاب کنید",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DashboardCard(
                emoji = "🧠",
                title = "هوش مصنوعی محلی",
                description = "گفت‌وگو با مدل آفلاین",
                modifier = Modifier.weight(1f),
                onClick = { onSelect("هوش مصنوعی محلی") }
            )
            DashboardCard(
                emoji = "🌐",
                title = "هوش مصنوعی آنلاین",
                description = "اتصال به سرویس‌های آنلاین",
                modifier = Modifier.weight(1f),
                onClick = { onSelect("هوش مصنوعی آنلاین") }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DashboardCard(
                emoji = "💻",
                title = "برنامه‌نویسی",
                description = "ساخت و اصلاح کد",
                modifier = Modifier.weight(1f),
                onClick = { onSelect("برنامه‌نویسی و توسعه") }
            )
            DashboardCard(
                emoji = "📱",
                title = "ساخت اپلیکیشن",
                description = "ابزارهای توسعه اندروید",
                modifier = Modifier.weight(1f),
                onClick = { onSelect("ساخت اپلیکیشن") }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DashboardCard(
                emoji = "🌍",
                title = "ساخت وب‌سایت",
                description = "طراحی و توسعه وب",
                modifier = Modifier.weight(1f),
                onClick = { onSelect("ساخت وب‌سایت") }
            )
            DashboardCard(
                emoji = "🛡️",
                title = "ابزارهای امنیتی",
                description = "بررسی سامانه‌های مجاز",
                modifier = Modifier.weight(1f),
                onClick = { onSelect("ابزارهای امنیتی مجاز") }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DashboardCard(
                emoji = "🗂️",
                title = "مدیریت مدل",
                description = "مدل‌ها و فایل‌های هوش مصنوعی",
                modifier = Modifier.weight(1f),
                onClick = { onSelect("مدیریت مدل") }
            )
            DashboardCard(
                emoji = "⚙️",
                title = "تنظیمات و حافظه",
                description = "تنظیمات و مدیریت اطلاعات",
                modifier = Modifier.weight(1f),
                onClick = { onSelect("تنظیمات و حافظه هوشمند") }
            )
        }

        DashboardCard(
            emoji = "🔐",
            title = "امنیت و مدیریت مالک",
            description = "مدیریت دسترسی و امنیت برنامه",
            modifier = Modifier.fillMaxWidth(),
            onClick = { onSelect("امنیت و مدیریت مالک") }
        )

        OutlinedButton(
            onClick = onLock,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("🔒  قفل برنامه")
        }

        Text(
            "FARID TECH AI  •  نسخه 1.0",
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun DashboardCard(
    emoji: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 132.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                emoji,
                style = MaterialTheme.typography.headlineSmall
            )

            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall
            )

            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun SectionScreen(
    title: String,
    onBack: () -> Unit
) {
    val description = when (title) {
        "هوش مصنوعی آنلاین" ->
            "اتصال به سرویس‌های هوش مصنوعی آنلاین در مرحله توسعه این بخش فعال خواهد شد."

        "برنامه‌نویسی و توسعه" ->
            "ابزارهای برنامه‌نویسی و کمک به توسعه پروژه‌ها در این بخش قرار می‌گیرند."

        "ساخت اپلیکیشن" ->
            "ابزارهای طراحی و توسعه اپلیکیشن اندروید در این بخش قرار خواهند گرفت."

        "ساخت وب‌سایت" ->
            "ابزارهای طراحی، ساخت و مدیریت وب‌سایت در این بخش قرار خواهند گرفت."

        "مدیریت مدل" ->
            "مدیریت مدل‌های محلی، وضعیت فایل‌ها و دریافت مدل‌ها در این بخش قرار می‌گیرد."

        "ابزارهای امنیتی مجاز" ->
            "ابزارهای بررسی امنیت سامانه‌هایی که مجوز بررسی آن‌ها را دارید در این بخش قرار می‌گیرند."

        "امنیت و مدیریت مالک" ->
            "تنظیمات امنیت و مدیریت دسترسی مالک در این بخش قرار خواهد گرفت."

        else ->
            "تنظیمات برنامه و مدیریت حافظه هوشمند در این بخش قرار خواهند گرفت."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        TextButton(onClick = onBack) {
            Text("← بازگشت به داشبورد")
        }

        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Text(
                description,
                modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
