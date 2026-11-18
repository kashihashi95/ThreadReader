# ==============================================================================
# ProGuard / R8 Configuration for ThreadReader
# ==============================================================================

# ------------------------------------------------------------------------------
# General Keep Attributes & Flags
# ------------------------------------------------------------------------------
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# Preserve native JNI methods across all packages
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ------------------------------------------------------------------------------
# 1. ONNX Runtime (ai.onnxruntime)
# ------------------------------------------------------------------------------
# Preserve ONNX Runtime classes, interfaces, native handles, and JNI entrypoints
-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ------------------------------------------------------------------------------
# 2. Kotlinx Serialization
# ------------------------------------------------------------------------------
# Preserve classes annotated with @Serializable, their fields, and companion objects
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable class *;
    @kotlinx.serialization.SerialInfo class *;
}

# Preserve generated $serializer companions and static factory methods
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer $serializer;
}
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Preserve PolymorphicSerializer, SealedClassSerializer, and internal reflection mechanisms
-keep class kotlinx.serialization.PolymorphicSerializer { *; }
-keep class kotlinx.serialization.SealedClassSerializer { *; }
-keep class kotlinx.serialization.internal.** { *; }
-keep class * implements kotlinx.serialization.KSerializer {
    <init>(...);
}
-keepclassmembers class * implements kotlinx.serialization.KSerializer {
    static ** INSTANCE;
}
-dontwarn kotlinx.serialization.**

# Preserve ThreadReader model classes and polymorphic envelopes
-keep class com.redder.model.** { *; }

# ------------------------------------------------------------------------------
# 3. AndroidX Media3 / ExoPlayer
# ------------------------------------------------------------------------------
# Preserve Media3 core, session, exoplayer, and UI reflection targets
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-keep class androidx.media3.extractor.** {
    <init>(...);
}
-dontwarn androidx.media3.**

# ------------------------------------------------------------------------------
# 4. OkHttp & Okio
# ------------------------------------------------------------------------------
# Preserve OkHttp public APIs, reflection, and platform-specific implementations
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# ------------------------------------------------------------------------------
# 5. Kotlin Coroutines
# ------------------------------------------------------------------------------
# Preserve coroutine internals accessed via reflection or atomic field updaters
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.android.** { *; }
-dontwarn kotlinx.coroutines.**
