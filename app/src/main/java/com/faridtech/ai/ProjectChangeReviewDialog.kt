
package com.faridtech.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ProjectChangeReviewDialog(
    fileName: String,
    originalContent: String,
    proposedContent: String,
    applying: Boolean = false,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!applying) onCancel()
        },
        shape = RoundedCornerShape(22.dp),
        title = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "بازبینی اصلاح فایل",
                    fontWeight = FontWeight.Bold
                )

                Text(
                    fileName,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "نسخه اصلی",
                    fontWeight = FontWeight.Bold
                )

                CodePreviewPanel(
                    content = originalContent,
                    backgroundColor = Color(0xFF202833)
                )

                Text(
                    "نسخه پیشنهادی هوش مصنوعی",
                    fontWeight = FontWeight.Bold
                )

                CodePreviewPanel(
                    content = proposedContent,
                    backgroundColor = Color(0xFF173D36)
                )

                Text(
                    "توجه: با تأیید شما، نسخه اصلی ابتدا پشتیبان‌گیری می‌شود و سپس نسخه پیشنهادی جایگزین آن خواهد شد.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !applying,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF19C37D),
                    contentColor = Color(0xFF062A1C)
                )
            ) {
                if (applying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("در حال اعمال...")
                } else {
                    Text("تأیید مالک و اعمال اصلاح")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !applying
            ) {
                Text("رد اصلاح")
            }
        }
    )
}

@Composable
private fun CodePreviewPanel(
    content: String,
    backgroundColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor
    ) {
        Text(
            text = content.ifBlank { "محتوایی وجود ندارد." },
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            color = Color(0xFFE5EAF0),
            fontSize = 11.sp,
            lineHeight = 17.sp
        )
    }
}
