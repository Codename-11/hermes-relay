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
-keep,includedescriptorclasses class com.hermesandroid.companion.**$$serializer { *; }
-keepclassmembers class com.hermesandroid.companion.** {
    *** Companion;
}
-keepclasseswithmembers class com.hermesandroid.companion.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── OkHttp ───────────────────────────────────────────────────────────
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── Compose ──────────────────────────────────────────────────────────
# Compose is mostly handled by R8 automatically, but keep stability annotations
-dontwarn androidx.compose.**

# ── EncryptedSharedPreferences / Tink ────────────────────────────────
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ── General ──────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
