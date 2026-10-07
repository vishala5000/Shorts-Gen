# R8/ProGuard is disabled for this app (isMinifyEnabled = false) because sherpa-onnx
# calls Kotlin classes from JNI (System.loadLibrary("sherpa-onnx-jni")).
# These rules are kept here in case you enable minification later.

-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

-keep class com.shortsgen.app.** { *; }

# Native methods must never be renamed or removed.
-keepclasseswithmembernames class * {
    native <methods>;
}

-dontwarn org.jetbrains.annotations.**
