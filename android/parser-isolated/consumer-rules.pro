# Consumer ProGuard rules for :android:parser-isolated (isolatedProcess).
# Platform ANDROID-ISOLATED-PROCESS: no app permissions; still not DoS-proof.

-keep class com.omnillm.android.parserisolated.** { <init>(); *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
