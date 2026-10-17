# ── Debug info ────────────────────────────────────────────────────────────────
# Keep line numbers in stack traces for easier crash debugging.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Retrofit ──────────────────────────────────────────────────────────────────
# Retrofit uses reflection to create interface implementations at runtime.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, Exceptions, *Annotation*, RuntimeVisibleAnnotations
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
# Keep all Retrofit service interfaces and API classes defined in the app.
-keep interface com.dimapp.android.homeywatch.api.** { *; }
-keep class com.dimapp.android.homeywatch.api.** { *; }

# ── Gson / JSON models ────────────────────────────────────────────────────────
# Gson uses reflection to read/write field names on data classes.
# Without these rules R8 renames fields and JSON deserialization silently fails or crashes.
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.** { *; }
-keep class com.google.gson.stream.** { *; }

# Retain generic signatures of TypeToken and its subclasses.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Keep all class members annotated with @SerializedName
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep all DTO, storage, and model packages in their entirety
-keep class com.dimapp.android.homeywatch.api.models.** { *; }
-keep class com.dimapp.android.homeywatch.repository.models.** { *; }
-keep class com.dimapp.android.homeywatch.storage.** { *; }

# ── OkHttp & Okio ─────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ── Kotlin Coroutines & Reflection ─────────────────────────────────────────────
-keep,allowobfuscation,allowshrinking interface kotlin.coroutines.Continuation
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-dontwarn kotlin.reflect.jvm.internal.**

# ── Coil image loading ────────────────────────────────────────────────────────
-keep class coil.** { *; }
-keep class io.coil-kt.** { *; }
-dontwarn coil.**
-dontwarn io.coil-kt.**

# ── ZXing QR Generator ────────────────────────────────────────────────────────
-keep class com.google.zxing.** { *; }

# ── Wear OS Tiles & ProtoLayout ───────────────────────────────────────────────
-keep class androidx.wear.tiles.** { *; }
-keep class androidx.wear.protolayout.** { *; }
