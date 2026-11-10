
#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <algorithm>
#include <cstdint>
#include <climits>
#include <memory>
#include <exception>

#include "llama.h"

namespace {

std::mutex engineMutex;

llama_model* model = nullptr;
llama_context* context = nullptr;

bool backendInitialized = false;

// Convert Java UTF-16 to UTF-8.
bool jstringToUtf8(
    JNIEnv* env,
    jstring value,
    std::string& output
) {
    if (value == nullptr) {
        return false;
    }

    const jchar* chars =
        env->GetStringChars(value, nullptr);

    if (chars == nullptr) {
        return false;
    }

    const jsize length =
        env->GetStringLength(value);

    output.clear();

    for (jsize i = 0; i < length; ++i) {

        uint32_t cp = chars[i];

        if (cp >= 0xD800 && cp <= 0xDBFF) {

            if (
                i + 1 < length &&
                chars[i + 1] >= 0xDC00 &&
                chars[i + 1] <= 0xDFFF
            ) {
                cp = 0x10000 +
                    ((cp - 0xD800) << 10) +
                    (chars[++i] - 0xDC00);
            } else {
                cp = 0xFFFD;
            }

        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }

        if (cp <= 0x7F) {

            output.push_back(static_cast<char>(cp));

        } else if (cp <= 0x7FF) {

            output.push_back(
                static_cast<char>(0xC0 | (cp >> 6))
            );

            output.push_back(
                static_cast<char>(0x80 | (cp & 0x3F))
            );

        } else if (cp <= 0xFFFF) {

            output.push_back(
                static_cast<char>(0xE0 | (cp >> 12))
            );

            output.push_back(
                static_cast<char>(0x80 | ((cp >> 6) & 0x3F))
            );

            output.push_back(
                static_cast<char>(0x80 | (cp & 0x3F))
            );

        } else {

            output.push_back(
                static_cast<char>(0xF0 | (cp >> 18))
            );

            output.push_back(
                static_cast<char>(0x80 | ((cp >> 12) & 0x3F))
            );

            output.push_back(
                static_cast<char>(0x80 | ((cp >> 6) & 0x3F))
            );

            output.push_back(
                static_cast<char>(0x80 | (cp & 0x3F))
            );
        }
    }

    env->ReleaseStringChars(value, chars);

    return true;
}

// Convert UTF-8 to UTF-16.
std::u16string utf8ToUtf16(
    const std::string& input
) {
    std::u16string output;

    size_t i = 0;

    while (i < input.size()) {

        uint32_t cp = 0;

        unsigned char c =
            static_cast<unsigned char>(input[i]);

        size_t extra = 0;

        if (c <= 0x7F) {

            cp = c;

        } else if (c >= 0xC2 && c <= 0xDF) {

            cp = c & 0x1F;
            extra = 1;

        } else if (c >= 0xE0 && c <= 0xEF) {

            cp = c & 0x0F;
            extra = 2;

        } else if (c >= 0xF0 && c <= 0xF4) {

            cp = c & 0x07;
            extra = 3;

        } else {

            output.push_back(u'\uFFFD');
            ++i;
            continue;
        }

        if (i + extra >= input.size()) {
            output.push_back(u'\uFFFD');
            break;
        }

        bool valid = true;

        for (size_t j = 1; j <= extra; ++j) {

            unsigned char next =
                static_cast<unsigned char>(input[i + j]);

            if ((next & 0xC0) != 0x80) {
                valid = false;
                break;
            }

            cp = (cp << 6) | (next & 0x3F);
        }

        if (!valid) {
            output.push_back(u'\uFFFD');
            ++i;
            continue;
        }

        if (
            (extra == 1 && cp < 0x80) ||
            (extra == 2 && cp < 0x800) ||
            (extra == 3 && cp < 0x10000) ||
            cp > 0x10FFFF ||
            (cp >= 0xD800 && cp <= 0xDFFF)
        ) {
            output.push_back(u'\uFFFD');
            i += extra + 1;
            continue;
        }

        if (cp <= 0xFFFF) {

            output.push_back(
                static_cast<char16_t>(cp)
            );

        } else {

            cp -= 0x10000;

            output.push_back(
                static_cast<char16_t>(
                    0xD800 + (cp >> 10)
                )
            );

            output.push_back(
                static_cast<char16_t>(
                    0xDC00 + (cp & 0x3FF)
                )
            );
        }

        i += extra + 1;
    }

    return output;
}

// Create Java string.
jstring makeResult(
    JNIEnv* env,
    const std::string& value
) {
    std::u16string converted =
        utf8ToUtf16(value);

    if (converted.size() > static_cast<size_t>(INT32_MAX)) {
        return nullptr;
    }

    return env->NewString(
        reinterpret_cast<const jchar*>(converted.data()),
        static_cast<jsize>(converted.size())
    );
}

// Persian error message.
jstring makeError(
    JNIEnv* env,
    const std::string& message
) {
    return makeResult(
        env,
        "خطا: " + message
    );
}

// Release context and model.
// Must be called while engineMutex is locked.
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

// Decode token batch.
bool decodeBatch(
    llama_context* ctx,
    llama_batch batch
) {
    return llama_decode(ctx, batch) == 0;
}

} // namespace


