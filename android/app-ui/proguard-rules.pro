# =============================================================================
# OmniLLM :android:app-ui — R8 / ProGuard release keep rules
# Authority: ANDROID-DIST, ANDROID-BASELINE, SEC-PRIVACY, ADR-007/010/011
#
# Enable with isMinifyEnabled=true in release after smoke-testing AIDL bind,
# FGS start, content-report submit path, companion handshake, and JNI load
# (see gradle/RELEASE_CHECKLIST.md §H).
# Do not invent keep rules that weaken security by retaining secrets in logs.
# =============================================================================

# --- Kotlin / coroutines ------------------------------------------------------
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**
-dontwarn kotlinx.serialization.**

# --- Compose / UI (release minify) --------------------------------------------
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# --- AIDL / Binder stubs (interfaces:aidl projections) ------------------------
# Parcelable / Stub classes must survive shrinking or Binder will fail closed.
# Package authority: specs/aidl/omnillm-aidl.yaml → ai.omnillm.api.*
-keep class ai.omnillm.api.** { *; }
-keep class com.omnillm.**.aidl.** { *; }
-keep class com.omnillm.interfaces.aidl.** { *; }
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keep class * extends android.os.Binder { *; }
-keepclassmembers class * extends android.os.Binder {
    public <init>(...);
    public static *** asInterface(android.os.IBinder);
}
# Generated AIDL Stub$Proxy / Stub.TRANSACTION_* reflection
-keepclassmembers class * extends android.os.Binder {
    static final int TRANSACTION_*;
    public static final java.lang.String DESCRIPTOR;
}

# --- Android components declared in merged manifest ---------------------------
-keep class com.omnillm.ui.OmniApplication { <init>(); }
-keep class com.omnillm.ui.MainActivity { <init>(); }
-keep class com.omnillm.android.runtimeservice.service.** { <init>(); }
-keep class com.omnillm.android.runtimeservice.binder.** { *; }
-keep class com.omnillm.android.workers.** { <init>(); }
-keep class com.omnillm.android.parserisolated.** { <init>(); }

# --- JNI / native engine bridge (loaded only in :runtime / workers) -----------
# jni_bridge.cpp registers:
#   Java_com_omnillm_engines_llamacpp_native_JniNativeBridge_*
# Kotlin @JvmStatic external methods + GetMethodID on NativeEventCallback.
# INV-001: UI process must not load natives; keeps still apply because runtime-
# service is merged into the main APK and R8 runs at app level.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.omnillm.engines.llamacpp.native.JniNativeBridge {
    *;
}
-keepclassmembers class com.omnillm.engines.llamacpp.native.JniNativeBridge {
    public static *** native*(...);
}
-keep class com.omnillm.engines.llamacpp.native.JniNativeBackend { *; }
-keep class com.omnillm.engines.llamacpp.native.JniNativeMapping { *; }
-keep class com.omnillm.engines.llamacpp.native.NativeEventCallback { *; }
-keep class * implements com.omnillm.engines.llamacpp.native.NativeEventCallback {
    public void onNativeEvent(...);
}
# AtomicInteger cancel flag used from JNI during generate
-keepclassmembers class java.util.concurrent.atomic.AtomicInteger {
    public int get();
    public void set(int);
}

# --- Canonical catalog enums / errors (fail-closed INV-018) -------------------
-keep class com.omnillm.core.canonical.generated.** { *; }
-keep class com.omnillm.core.errors.generated.** { *; }
-keep class com.omnillm.core.state.generated.** { *; }
-keepclassmembers enum com.omnillm.core.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Content report payload / FSM (FEAT-AI-CONTENT-REPORT) --------------------
-keep class com.omnillm.features.contentreport.** { *; }

# --- Serialization (if kotlinx.serialization used on wire models) -------------
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep,includedescriptorclasses class com.omnillm.**$$serializer { *; }
-keepclassmembers class com.omnillm.** {
    *** Companion;
}
-keepclasseswithmembers class com.omnillm.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Line numbers for crash / diagnostic redaction pipelines ------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Do not strip BuildConfig used by report appBuild field -------------------
-keep class com.omnillm.ui.BuildConfig { *; }

# --- Optional: aggressive optimize notes (uncomment after first release gate) -
# -assumenosideeffects class android.util.Log {
#     public static *** d(...);
#     public static *** v(...);
# }
