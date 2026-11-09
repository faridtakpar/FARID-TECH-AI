
package com.faridtech.ai

class NativeLlama {

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    // بررسی ارتباط Kotlin و C++
    external fun nativeBridgeCheck(): String

    // بارگذاری مدل GGUF
    external fun nativeLoadModel(modelPath: String): String

    // تولید پاسخ توسط مدل
    external fun nativeGenerate(
        promptText: String,
        maxTokens: Int
    ): String

    // آزادسازی حافظه مدل
    external fun nativeRelease()
}
