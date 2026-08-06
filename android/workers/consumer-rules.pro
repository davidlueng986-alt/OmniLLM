# Consumer ProGuard rules for :android:workers (same-UID crash worker process).
# Not a security sandbox — ADR-007 untrusted acceleration uses companion instead.

-keep class com.omnillm.android.workers.** { <init>(); *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
