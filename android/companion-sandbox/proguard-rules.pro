# =============================================================================
# OmniLLM :android:companion-sandbox — R8 / ProGuard release skeleton
# Authority: SEC-EXTERNAL-SANDBOX, ADR-007, ANDROID-DIST
#
# Companion is a different applicationId/UID APK. Keep rules are intentionally
# narrow: no main-app DB, token vault, or admin surface exists here.
# =============================================================================

-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Entry points
-keep class com.omnillm.companion.CompanionApplication { <init>(); }
-keep class com.omnillm.companion.CompanionSandboxService { <init>(); }

# Binder / ticket handshake types (protocol fail-closed)
-keep class com.omnillm.companion.** { *; }
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keep class * extends android.os.Binder { *; }

# Canonical digests / errors only (no control-plane types)
-keep class com.omnillm.core.canonical.generated.** { *; }
-keep class com.omnillm.core.errors.generated.** { *; }

# JNI keep (companion may host untrusted acceleration natives later — ADR-007)
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class * extends android.os.Binder {
    public static *** asInterface(android.os.IBinder);
}

-dontwarn kotlinx.coroutines.**
