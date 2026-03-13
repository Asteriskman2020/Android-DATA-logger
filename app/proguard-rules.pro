# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /path-to-android-sdk/tools/proguard/proguard-android.txt

# Keep MQTT classes
-keep class org.eclipse.paho.** { *; }
-dontwarn org.eclipse.paho.**

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
