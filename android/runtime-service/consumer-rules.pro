# =============================================================================
# Consumer ProGuard rules for :android:runtime-service (merged into app-ui)
# PLAY-FGS-DECLARATION / ANDROID-SERVICE — keep FGS + Binder service classes.
# =============================================================================

-keep class com.omnillm.android.runtimeservice.service.** { <init>(); *; }
-keep class com.omnillm.android.runtimeservice.binder.** { *; }
-keep class com.omnillm.android.runtimeservice.** { *; }

# AIDL stubs used by RuntimeBindingService / AdminBindingService
-keep class ai.omnillm.api.** { *; }

# Parcelables crossing process boundaries (:ui ↔ :runtime)
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keep class * extends android.os.Binder { *; }
-keepclassmembers class * extends android.os.Binder {
    public static *** asInterface(android.os.IBinder);
    static final int TRANSACTION_*;
}

# Catalog enums used in binder payloads
-keep class com.omnillm.core.canonical.generated.** { *; }
-keep class com.omnillm.core.errors.generated.** { *; }
-keep class com.omnillm.core.state.generated.** { *; }

# JNI bridge (libomnillm_llama) — load only in :runtime / workers (INV-001)
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.omnillm.engines.llamacpp.native.JniNativeBridge { *; }
-keepclassmembers class com.omnillm.engines.llamacpp.native.JniNativeBridge {
    public static *** native*(...);
}
-keep class com.omnillm.engines.llamacpp.native.JniNativeBackend { *; }
-keep class com.omnillm.engines.llamacpp.native.NativeEventCallback { *; }
-keep class * implements com.omnillm.engines.llamacpp.native.NativeEventCallback {
    public void onNativeEvent(...);
}

