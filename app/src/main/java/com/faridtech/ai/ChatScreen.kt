
package com.faridtech.ai

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    onBack: () -> Unit,
    onExternalPickerStateChanged: (Boolean) -> Unit
) {

    val prefs = remember {
        context.getSharedPreferences(
            "farid_chat",
            Context.MODE_PRIVATE
        )
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

            val saved =
                prefs.getString("history", "") ?: ""

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

    var input by remember {
        mutableStateOf("")
    }

    var downloading by remember {
        mutableStateOf(false)
    }

    var transferring by remember {
        mutableStateOf(false)
    }

    var generating by remember {
        mutableStateOf(false)
    }

    var modelReady by remember {
        mutableStateOf(false)
    }

    var downloadProgress by remember {
        mutableStateOf(0)
    }

    var downloadStatus by remember {
        mutableStateOf("در حال بررسی مدل...")
    }

    var nativeStatus by remember {
        mutableStateOf("در حال بررسی موتور بومی...")
    }

    val listState = rememberLazyListState()

    // Save chat history.
    fun saveHistory() {

        val data = messages.joinToString("\n") { message ->

            val encoded = Base64.encodeToString(
                message.text.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )

            (if (message.isUser) "U:" else "A:") + encoded
        }

        prefs.edit()
            .putString("history", data)
            .apply()
    }

    // Build a recent conversation prompt.
    // Keep the prompt conservative because the model
    // has a context size of 2048 tokens.

    fun buildConversationPrompt(): String {

        val systemMessage =
            "<|im_start|>system\n" +
            "تو دستیار هوشمند FARID TECH AI هستی. " +
            "به زبان فارسی، دقیق، مفید و روشن پاسخ بده. " +
            "در صورت نیاز از اطلاعات پیام‌های قبلی استفاده کن.\n" +
            "<|im_end|>\n"

        val selectedMessages = mutableListOf<String>()

        var usedCharacters = systemMessage.length

        val maxCharacters = 2800

        for (message in messages.asReversed()) {

            val role = if (message.isUser) {
                "user"
            } else {
                "assistant"
            }

            val block =
                "<|im_start|>$role\n" +
                message.text +
                "\n<|im_end|>\n"

            if (
                usedCharacters + block.length > maxCharacters
            ) {
                break
            }

            selectedMessages.add(0, block)

            usedCharacters += block.length
        }

        return buildString {

            append(systemMessage)

            selectedMessages.forEach {
                append(it)
            }

            append("<|im_start|>assistant\n")
        }
    }

    // Recognize known engine errors instead of
    // reporting them as successful AI responses.

    fun isEngineError(response: String): Boolean {

        val errorPrefixes = listOf(
            "فایل مدل معتبر نیست",
            "فایل مدل معتبر نیست یا نصب نشده است",
            "ابتدا باید مدل را بارگذاری کنی",
            "بارگذاری مدل ناموفق",
            "ساخت محیط اجرای مدل ناموفق",
            "خطای غیرمنتظره هنگام بارگذاری مدل",
            "طول پیام برای تبدیل",
            "پیام برای ظرفیت فعلی مدل",
            "تبدیل پیام به توکن ناموفق",
            "پردازش پیام توسط موتور",
            "ظرفیت پردازش دسته‌ای",
            "ساخت نمونه‌گیر ناموفق",
            "ساخت فیلتر Top-K ناموفق",
            "ساخت فیلتر Top-P ناموفق",
            "تنظیم دمای تولید ناموفق",
            "ساخت نمونه‌گیر تصادفی ناموفق",
            "پردازش توکن خروجی ناموفق",
            "تبدیل توکن خروجی به متن ناموفق",
            "مدل پاسخی تولید نکرد",
            "خطای غیرمنتظره هنگام تولید پاسخ",
            "خطا در اجرای هوش مصنوعی"
        )

        return errorPrefixes.any {
            response.startsWith(it)
        }
    }

    val backupLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(
                "application/octet-stream"
            )
        ) { uri: Uri? ->

            onExternalPickerStateChanged(false)

            if (uri != null) {

                scope.launch {

                    transferring = true
                    downloadStatus =
                        "در حال ساخت نسخه پشتیبان..."

                    val success = try {
                        engine.exportModelTo(uri)
                    } catch (_: Exception) {
                        false
                    }

                    downloadStatus = if (success) {
                        "نسخه پشتیبان مدل ذخیره شد."
                    } else {
                        "ذخیره نسخه پشتیبان ناموفق بود."
                    }

                    transferring = false
                }
            }
        }

    val restoreLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->

            onExternalPickerStateChanged(false)

            if (uri != null) {

                scope.launch {

                    transferring = true
                    downloadStatus =
                        "در حال بازیابی مدل..."

                    val success = try {
                        engine.importModelFrom(uri)
                    } catch (_: Exception) {
                        false
                    }

                    modelReady = engine.checkModel()

                    downloadStatus = if (
                        success && modelReady
                    ) {
                        "مدل با موفقیت بازیابی شد."
                    } else {
                        "بازیابی ناموفق بود؛ فایل را بررسی کنید."
                    }

                    transferring = false
                }
            }
        }

    LaunchedEffect(Unit) {

        modelReady = engine.checkModel()

        downloadStatus = if (modelReady) {
            "فایل مدل روی گوشی موجود است."
        } else {
            "برای استفاده آفلاین، ابتدا مدل را دانلود کنید."
        }

        nativeStatus = try {

            val result =
                NativeLlama().nativeBridgeCheck()

            if (
                result ==
                "FARID TECH AI Native Bridge is ready"
            ) {
                "ارتباط Kotlin و C++ برقرار است."
            } else {
                "پاسخ غیرمنتظره از موتور بومی: $result"
            }

        } catch (e: Throwable) {

            "خطا در اتصال موتور بومی: ${
                e.message ?: e.javaClass.simpleName
            }"
        }
    }

    fun startDownload() {

        if (
            downloading ||
            modelReady ||
            transferring ||
            generating
        ) {
            return
        }

        scope.launch {

            downloading = true
            downloadStatus = "در حال دانلود مدل..."
            downloadProgress = 0

            try {

                downloader.download {
                    downloaded, total ->

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
                    "دانلود فایل مدل تمام شد."
                } else {
                    "فایل دریافت شد اما معتبر نیست."
                }

            } catch (e: Exception) {

                downloadStatus =
                    "دانلود ناموفق بود: ${
                        e.message ?: "خطای نامشخص"
                    }"

            } finally {

                downloading = false
            }
        }
    }

    fun sendMessage() {

        val userPrompt = input.trim()

        if (
            userPrompt.isBlank() ||
            generating ||
            downloading ||
            transferring
        ) {
            return
        }

        if (!modelReady) {

            downloadStatus =
                "ابتدا فایل مدل را دانلود یا بازیابی کنید."

            return
        }

        messages.add(
            ChatMessage(userPrompt, true)
        )

        input = ""

        saveHistory()

        scope.launch {

            generating = true

            nativeStatus =
                "در حال آماده‌سازی پاسخ..."

            try {

                val formattedPrompt =
                    buildConversationPrompt()

                val response =
                    engine.generate(formattedPrompt)

                messages.add(
                    ChatMessage(response, false)
                )

                saveHistory()

                nativeStatus = if (
                    isEngineError(response)
                ) {
                    "موتور محلی نتوانست پاسخ را تولید کند."
                } else {
                    "پاسخ‌گویی موتور محلی انجام شد."
                }

            } catch (e: Exception) {

                val error =
                    "خطا در تولید پاسخ: ${
                        e.message ?: "خطای نامشخص"
                    }"

                messages.add(
                    ChatMessage(error, false)
                )

                saveHistory()

                nativeStatus = error

            } finally {

                generating = false
            }
        }
    }

    Scaffold(

        topBar = {

            TopAppBar(

                title = {
                    Text("FARID TECH AI")
                },

                navigationIcon = {

                    TextButton(
                        onClick = onBack
                    ) {
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

                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {

                OutlinedTextField(

                    value = input,

                    onValueChange = {
                        input = it
                    },

                    enabled = !generating &&
                        !downloading &&
                        !transferring,

                    modifier = Modifier.weight(1f),

                    placeholder = {
                        Text("پیام خود را بنویسید...")
                    }
                )

                Button(

                    onClick = {
                        sendMessage()
                    },

                    enabled = input.isNotBlank() &&
                        !generating &&
                        !downloading &&
                        !transferring,

                    modifier = Modifier.align(
                        Alignment.CenterVertically
                    )
                ) {

                    Text(
                        if (generating) {
                            "..."
                        } else {
                            "ارسال"
                        }
                    )
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

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {

                Column(

                    modifier = Modifier.padding(12.dp),

                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {

                    Text(
                        "وضعیت اتصال موتور بومی",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(nativeStatus)

                    if (generating) {

                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            "هوش مصنوعی در حال پاسخ‌گویی است..."
                        )
                    }
                }
            }

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {

                Column(

                    modifier = Modifier.padding(12.dp),

                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {

                    Text(
                        "موتور هوش مصنوعی آفلاین",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(downloadStatus)

                    if (downloading) {

                        LinearProgressIndicator(

                            progress = {
                                downloadProgress / 100f
                            },

                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("$downloadProgress٪")
                    }

                    Button(

                        onClick = {
                            startDownload()
                        },

                        enabled = !downloading &&
                            !modelReady &&
                            !transferring &&
                            !generating,

                        modifier = Modifier.fillMaxWidth()
                    ) {

                        Text(
                            when {
                                modelReady ->
                                    "فایل مدل موجود است"

                                downloading ->
                                    "در حال دانلود..."

                                else ->
                                    "دانلود مدل آفلاین"
                            }
                        )
                    }

                    OutlinedButton(

                        onClick = {

                            onExternalPickerStateChanged(true)

                            try {
                                backupLauncher.launch(
                                    "FARID-TECH-Qwen2.5-Coder-1.5B.gguf"
                                )
                            } catch (e: Exception) {
                                onExternalPickerStateChanged(false)
                                downloadStatus =
                                    "بازکردن محل ذخیره ناموفق بود."
                            }
                        },

                        enabled = modelReady &&
                            !downloading &&
                            !transferring &&
                            !generating,

                        modifier = Modifier.fillMaxWidth()
                    ) {

                        Text("پشتیبان‌گیری از مدل")
                    }

                    OutlinedButton(

                        onClick = {

                            onExternalPickerStateChanged(true)

                            try {
                                restoreLauncher.launch(
                                    arrayOf("*/*")
                                )
                            } catch (e: Exception) {
                                onExternalPickerStateChanged(false)
                                downloadStatus =
                                    "بازکردن انتخاب‌گر فایل ناموفق بود."
                            }
                        },

                        enabled = !downloading &&
                            !transferring &&
                            !generating,

                        modifier = Modifier.fillMaxWidth()
                    ) {

                        Text("بازیابی مدل از فایل")
                    }

                    if (transferring) {

                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            LazyColumn(

                modifier = Modifier.weight(1f),

                state = listState,

                verticalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {

                items(messages) { message ->

                    Card(

                        modifier = Modifier.fillMaxWidth(),

                        colors = CardDefaults.cardColors(

                            containerColor =
                                if (message.isUser) {
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

            listState.animateScrollToItem(
                messages.lastIndex
            )
        }
    }
}
