
package com.faridtech.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProjectBackupHistoryDialog(
    history: List<ProjectBackupEntry>,
    loading: Boolean,
    error: String? = null,
    restoring: Boolean = false,
    onRestore: (ProjectBackupEntry) -> Unit,
    onDismiss: () -> Unit
) {

    var selectedEntry by remember {
        mutableStateOf<ProjectBackupEntry?>(null)
    }

    val dateFormatter = DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        Locale("fa", "IR")
    )

    AlertDialog(
        onDismissRequest = {
            if (!loading && !restoring) {
                onDismiss()
            }
        },
        shape = RoundedCornerShape(24.dp),
        title = {
            Column {
                Text(
                    text = "تاریخچه نسخه‌های پشتیبان",
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "نسخه‌های ذخیره‌شده پروژه شما",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {

            Column(
                modifier = Modifier.fillMaxWidth()
            ) {

                if (loading) {

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text("در حال دریافت تاریخچه...")
                    }

                } else if (restoring) {

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {

                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text("در حال بازگردانی نسخه انتخاب‌شده...")

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "لطفاً تا پایان عملیات صبر کنید.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                } else if (error != null) {

                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )

                } else if (history.isEmpty()) {

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {

                        Text(
                            text = "📁",
                            style = MaterialTheme.typography.headlineLarge
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "هنوز نسخه پشتیبانی ثبت نشده است.",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "پس از اعمال اولین اصلاح، نسخه پشتیبان اینجا نمایش داده می‌شود.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                } else {

                    Text(
                        text = "${history.size} نسخه پشتیبان",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(350.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {

                        items(
                            items = history,
                            key = { it.backupPath }
                        ) { entry ->

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor =
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {

                                Column(
                                    modifier = Modifier.padding(14.dp)
                                ) {

                                    Text(
                                        text = File(entry.filePath).name,
                                        style = MaterialTheme.typography.titleSmall
                                    )

                                    Spacer(
                                        modifier = Modifier.height(8.dp)
                                    )

                                    Text(
                                        text = "زمان ایجاد: ${
                                            dateFormatter.format(
                                                Date(entry.createdAt)
                                            )
                                        }",
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    Spacer(
                                        modifier = Modifier.height(5.dp)
                                    )

                                    Text(
                                        text = "حجم نسخه: ${formatBackupSize(entry.backupSize)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    Spacer(
                                        modifier = Modifier.height(10.dp)
                                    )

                                    Divider(
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )

                                    Spacer(
                                        modifier = Modifier.height(8.dp)
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {

                                        Text(
                                            text = "وضعیت نسخه",
                                            style = MaterialTheme.typography.bodySmall
                                        )

                                        Text(
                                            text = if (entry.isValid) {
                                                "سالم و قابل بازگردانی"
                                            } else {
                                                "نامعتبر یا آسیب‌دیده"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (entry.isValid) {
                                                Color(0xFF168653)
                                            } else {
                                                MaterialTheme.colorScheme.error
                                            }
                                        )
                                    }

                                    if (entry.isValid) {

                                        Spacer(
                                            modifier = Modifier.height(8.dp)
                                        )

                                        TextButton(
                                            onClick = {
                                                selectedEntry = entry
                                            },
                                            enabled = !restoring,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("انتخاب و بازگردانی این نسخه")
                                        }

                                    } else {

                                        Spacer(
                                            modifier = Modifier.height(8.dp)
                                        )

                                        Text(
                                            text = "این نسخه به دلیل مشکل در بررسی سلامت قابل بازگردانی نیست.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !loading && !restoring
            ) {
                Text("بستن")
            }
        }
    )

    // -------------------------------------------------
    // تأیید نهایی بازگردانی
    // -------------------------------------------------

    selectedEntry?.let { entry ->

        AlertDialog(
            onDismissRequest = {
                if (!restoring) {
                    selectedEntry = null
                }
            },
            shape = RoundedCornerShape(24.dp),
            title = {
                Text("تأیید بازگردانی نسخه")
            },
            text = {
                Column {

                    Text(
                        text = "آیا مطمئن هستید که می‌خواهید نسخه زیر را بازگردانی کنید؟",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "نام فایل: ${File(entry.filePath).name}",
                        style = MaterialTheme.typography.titleSmall
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "تاریخ نسخه: ${
                            dateFormatter.format(
                                Date(entry.createdAt)
                            )
                        }",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "نکته امنیتی: قبل از بازگردانی، از وضعیت فعلی فایل نیز نسخه پشتیبان تهیه می‌شود تا امکان بازگشت به وضعیت فعلی وجود داشته باشد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "این عملیات محتوای فعلی فایل را با نسخه انتخاب‌شده جایگزین می‌کند.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRestore(entry)
                        selectedEntry = null
                    },
                    enabled = !restoring
                ) {
                    Text("تأیید و بازگردانی")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        selectedEntry = null
                    },
                    enabled = !restoring
                ) {
                    Text("انصراف")
                }
            }
        )
    }
}

private fun formatBackupSize(size: Long): String {
    return when {
        size <= 0L -> "نامشخص"

        size < 1024L -> "$size بایت"

        size < 1024L * 1024L ->
            String.format(
                Locale("fa", "IR"),
                "%.1f کیلوبایت",
                size / 1024.0
            )

        else ->
            String.format(
                Locale("fa", "IR"),
                "%.2f مگابایت",
                size / (1024.0 * 1024.0)
            )
    }
}
