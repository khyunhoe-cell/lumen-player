// 앱과 whisper.cpp 사이의 JNI 연결. 결과는 "시작ms\t끝ms\t대사"의 UTF-8 바이트 배열로 돌려준다.
// whisper 토큰은 바이트 단위라 구간 경계에서 한글이 잘릴 수 있어 NewStringUTF 대신 바이트로 넘긴다.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include "whisper.h"

#define LOG_TAG "LumenWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct Handle {
    whisper_context *ctx = nullptr;
    std::atomic<bool> abort{false};
};

struct ProgressBridge {
    JNIEnv *env;
    jobject listener;
    jmethodID method;
};

void on_progress(whisper_context *, whisper_state *, int progress, void *user) {
    auto *b = static_cast<ProgressBridge *>(user);
    if (b->listener != nullptr) b->env->CallVoidMethod(b->listener, b->method, progress);
}

bool on_abort(void *user) {
    return static_cast<Handle *>(user)->abort.load();
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_lumen_player_subtitle_ai_WhisperNative_nativeInit(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cparams);
    env->ReleaseStringUTFChars(path, p);
    if (ctx == nullptr) return 0;
    auto *h = new Handle();
    h->ctx = ctx;
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lumen_player_subtitle_ai_WhisperNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (h == nullptr) return;
    whisper_free(h->ctx);
    delete h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_lumen_player_subtitle_ai_WhisperNative_nativeAbort(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (h != nullptr) h->abort.store(true);
}

namespace {
jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}
}  // namespace

/**
 * 결과 배열의 0번은 감지한 언어 코드, 1번부터 "t0\tt1\ttext" (밀리초). 모두 UTF-8 바이트.
 * 실패하거나 취소되면 null.
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_lumen_player_subtitle_ai_WhisperNative_nativeTranscribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray pcm, jstring language, jstring prompt,
        jint threads, jint beamSize, jobject listener) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (h == nullptr) return nullptr;
    h->abort.store(false);

    const jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples(n);
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());

    const char *lang = env->GetStringUTFChars(language, nullptr);
    const char *init = env->GetStringUTFChars(prompt, nullptr);
    std::string langStr(lang);
    std::string promptStr(init);
    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseStringUTFChars(prompt, init);

    whisper_full_params params = whisper_full_default_params(
            beamSize > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.n_threads = threads;
    params.language = langStr.empty() ? "auto" : langStr.c_str();
    params.detect_language = false;
    params.initial_prompt = promptStr.empty() ? nullptr : promptStr.c_str();
    params.suppress_blank = true;
    params.no_context = false;
    if (beamSize > 1) params.beam_search.beam_size = beamSize;

    ProgressBridge bridge{env, listener, nullptr};
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        bridge.method = env->GetMethodID(cls, "onProgress", "(I)V");
        params.progress_callback = on_progress;
        params.progress_callback_user_data = &bridge;
    }
    params.abort_callback = on_abort;
    params.abort_callback_user_data = h;

    LOGI("transcribe: %d samples, lang=%s, threads=%d, beam=%d", n, params.language, threads, beamSize);
    if (whisper_full(h->ctx, params, samples.data(), n) != 0 || h->abort.load()) return nullptr;

    const int segments = whisper_full_n_segments(h->ctx);
    jclass bytesClass = env->FindClass("[B");
    jobjectArray out = env->NewObjectArray(segments + 1, bytesClass, nullptr);
    const char *detected = whisper_lang_str(whisper_full_lang_id(h->ctx));
    jbyteArray langBytes = to_bytes(env, detected != nullptr ? detected : "");
    env->SetObjectArrayElement(out, 0, langBytes);
    env->DeleteLocalRef(langBytes);
    for (int i = 0; i < segments; ++i) {
        // whisper 시간 단위는 10ms
        const int64_t t0 = whisper_full_get_segment_t0(h->ctx, i) * 10;
        const int64_t t1 = whisper_full_get_segment_t1(h->ctx, i) * 10;
        std::string line = std::to_string(t0) + "\t" + std::to_string(t1) + "\t" + whisper_full_get_segment_text(h->ctx, i);
        jbyteArray bytes = to_bytes(env, line);
        env->SetObjectArrayElement(out, i + 1, bytes);
        env->DeleteLocalRef(bytes);
    }
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lumen_player_subtitle_ai_WhisperNative_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}
