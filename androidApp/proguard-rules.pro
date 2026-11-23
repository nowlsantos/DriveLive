# ==============================================================================
# DriveLive (com.nowlsantos.drivelive) - R8 / ProGuard Release Rules
# ==============================================================================

# Keep line numbers and source file names for readable crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ------------------------------------------------------------------------------
# 1. DriveLive Network Models & Core State
# ------------------------------------------------------------------------------
-keep class com.nowlsantos.drivelive.network.** { *; }
-keep class com.nowlsantos.drivelive.state.** { *; }
-keep class com.nowlsantos.drivelive.agent.** { *; }

# ------------------------------------------------------------------------------
# 2. Kotlinx Serialization (Gemini Live WebSocket JSON Frames & Tool Payloads)
# ------------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt

# Keep generated $serializer classes and Companion serializer() accessors
-keep,includedescriptorclasses class com.nowlsantos.drivelive.**$$serializer { *; }
-keepclassmembers class com.nowlsantos.drivelive.** {
    *** Companion;
}
-keepclassmembers class com.nowlsantos.drivelive.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# Preserve kotlinx.serialization runtime descriptors and polymorphic lookup
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class <1> {
    @kotlinx.serialization.SerialName <fields>;
}

# ------------------------------------------------------------------------------
# 3. Ktor Client & OkHttp WebSocket Engine
# ------------------------------------------------------------------------------
-keep class io.ktor.** { *; }
-keep class kotlinx.atomicfu.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# OkHttp & Okio (consumer rules are automatically bundled by OkHttp; suppress optional warnings)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# ------------------------------------------------------------------------------
# 4. Koin Dependency Injection & Lifecycle ViewModels
# ------------------------------------------------------------------------------
-keep class com.nowlsantos.drivelive.di.** { *; }
-keep class com.nowlsantos.drivelive.presentation.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ------------------------------------------------------------------------------
# 5. Kotlin Coroutines
# ------------------------------------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ------------------------------------------------------------------------------
# 6. CameraX & Android Platform Bridges
# ------------------------------------------------------------------------------
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
-keep class com.nowlsantos.drivelive.platform.** { *; }
