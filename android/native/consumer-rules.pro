# Consumer ProGuard rules for :android:native (JNI / 16 KB packaging).
# Engines load only outside the UI process (INV-001). Merged into app-ui R8.
#
# JNI registration (jni_bridge.cpp):
#   Java_com_omnillm_engines_llamacpp_native_JniNativeBridge_*

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.omnillm.engines.llamacpp.native.JniNativeBridge {
    *;
}
-keepclassmembers class com.omnillm.engines.llamacpp.native.JniNativeBridge {
    public static *** native*(...);
}
-keep class com.omnillm.engines.llamacpp.native.NativeEventCallback { *; }
-keep class * implements com.omnillm.engines.llamacpp.native.NativeEventCallback {
    public void onNativeEvent(...);
}
