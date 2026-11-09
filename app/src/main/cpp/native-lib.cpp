
#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <algorithm>
#include <cstdint>
#include <climits>

#include "llama.h"

namespace {

std::mutex engineMutex;

llama_model* model = nullptr;
llama_context* context = nullptr;

bool backendInitialized = false;

void releaseModelLocked() {
    if (context != nullptr) {
        llama_free(context);
        context = nullptr;
    }

    if (model != nullptr) {
        llama_model_free(model);
        model = nullptr;
    }
}

jstring makeResult(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_com_faridtech_ai_NativeLlama_nativeBridgeCheck(
        JNIEnv* env,
        jobject /* this */) {

    return makeResult(
        env,
        "FARID TECH AI Native Bridge is ready"
    );
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_faridtech_ai_NativeLlama_nativeLoadModel(
        JNIEnv* env,
        jobject /* this */,
        jstring modelPath) {

    if (modelPath == nullptr) {
        return makeResult(env, "مسیر مدل مشخص نشده است.");
    }

    const char* pathChars =
        env->GetStringUTFChars(modelPath, nullptr);

    if (pathChars == nullptr) {
        return makeResult(env, "خواندن مسیر مدل ناموفق بود.");
    }

    std::string path(pathChars);

    env->ReleaseStringUTFChars(modelPath, pathChars);

    std::lock_guard<std::mutex> lock(engineMutex);

    try {
        if (!backendInitialized) {
            llama_backend_init();
            backendInitialized = true;
        }

        llama_model_params modelParams =
            llama_model_default_params();

        modelParams.n_gpu_layers = 0;
        modelParams.check_tensors = true;

        llama_model* newModel =
            llama_model_load_from_file(
                path.c_str(),
                modelParams
            );

        if (newModel == nullptr) {
            return makeResult(
                env,
                "بارگذاری مدل ناموفق بود. فایل GGUF را بررسی کن."
            );
        }

        llama_context_params contextParams =
            llama_context_default_params();

        contextParams.n_ctx = 2048;
        contextParams.n_batch = 512;
        contextParams.n_ubatch = 512;
        contextParams.n_threads = 4;
        contextParams.n_threads_batch = 4;

        llama_context* newContext =
            llama_init_from_model(
                newModel,
                contextParams
            );

        if (newContext == nullptr) {
            llama_model_free(newModel);

            return makeResult(
                env,
                "ساخت محیط اجرای مدل ناموفق بود."
            );
        }

        releaseModelLocked();

        model = newModel;
        context = newContext;

        return makeResult(
            env,
            "مدل با موفقیت بارگذاری شد."
        );

    } catch (...) {
        return makeResult(
            env,
            "خطای غیرمنتظره هنگام بارگذاری مدل."
        );
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_faridtech_ai_NativeLlama_nativeGenerate(
        JNIEnv* env,
        jobject /* this */,
        jstring promptText,
        jint maxTokens) {

    if (promptText == nullptr) {
        return makeResult(env, "پیام خالی است.");
    }

    const char* promptChars =
        env->GetStringUTFChars(promptText, nullptr);

    if (promptChars == nullptr) {
        return makeResult(env, "خواندن پیام ناموفق بود.");
    }

    std::string prompt(promptChars);

    env->ReleaseStringUTFChars(promptText, promptChars);

    if (prompt.empty()) {
        return makeResult(env, "لطفاً پیام خود را وارد کن.");
    }

    if (prompt.size() > 64000) {
        return makeResult(env, "پیام بیش از حد طولانی است.");
    }

    std::lock_guard<std::mutex> lock(engineMutex);

    if (model == nullptr || context == nullptr) {
        return makeResult(env, "ابتدا باید مدل را بارگذاری کنی.");
    }

    try {
        const llama_vocab* vocab =
            llama_model_get_vocab(model);

        if (vocab == nullptr) {
            return makeResult(env, "واژگان مدل در دسترس نیست.");
        }

        std::vector<llama_token> tokens(
            prompt.size() + 16
        );

        int32_t tokenCount = llama_tokenize(
            vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            true,
            false
        );

        if (tokenCount < 0) {
            const int32_t required = -tokenCount;

            tokens.resize(
                static_cast<size_t>(required)
            );

            tokenCount = llama_tokenize(
                vocab,
                prompt.c_str(),
                static_cast<int32_t>(prompt.size()),
                tokens.data(),
                required,
                true,
                false
            );
        }

        if (tokenCount <= 0) {
            return makeResult(env, "تبدیل پیام به توکن ناموفق بود.");
        }

        tokens.resize(
            static_cast<size_t>(tokenCount)
        );

        const uint32_t contextSize =
            llama_n_ctx(context);

        if (tokens.size() >= contextSize) {
            return makeResult(
                env,
                "پیام برای ظرفیت فعلی مدل بیش از حد طولانی است."
            );
        }

        // Process prompt in batches.
        const int32_t batchLimit = 512;

        for (size_t offset = 0;
             offset < tokens.size();
             offset += batchLimit) {

            const int32_t count =
                static_cast<int32_t>(
                    std::min(
                        static_cast<size_t>(batchLimit),
                        tokens.size() - offset
                    )
                );

            llama_batch batch = llama_batch_get_one(
                tokens.data() + offset,
                count
            );

            if (llama_decode(context, batch) != 0) {
                return makeResult(
                    env,
                    "پردازش پیام توسط موتور هوش مصنوعی ناموفق بود."
                );
            }
        }

        llama_sampler_chain_params samplerParams =
            llama_sampler_chain_default_params();

        llama_sampler* sampler =
            llama_sampler_chain_init(samplerParams);

        if (sampler == nullptr) {
            return makeResult(env, "ساخت نمونه‌گیر ناموفق بود.");
        }

        llama_sampler_chain_add(
            sampler,
            llama_sampler_init_top_k(40)
        );

        llama_sampler_chain_add(
            sampler,
            llama_sampler_init_top_p(0.90f, 1)
        );

        llama_sampler_chain_add(
            sampler,
            llama_sampler_init_temp(0.70f)
        );

        llama_sampler_chain_add(
            sampler,
            llama_sampler_init_dist(LLAMA_DEFAULT_SEED)
        );

        std::string output;

        const int limit = std::max(
            1,
            std::min(static_cast<int>(maxTokens), 512)
        );

        int generated = 0;

        for (int i = 0; i < limit; ++i) {

            llama_token newToken =
                llama_sampler_sample(
                    sampler,
                    context,
                    -1
                );

            if (llama_vocab_is_eog(vocab, newToken)) {
                break;
            }

            char piece[256];

            int32_t pieceLength =
                llama_token_to_piece(
                    vocab,
                    newToken,
                    piece,
                    sizeof(piece),
                    0,
                    false
                );

            if (pieceLength < 0) {
                std::vector<char> largeBuffer(
                    static_cast<size_t>(-pieceLength) + 1
                );

                pieceLength = llama_token_to_piece(
                    vocab,
                    newToken,
                    largeBuffer.data(),
                    static_cast<int32_t>(largeBuffer.size()),
                    0,
                    false
                );

                if (pieceLength > 0) {
                    output.append(
                        largeBuffer.data(),
                        static_cast<size_t>(pieceLength)
                    );
                }

            } else if (pieceLength > 0) {
                output.append(
                    piece,
                    static_cast<size_t>(pieceLength)
                );
            }

            generated++;

            llama_batch nextBatch =
                llama_batch_get_one(&newToken, 1);

            if (llama_decode(context, nextBatch) != 0) {
                break;
            }

            if (tokens.size() + generated >= contextSize) {
                break;
            }
        }

        llama_sampler_free(sampler);

        if (output.empty()) {
            return makeResult(
                env,
                "مدل پاسخی تولید نکرد."
            );
        }

        return makeResult(env, output);

    } catch (...) {
        return makeResult(
            env,
            "خطای غیرمنتظره هنگام تولید پاسخ."
        );
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_faridtech_ai_NativeLlama_nativeRelease(
        JNIEnv* /* env */,
        jobject /* this */) {

    std::lock_guard<std::mutex> lock(engineMutex);

    releaseModelLocked();
}
