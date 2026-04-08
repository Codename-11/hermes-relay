# ── kotlinx.serialization ────────────────────────────────────────────
# Keep @Serializable classes and their generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep all @Serializable data classes in our package
-keep,includedescriptorclasses class com.hermesandroid.relay.**$$serializer { *; }
-keepclassmembers class com.hermesandroid.relay.** {
    *** Companion;
}
-keepclasseswithmembers class com.hermesandroid.relay.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── OkHttp ───────────────────────────────────────────────────────────
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── OkHttp-SSE ──────────────────────────────────────────────────────
-keep class okhttp3.sse.** { *; }
-keep interface okhttp3.sse.** { *; }
-keep class okhttp3.internal.sse.** { *; }

# ── Mikepenz Markdown Renderer ────────────────────────────────────
-keep class com.mikepenz.markdown.** { *; }
-keep interface com.mikepenz.markdown.** { *; }
-keep class org.intellij.markdown.** { *; }
-keep interface org.intellij.markdown.** { *; }

# ── Compose ──────────────────────────────────────────────────────────
# Compose is mostly handled by R8 automatically, but keep stability annotations
-dontwarn androidx.compose.**

# ── EncryptedSharedPreferences / Tink ────────────────────────────────
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ── ML Kit Barcode Scanning ──────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-dontwarn com.google.mlkit.**

# ── CameraX ─────────────────────────────────────────────────────────
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ── General ──────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