// --------------------------------------------------
// Native bridge check
// --------------------------------------------------

extern "C"
JNIEXPORT jstring JNICALL
Java_com_faridtech_ai_NativeLlama_nativeBridgeCheck(
    JNIEnv* env,
    jobject
) {
    return makeResult(
        env,
        "FARID TECH AI Native Bridge is ready"
    );
}


// --------------------------------------------------
// Load GGUF model
// --------------------------------------------------

extern "C"
JNIEXPORT jstring JNICALL
Java_com_faridtech_ai_NativeLlama_nativeLoadModel(
    JNIEnv* env,
    jobject,
    jstring modelPath
) {
    if (modelPath == nullptr) {
        return makeError(
            env,
            "مسیر مدل مشخص نشده است."
        );
    }

    std::string path;

    if (!jstringToUtf8(env, modelPath, path)) {
        return makeError(
            env,
            "خواندن مسیر مدل ناموفق بود."
        );
    }

    if (path.empty()) {
        return makeError(
            env,
            "مسیر مدل خالی است."
        );
    }

    std::lock_guard<std::mutex> lock(engineMutex);

    try {

        // Release old model BEFORE loading the new one.
        // This prevents two models from occupying memory
        // at the same time.
        releaseModelLocked();

        if (!backendInitialized) {

            llama_backend_init();

            backendInitialized = true;
        }

        llama_model_params modelParams =
            llama_model_default_params();

        modelParams.n_gpu_layers = 0;
        modelParams.check_tensors = true;

        model = llama_model_load_from_file(
            path.c_str(),
            modelParams
        );

        if (model == nullptr) {

            return makeError(
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

        context = llama_init_from_model(
            model,
            contextParams
        );

        if (context == nullptr) {

            // Clean up partially loaded model.
            releaseModelLocked();

            return makeError(
                env,
                "ساخت محیط اجرای مدل ناموفق بود."
            );
        }

        return makeResult(
            env,
            "مدل با موفقیت بارگذاری شد."
        );

    } catch (const std::exception&) {

        releaseModelLocked();

        return makeError(
            env,
            "خطای غیرمنتظره هنگام بارگذاری مدل."
        );

    } catch (...) {

        releaseModelLocked();

        return makeError(
            env,
            "خطای ناشناخته هنگام بارگذاری مدل."
        );
    }
}


// --------------------------------------------------
// Generate AI response
// --------------------------------------------------

extern "C"
JNIEXPORT jstring JNICALL
Java_com_faridtech_ai_NativeLlama_nativeGenerate(
    JNIEnv* env,
    jobject,
    jstring promptText,
    jint maxTokens
) {
    if (promptText == nullptr) {
        return makeError(
            env,
            "پیام خالی است."
        );
    }

    std::string prompt;

    if (!jstringToUtf8(env, promptText, prompt)) {
        return makeError(
            env,
            "خواندن پیام ناموفق بود."
        );
    }

    if (prompt.empty()) {
        return makeError(
            env,
            "لطفاً پیام خود را وارد کن."
        );
    }

    if (prompt.size() > 64000) {
        return makeError(
            env,
            "پیام بیش از حد طولانی است."
        );
    }

    std::lock_guard<std::mutex> lock(engineMutex);

    if (model == nullptr || context == nullptr) {
        return makeError(
            env,
            "ابتدا باید مدل را بارگذاری کنی."
        );
    }

    try {

        // Clear previous conversation memory.
        llama_memory_clear(
            llama_get_memory(context),
            true
        );

        const llama_vocab* vocab =
            llama_model_get_vocab(model);

        if (vocab == nullptr) {
            return makeError(
                env,
                "واژگان مدل در دسترس نیست."
            );
        }

        if (
            prompt.size() >
            static_cast<size_t>(INT32_MAX - 16)
        ) {
            return makeError(
                env,
                "طول پیام بیش از حد مجاز است."
            );
        }

        // Token buffer.
        std::vector<llama_token> tokens(
            prompt.size() + 16
        );

        int32_t tokenCount =
            llama_tokenize(
                vocab,
                prompt.c_str(),
                static_cast<int32_t>(prompt.size()),
                tokens.data(),
                static_cast<int32_t>(tokens.size()),
                true,
                true
            );

        if (tokenCount == INT32_MIN) {
            return makeError(
                env,
                "طول پیام برای تبدیل به توکن بیش از حد مجاز است."
            );
        }

        // Resize if token buffer is too small.
        if (tokenCount < 0) {

            const int32_t required = -tokenCount;

            if (required <= 0) {
                return makeError(
                    env,
                    "اندازه بافر توکن معتبر نیست."
                );
            }

            tokens.resize(
                static_cast<size_t>(required)
            );

            tokenCount =
                llama_tokenize(
                    vocab,
                    prompt.c_str(),
                    static_cast<int32_t>(prompt.size()),
                    tokens.data(),
                    required,
                    true,
                    true
                );
        }

        if (tokenCount <= 0) {
            return makeError(
                env,
                "تبدیل پیام به توکن ناموفق بود."
            );
        }

        tokens.resize(
            static_cast<size_t>(tokenCount)
        );

        const uint32_t contextSize =
            llama_n_ctx(context);

        if (tokens.size() >= contextSize) {
            return makeError(
                env,
                "پیام برای ظرفیت فعلی مدل بیش از حد طولانی است."
            );
        }

        const int32_t batchLimit =
            static_cast<int32_t>(
                llama_n_batch(context)
            );

        if (batchLimit <= 0) {
            return makeError(
                env,
                "ظرفیت پردازش دسته‌ای مدل معتبر نیست."
            );
        }

        // Process prompt in batches.
        for (
            size_t offset = 0;
            offset < tokens.size();
            offset += static_cast<size_t>(batchLimit)
        ) {

            const int32_t count =
                static_cast<int32_t>(
                    std::min(
                        static_cast<size_t>(batchLimit),
                        tokens.size() - offset
                    )
                );

            llama_batch batch =
                llama_batch_get_one(
                    tokens.data() + offset,
                    count
                );

            if (!decodeBatch(context, batch)) {
                return makeError(
                    env,
                    "پردازش پیام توسط موتور هوش مصنوعی ناموفق بود."
                );
            }
        }

        // Sampling chain.
        llama_sampler_chain_params samplerParams =
            llama_sampler_chain_default_params();

        std::unique_ptr<
            llama_sampler,
            decltype(&llama_sampler_free)
        > sampler(
            llama_sampler_chain_init(samplerParams),
            &llama_sampler_free
        );

        if (!sampler) {
            return makeError(
                env,
                "ساخت نمونه‌گیر ناموفق بود."
            );
        }

        auto addSampler =
            [&](llama_sampler* item) -> bool {

                if (item == nullptr) {
                    return false;
                }

                llama_sampler_chain_add(
                    sampler.get(),
                    item
                );

                return true;
            };

        if (!addSampler(
            llama_sampler_init_top_k(40)
        )) {
            return makeError(
                env,
                "ساخت فیلتر Top-K ناموفق بود."
            );
        }

        if (!addSampler(
            llama_sampler_init_top_p(0.90f, 1)
        )) {
            return makeError(
                env,
                "ساخت فیلتر Top-P ناموفق بود."
            );
        }

        if (!addSampler(
            llama_sampler_init_temp(0.70f)
        )) {
            return makeError(
                env,
                "تنظیم دمای تولید ناموفق بود."
            );
        }

        if (!addSampler(
            llama_sampler_init_dist(
                LLAMA_DEFAULT_SEED
            )
        )) {
            return makeError(
                env,
                "ساخت نمونه‌گیر تصادفی ناموفق بود."
            );
        }

        std::string output;

        const int limit =
            std::max(
                1,
                std::min(
                    static_cast<int>(maxTokens),
                    512
                )
            );

        // Generate tokens.
        for (int i = 0; i < limit; ++i) {

            llama_token newToken =
                llama_sampler_sample(
                    sampler.get(),
                    context,
                    -1
                );

            if (
                llama_vocab_is_eog(
                    vocab,
                    newToken
                )
            ) {
                break;
            }

            char piece[256];

            int32_t pieceLength =
                llama_token_to_piece(
                    vocab,
                    newToken,
                    piece,
                    static_cast<int32_t>(sizeof(piece)),
                    0,
                    false
                );

            if (pieceLength == INT32_MIN) {
                return makeError(
                    env,
                    "اندازه متن خروجی معتبر نیست."
                );
            }

            if (pieceLength < 0) {

                const size_t required =
                    static_cast<size_t>(
                        -static_cast<int64_t>(pieceLength)
                    );

                if (
                    required >=
                    static_cast<size_t>(INT32_MAX)
                ) {
                    return makeError(
                        env,
                        "اندازه توکن خروجی بیش از حد مجاز است."
                    );
                }

                std::vector<char> largeBuffer(
                    required + 1
                );

                pieceLength =
                    llama_token_to_piece(
                        vocab,
                        newToken,
                        largeBuffer.data(),
                        static_cast<int32_t>(
                            largeBuffer.size()
                        ),
                        0,
                        false
                    );

                if (pieceLength <= 0) {
                    return makeError(
                        env,
                        "تبدیل توکن خروجی به متن ناموفق بود."
                    );
                }

                output.append(
                    largeBuffer.data(),
                    static_cast<size_t>(pieceLength)
                );

            } else if (pieceLength > 0) {

                output.append(
                    piece,
                    static_cast<size_t>(pieceLength)
                );
            }

            // Prevent context overflow.
            if (
                tokens.size() +
                static_cast<size_t>(i) + 1 >= contextSize
            ) {
                break;
            }

            llama_batch nextBatch =
                llama_batch_get_one(
                    &newToken,
                    1
                );

            if (!decodeBatch(context, nextBatch)) {
                return makeError(
                    env,
                    "پردازش توکن خروجی ناموفق بود."
                );
            }
        }

        if (output.empty()) {
            return makeError(
                env,
                "مدل پاسخی تولید نکرد."
            );
        }

        return makeResult(
            env,
            output
        );

    } catch (const std::exception&) {

        return makeError(
            env,
            "خطای غیرمنتظره هنگام تولید پاسخ."
        );

    } catch (...) {

        return makeError(
            env,
            "خطای ناشناخته هنگام تولید پاسخ."
        );
    }
}


// --------------------------------------------------
// Release native model
// --------------------------------------------------

extern "C"
JNIEXPORT void JNICALL
Java_com_faridtech_ai_NativeLlama_nativeRelease(
    JNIEnv*,
    jobject
) {
    std::lock_guard<std::mutex> lock(engineMutex);

    releaseModelLocked();
}
