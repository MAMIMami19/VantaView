// SPDX-License-Identifier: GPL-3.0-or-later
// JNI bridge to whisper.cpp for VantaView's AI subtitles.
#include <jni.h>
#include <string>
#include <thread>
#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_vantaview_app_data_aisubs_WhisperNative_init(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vantaview_app_data_aisubs_WhisperNative_free(JNIEnv *, jobject, jlong ptr) {
    if (ptr != 0) whisper_free(reinterpret_cast<whisper_context *>(ptr));
}

// Returns "<language code>\n<text>" for one chunk of 16 kHz mono audio.
extern "C" JNIEXPORT jstring JNICALL
Java_com_vantaview_app_data_aisubs_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong ptr, jfloatArray samples, jboolean translate, jint threads, jstring prompt) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx == nullptr) return env->NewStringUTF("");
    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const char *pr = prompt ? env->GetStringUTFChars(prompt, nullptr) : nullptr;

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_special = false;
    wp.print_timestamps = false;
    wp.translate = translate;
    wp.language = "auto";
    wp.detect_language = false;
    wp.n_threads = threads > 0 ? threads : 2;
    wp.no_context = true;
    wp.single_segment = true;
    wp.no_timestamps = true;
    wp.suppress_blank = true;
    wp.suppress_nst = true;
    wp.temperature_inc = 0.0f;
    wp.initial_prompt = (pr && pr[0]) ? pr : nullptr;

    std::string out;
    if (whisper_full(ctx, wp, data, n) == 0) {
        const int lang = whisper_full_lang_id(ctx);
        out = lang >= 0 ? whisper_lang_str(lang) : "";
        out += "\n";
        const int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; i++) out += whisper_full_get_segment_text(ctx, i);
    }
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (pr) env->ReleaseStringUTFChars(prompt, pr);
    return env->NewStringUTF(out.c_str());
}
