package com.faridtech.ai

class NativeLlama {

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    external fun nativeBridgeCheck(): String
}
