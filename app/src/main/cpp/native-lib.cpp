#include <jni.h>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_faridtech_ai_NativeLlama_nativeBridgeCheck(
        JNIEnv* env,
        jobject /* this */) {

    return env->NewStringUTF(
        "FARID TECH AI Native Bridge is ready"
    );
}
