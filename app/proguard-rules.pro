# WiFi Scanner ProGuard Rules

# Keep Hilt generated code
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# Keep Room entities
-keep class com.rift.core.data.** { *; }

# Keep Kotlin coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-dontwarn okio.**

# Keep Compose
-keep class androidx.compose.** { *; }

# Keep Coil
-keep class coil.** { *; }

# Keep our data models from being stripped
-keepclassmembers class com.rift.** {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}

# ONNX Runtime — keep all classes to prevent inference failures
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# General Android rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
