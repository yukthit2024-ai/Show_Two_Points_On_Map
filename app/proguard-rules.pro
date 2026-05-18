# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# MapLibre SDK rules
-keep class org.maplibre.** { *; }
-keep interface org.maplibre.** { *; }
-dontwarn org.maplibre.**

# OkHttp rules
-keepattributes Signature, InnerClasses, AnnotationDefault
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# Gson rules
-keep class com.google.gson.** { *; }
-keepattributes EnclosingMethod
# Keep all model classes for Gson serialization
-keep class com.vypeensoft.friendtracker.model.** { *; }

# Preserve line numbers for debugging
-keepattributes SourceFile,LineNumberTable
