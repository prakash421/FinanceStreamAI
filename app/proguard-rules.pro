# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ==========================================
# Google Sign-In via Credential Manager
# ==========================================
# Credential Manager + the googleid library reflectively look up classes by
# name; without these keeps, R8 strips them in release builds and sign-in
# silently fails on the Play Store APK while still working in debug.
-keep class androidx.credentials.** { *; }
-keep class androidx.credentials.playservices.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-dontwarn androidx.credentials.**
-dontwarn com.google.android.libraries.identity.googleid.**

# ==========================================
# Retrofit / Gson / OkHttp keep rules
# ==========================================
# Retrofit uses reflection on suspend-typed methods; Gson uses reflection on
# our @SerializedName-annotated data classes. Both need keeps in release.
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep class com.google.gson.** { *; }
-keep class com.example.financestreamai.** { *; }
-keepclassmembers class * { @com.google.gson.annotations.SerializedName <fields>; }
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**