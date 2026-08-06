# AIDL parcelables are projections of specs/aidl/omnillm-aidl.yaml — keep generated stubs.
# Required when app-ui release minify is enabled (R8 must not strip Binder stubs).
# Package: ai.omnillm.api.* (44 interfaces + parcelables under interfaces/aidl).

-keep class ai.omnillm.api.** { *; }
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
    static final int TRANSACTION_*;
    public static final java.lang.String DESCRIPTOR;
}
