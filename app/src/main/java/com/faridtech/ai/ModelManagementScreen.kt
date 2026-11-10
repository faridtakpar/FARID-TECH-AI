
package com.faridtech.ai

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagementScreen(
    context: Context,
    onBack: () -> Unit,
    onExternalPickerStateChanged: (Boolean) -> Unit
) {
    val engine = remember {
        LocalAIEngine(context.applicationContext)
    }

    val downloader = remember {
        ModelDownloader(context.applicationContext)
    }

    val scope = rememberCoroutineScope()

    var details by remember {
        mutableStateOf("در حال بررسی مدل...")
    }

    var status by remember {
        mutableStateOf("آماده")
    }

    var busy by remember {
        mutableStateOf(false)
    }

    var progress by remember {
        mutableFloatStateOf(0f)
    }

    var progressText by remember {
        mutableStateOf("")
    }

    var modelAvailable by remember {
        mutableStateOf(false)
    }

    fun refreshModel() {
        scope.launch {
            try {
                val currentDetails = engine.getModelDetails()
                val available = engine.checkModel()

                details = currentDetails
                modelAvailable = available

            } catch (e: Exception) {
                details = "خطا در بررسی مدل: ${e.message ?: "نامشخص"}"
                modelAvailable = false
            }
        }
    }

    fun runOperation(
        operationStatus: String,
        operation: suspend () -> String
    ) {
        scope.launch {
            if (busy) return@launch

            busy = true
            status = operationStatus

            try {
                status = operation()

            } catch (e: Exception) {
                status = "خطا: ${e.message ?: "عملیات ناموفق بود"}"

            } finally {
                busy = false
                refreshModel()
            }
        }
    }

    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->

            onExternalPickerStateChanged(false)

            if (uri != null) {
                runOperation("در حال واردکردن مدل...") {
                    if (engine.importModelFrom(uri)) {
                        "مدل با موفقیت وارد شد."
                    } else {
                        "واردکردن مدل ناموفق بود."
                    }
                }
            }
        }

    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(
                "application/octet-stream"
            )
        ) { uri ->

            onExternalPickerStateChanged(false)

            if (uri != null) {
                runOperation("در حال تهیه نسخه پشتیبان...") {
                    if (engine.exportModelTo(uri)) {
                        "نسخه پشتیبان با موفقیت ذخیره شد."
                    } else {
                        "خروجی‌گرفتن از مدل ناموفق بود."
                    }
                }
            }
        }

    LaunchedEffect(Unit) {
        refreshModel()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("مدیریت مدل هوش مصنوعی")
                },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        enabled = !busy
                    ) {
                        Text("بازگشت")
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor =
                        MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "FARID TECH AI",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Text(
                        "مدیریت هوش مصنوعی آفلاین",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                        "Qwen2.5-Coder-1.5B",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "وضعیت مدل",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        if (modelAvailable) {
                            "مدل روی دستگاه موجود است."
                        } else {
                            "مدل هنوز نصب نشده است."
                        }
                    )

                    HorizontalDivider()

                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedButton(
                        onClick = {
                            refreshModel()
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("بررسی مجدد وضعیت")
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "دانلود و نصب مدل",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        "دانلود مدل از سرور و ذخیره آن برای استفاده آفلاین."
                    )

                    if (busy && progressText.isNotEmpty()) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            progressText,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                if (busy) return@launch

                                busy = true
                                progress = 0f
                                progressText = "شروع دانلود..."
                                status = "در حال دانلود مدل..."

                                try {
                                    downloader.download {
                                        downloaded,
                                        total ->

                                        scope.launch(
                                            Dispatchers.Main.immediate
                                        ) {
                                            progress =
                                                if (total > 0L) {
                                                    downloaded.toFloat() /
                                                        total.toFloat()
                                                } else {
                                                    0f
                                                }

                                            val downloadedMb =
                                                downloaded /
                                                    (1024.0 * 1024.0)

                                            val totalMb =
                                                total /
                                                    (1024.0 * 1024.0)

                                            progressText =
                                                String.format(
                                                    Locale.US,
                                                    "%.1f از %.1f مگابایت",
                                                    downloadedMb,
                                                    totalMb
                                                )
                                        }
                                    }

                                    status =
                                        "مدل با موفقیت دانلود شد."

                                } catch (e: Exception) {
                                    status =
                                        "خطا: ${e.message ?: "دانلود ناموفق بود"}"

                                } finally {
                                    busy = false
                                    refreshModel()
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("دانلود مدل")
                    }

                    OutlinedButton(
                        onClick = {
                            runOperation("در حال بارگذاری مدل...") {
                                engine.loadModel()
                            }
                        },
                        enabled = !busy && modelAvailable,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("بارگذاری مدل در حافظه")
                    }

                    OutlinedButton(
                        onClick = {
                            runOperation("در حال آزادسازی حافظه...") {
                                engine.releaseModel()
                                "درخواست آزادسازی حافظه انجام شد."
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("آزادسازی حافظه مدل")
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "ورود و خروجی مدل",
                        style = MaterialTheme.typography.titleMedium
                    )

                    OutlinedButton(
                        onClick = {
                            try {
                                onExternalPickerStateChanged(true)

                                importLauncher.launch(
                                    arrayOf("*/*")
                                )

                            } catch (e: Exception) {
                                onExternalPickerStateChanged(false)
                                status = "بازکردن انتخاب‌گر فایل ناموفق بود."
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("واردکردن فایل مدل از دستگاه")
                    }

                    OutlinedButton(
                        onClick = {
                            try {
                                onExternalPickerStateChanged(true)

                                exportLauncher.launch(
                                    "Qwen2.5-Coder-1.5B-Q4_K_M.gguf"
                                )

                            } catch (e: Exception) {
                                onExternalPickerStateChanged(false)
                                status = "بازکردن محل ذخیره فایل ناموفق بود."
                            }
                        },
                        enabled = !busy && modelAvailable,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ذخیره نسخه پشتیبان مدل")
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "پیام سیستم",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(status)
                }
            }

            if (busy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()

                    Spacer(Modifier.width(12.dp))

                    Text("لطفاً صبر کنید...")
                }
            }
        }
    }
}
