/**
 * JNI bridge for com.omnillm.engines.llamacpp.native.JniNativeBridge
 * (ENGINE-LLAMACPP §2, ANDROID-NATIVE §4).
 *
 * Loaded only from verified application module location via System.loadLibrary.
 * Never accepts downloaded .so paths.
 */

#include "omnillm_llama.h"

#include <jni.h>

#include <string>

namespace {

struct StreamSink {
    JNIEnv* env = nullptr;
    jobject callback = nullptr;
    jmethodID onEvent = nullptr;
    jobject cancelAtomic = nullptr;
    jmethodID atomicGet = nullptr;
    int32_t* cancelFlag = nullptr;
};

void stream_cb(
    int32_t kind,
    const char* payload_digest_hex,
    const char* attributes_kv,
    void* user_data) {
    auto* sink = static_cast<StreamSink*>(user_data);
    if (sink == nullptr || sink->env == nullptr) {
        return;
    }
    // Refresh cooperative cancel from AtomicInteger before/between deltas.
    if (sink->cancelAtomic != nullptr && sink->atomicGet != nullptr &&
        sink->cancelFlag != nullptr) {
        const jint v = sink->env->CallIntMethod(sink->cancelAtomic, sink->atomicGet);
        *sink->cancelFlag = static_cast<int32_t>(v);
    }
    if (sink->callback == nullptr || sink->onEvent == nullptr) {
        return;
    }
    jstring jDigest = payload_digest_hex != nullptr
        ? sink->env->NewStringUTF(payload_digest_hex)
        : nullptr;
    jstring jAttrs = attributes_kv != nullptr
        ? sink->env->NewStringUTF(attributes_kv)
        : sink->env->NewStringUTF("");
    sink->env->CallVoidMethod(sink->callback, sink->onEvent, kind, jDigest, jAttrs);
    if (jDigest != nullptr) sink->env->DeleteLocalRef(jDigest);
    if (jAttrs != nullptr) sink->env->DeleteLocalRef(jAttrs);
}

std::string jstring_to_utf8(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring new_jstring(JNIEnv* env, const char* s) {
    return env->NewStringUTF(s != nullptr ? s : "");
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_omnillm_engines_llamacpp_native_JniNativeBridge_nativeLibraryLabel(
    JNIEnv* env,
    jclass /*clazz*/) {
    char buf[192];
    omnillm_llama_library_label(buf, sizeof(buf));
    return new_jstring(env, buf);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omnillm_engines_llamacpp_native_JniNativeBridge_nativeAbiVersion(
    JNIEnv* /*env*/,
    jclass /*clazz*/) {
    return omnillm_llama_abi_version();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omnillm_engines_llamacpp_native_JniNativeBridge_nativeUpstreamLinked(
    JNIEnv* /*env*/,
    jclass /*clazz*/) {
    return omnillm_llama_upstream_linked() != 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omnillm_engines_llamacpp_native_JniNativeBridge_nativeProbe(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jBackend,
    jstring jOpToken,
    jobjectArray jOutAttrs) {
    const std::string backend = jstring_to_utf8(env, jBackend);
    const std::string op = jstring_to_utf8(env, jOpToken);
    char attrs[512];
    attrs[0] = '\0';
    const int32_t rc = omnillm_llama_probe(
        backend.c_str(),
        op.c_str(),
        attrs,
        sizeof(attrs));
    if (jOutAttrs != nullptr && env->GetArrayLength(jOutAttrs) > 0) {
        env->SetObjectArrayElement(jOutAttrs, 0, new_jstring(env, attrs));
    }
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omnillm_engines_llamacpp_native_JniNativeBridge_nativeLoadModel(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jStorageRoot,
    jstring jInstallKey,
    jstring jBackend,
    jint nCtx,
    jint nThreads,
    jstring jTicket,
    jstring jResolvedPath,
    jint modelFd,
    jobjectArray jOutToken) {
    const std::string storage = jstring_to_utf8(env, jStorageRoot);
    const std::string install = jstring_to_utf8(env, jInstallKey);
    const std::string backend = jstring_to_utf8(env, jBackend);
    const std::string ticket = jstring_to_utf8(env, jTicket);
    const std::string path = jstring_to_utf8(env, jResolvedPath);
    char token[96];
    token[0] = '\0';
    const int32_t rc = omnillm_llama_load_model(
        storage.c_str(),
        install.c_str(),
        backend.c_str(),
        static_cast<int32_t>(nCtx),
        static_cast<int32_t>(nThreads),
        ticket.c_str(),
        path.empty() ? nullptr : path.c_str(),
        static_cast<int32_t>(modelFd),
        token,
        sizeof(token));
    if (rc == OMNILLM_LLAMA_OK && jOutToken != nullptr &&
        env->GetArrayLength(jOutToken) > 0) {
        env->SetObjectArrayElement(jOutToken, 0, new_jstring(env, token));
    }
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omnillm_engines_llamacpp_native_JniNativeBridge_nativeCreateSession(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jModel,
    jint nCtx,
    jlong seed,
    jboolean hasSeed,
    jobjectArray jOutToken) {
    const std::string model = jstring_to_utf8(env, jModel);
    char token[96];
    token[0] = '\0';
    const int32_t rc = omnillm_llama_create_session(
        model.c_str(),
        static_cast<int32_t>(nCtx),
        static_cast<int64_t>(seed),
        hasSeed ? 1 : 0,
        token,
        sizeof(token));
    if (rc == OMNILLM_LLAMA_OK && jOutToken != nullptr &&
        env->GetArrayLength(jOutToken) > 0) {
        env->SetObjectArrayElement(jOutToken, 0, new_jstring(env, token));
    }
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omnillm_engines_llamacpp_native_JniNativeBridge_nativeGenerate(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jSession,
    jstring jOpToken,
    jstring jPromptDigest,
    jstring jPromptUtf8,
    jint maxTokens,
    jfloat temperature,
    jboolean hasTemp,
    jfloat topP,
    jboolean hasTopP,
    jint topK,
    jboolean hasTopK,
    jint stopCount,
    jobject cancelFlagObj,
    jobject eventCallback,
    jintArray jOutCounts,
    jobjectArray jOutStopReason) {
    const std::string session = jstring_to_utf8(env, jSession);
    const std::string op = jstring_to_utf8(env, jOpToken);
    const std::string prompt = jstring_to_utf8(env, jPromptDigest);
    const std::string promptUtf8 = jstring_to_utf8(env, jPromptUtf8);

    int32_t cancel_local = 0;
    StreamSink sink;
    sink.env = env;
    sink.callback = eventCallback;
    sink.cancelAtomic = cancelFlagObj;
    sink.cancelFlag = &cancel_local;

    if (cancelFlagObj != nullptr) {
        jclass atomicCls = env->FindClass("java/util/concurrent/atomic/AtomicInteger");
        if (atomicCls != nullptr) {
            sink.atomicGet = env->GetMethodID(atomicCls, "get", "()I");
            if (sink.atomicGet != nullptr) {
                cancel_local = env->CallIntMethod(cancelFlagObj, sink.atomicGet);
            }
        }
    }
    if (eventCallback != nullptr) {
        jclass cbCls = env->GetObjectClass(eventCallback);
        sink.onEvent = env->GetMethodID(
            cbCls,
            "onNativeEvent",
            "(ILjava/lang/String;Ljava/lang/String;)V");
    }

    int32_t prompt_tokens = 0;
    int32_t completion_tokens = 0;
    char stop_reason[64];
    stop_reason[0] = '\0';

    const int32_t rc = omnillm_llama_generate(
        session.c_str(),
        op.c_str(),
        prompt.c_str(),
        promptUtf8.empty() ? nullptr : promptUtf8.c_str(),
        static_cast<int32_t>(maxTokens),
        static_cast<float>(temperature),
        hasTemp ? 1 : 0,
        static_cast<float>(topP),
        hasTopP ? 1 : 0,
        static_cast<int32_t>(topK),
        hasTopK ? 1 : 0,
        static_cast<int32_t>(stopCount),
        &cancel_local,
        stream_cb,
        &sink,
        &prompt_tokens,
        &completion_tokens,
        stop_reason,
        sizeof(stop_reason));

    if (jOutCounts != nullptr && env->GetArrayLength(jOutCounts) >= 2) {
        jint counts[2] = {prompt_tokens, completion_tokens};
        env->SetIntArrayRegion(jOutCounts, 0, 2, counts);
    }
    if (jOutStopReason != nullptr && env->GetArrayLength(jOutStopReason) > 0) {
        env->SetObjectArrayElement(
            jOutStopReason,
            0,
            new_jstring(env, stop_reason));
    }
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omnillm_engines_llamacpp_native_JniNativeBridge_nativeEmbed(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jModel,
    jstring jOpToken,
    jstring jInputDigest) {
    const std::string model = jstring_to_utf8(env, jModel);
    const std::string op = jstring_to_utf8(env, jOpToken);
    const std::string input = jstring_to_utf8(env, jInputDigest);
    return omnillm_llama_embed(model.c_str(), op.c_str(), input.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omnillm_engines_llamacpp_native_JniNativeBridge_nativeCloseSession(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jSession) {
    const std::string session = jstring_to_utf8(env, jSession);
    return omnillm_llama_close_session(session.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omnillm_engines_llamacpp_native_JniNativeBridge_nativeUnloadModel(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jModel) {
    const std::string model = jstring_to_utf8(env, jModel);
    return omnillm_llama_unload_model(model.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omnillm_engines_llamacpp_native_JniNativeBridge_nativeRequestCancel(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring jOpToken) {
    const std::string op = jstring_to_utf8(env, jOpToken);
    return omnillm_llama_request_cancel(op.c_str());
}
