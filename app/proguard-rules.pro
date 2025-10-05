# ================================================================================================
# De1984 - ProGuard Configuration
# ================================================================================================
# Purpose: Enable minification (~30-40% APK reduction) while preserving all functionality
# F-Droid Compliant: Yes (strips analytics, telemetry, debug logging)
# Covers: Hilt DI, Room, Compose, Kotlin, OkHttp, Coroutines
# Last Updated: 2025-10-05
# ================================================================================================

# ================================================================================================
# KOTLIN LANGUAGE FEATURES
# ================================================================================================

# Keep Kotlin metadata for reflection
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations

# Keep Kotlin data classes (used in StateFlow with Compose)
-keepclassmembers class io.github.dorumrr.de1984.** {
    public <init>(...);
}

# Data class copy() and componentN() methods (required by Compose state)
-keep class io.github.dorumrr.de1984.**.*UiState {
    public ** copy(...);
    public ** component*();
}

# Keep sealed classes and their subclasses (Kotlin reflection)
-keep class io.github.dorumrr.de1984.**.*State { *; }
-keep class io.github.dorumrr.de1984.**.*State$* { *; }
-keep class io.github.dorumrr.de1984.**.*Result { *; }
-keep class io.github.dorumrr.de1984.**.*Result$* { *; }
-keep class io.github.dorumrr.de1984.**.*Error { *; }
-keep class io.github.dorumrr.de1984.**.*Error$* { *; }

# Keep enums (values() and valueOf() used throughout app)
-keepclassmembers enum io.github.dorumrr.de1984.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] $VALUES;
    public *;
}

# ================================================================================================
# HILT DEPENDENCY INJECTION
# ================================================================================================

# Keep Hilt generated classes (critical for DI)
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep classes with @Inject constructors
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
}

# Keep Hilt ViewModels and their factories
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}

# Keep @HiltViewModel annotated classes
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * {
    <init>(...);
}

# Keep Hilt generated modules
-keep class **_HiltModules { *; }
-keep class **_HiltModules$* { *; }
-keep class **_Factory { *; }
-keep class **_MembersInjector { *; }
-keep class **Hilt* { *; }

# Keep Hilt entry points
-keep @dagger.hilt.InstallIn class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * {
    <init>(...);
    public <fields>;
    public <methods>;
}

# ================================================================================================
# ROOM DATABASE
# ================================================================================================

# Keep Room database classes
-keep class * extends androidx.room.RoomDatabase {
    <init>(...);
}

# Keep Room entities
-keep @androidx.room.Entity class * {
    *;
}

# Keep Room DAOs (interfaces and implementations)
-keep @androidx.room.Dao interface * {
    *;
}
-keep class androidx.room.** { *; }
-keep interface androidx.room.** { *; }

# Keep Room generated _Impl classes
-keep class **_Impl { *; }

# Keep Room TypeConverters
-keepclassmembers class * {
    @androidx.room.TypeConverter <methods>;
}

# Remove Room error messages with tracking URLs (F-Droid compliance)
-assumenosideeffects class androidx.room.** {
    *** *Error*(...);
    *** *Exception*(...);
}

# Don't warn about Room paging (not used)
-dontwarn androidx.room.paging.**

# ================================================================================================
# JETPACK COMPOSE
# ================================================================================================

# Keep Composable functions
-keep @androidx.compose.runtime.Composable class * { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Keep Compose runtime classes
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }

# Keep Compose compiler classes
-keep class androidx.compose.compiler.** { *; }

# Keep Compose navigation
-keep class androidx.navigation.** { *; }

# Keep lambda classes used in Compose
-keepclassmembers class androidx.compose.** {
    <methods>;
}

# ================================================================================================
# ANDROID COMPONENTS
# ================================================================================================

# Keep Activities
-keep public class * extends android.app.Activity {
    <init>(...);
}

# Keep Services
-keep public class * extends android.app.Service {
    <init>(...);
}

# Keep BroadcastReceivers
-keep public class * extends android.content.BroadcastReceiver {
    <init>(...);
    public void onReceive(android.content.Context, android.content.Intent);
}

# Keep ContentProviders
-keep public class * extends android.content.ContentProvider {
    <init>(...);
}

# Keep Application class
-keep public class * extends android.app.Application {
    <init>(...);
    public void onCreate();
}

# ================================================================================================
# KOTLIN COROUTINES
# ================================================================================================

# Keep coroutine internal classes
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep coroutine continuation classes
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep Flow classes
-keep class kotlinx.coroutines.flow.** { *; }

# Don't warn about coroutines debug mode
-dontwarn kotlinx.coroutines.debug.**

# ================================================================================================
# OKHTTP (FOR UPDATE CHECKER)
# ================================================================================================

# Keep OkHttp classes
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Keep Okio
-keep class okio.** { *; }
-keepnames class okio.**

# Platform-specific implementations (SSL/TLS)
-keep class okhttp3.internal.platform.** { *; }
-keepclassmembers class okhttp3.internal.platform.Platform {
    public <methods>;
}

# Don't warn about OkHttp internal
-dontwarn okhttp3.internal.**
-dontwarn okio.**

# ================================================================================================
# ANDROID ARCHITECTURE COMPONENTS
# ================================================================================================

# Keep Lifecycle classes
-keep class androidx.lifecycle.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Keep StateFlow and SharedFlow
-keep class kotlinx.coroutines.flow.StateFlow { *; }
-keep class kotlinx.coroutines.flow.SharedFlow { *; }
-keep class kotlinx.coroutines.flow.MutableStateFlow { *; }
-keep class kotlinx.coroutines.flow.MutableSharedFlow { *; }

# ================================================================================================
# ANDROID SYSTEM CLASSES
# ================================================================================================

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep Serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# ================================================================================================
# APP-SPECIFIC MODELS AND ENTITIES
# ================================================================================================

# Keep domain models (may be serialized or used with reflection)
-keep class io.github.dorumrr.de1984.domain.model.** { *; }

# Keep database entities
-keep class io.github.dorumrr.de1984.data.database.entity.** { *; }

# Keep data models
-keep class io.github.dorumrr.de1984.data.model.** { *; }

# Keep use cases (injected via Hilt)
-keep class io.github.dorumrr.de1984.domain.usecase.** { *; }

# Keep repositories (injected via Hilt)
-keep class io.github.dorumrr.de1984.domain.repository.** { *; }
-keep class io.github.dorumrr.de1984.data.repository.** { *; }

# Keep data sources
-keep class io.github.dorumrr.de1984.data.datasource.** { *; }

# Keep services
-keep class io.github.dorumrr.de1984.data.service.** { *; }

# Keep receivers
-keep class io.github.dorumrr.de1984.data.receiver.** { *; }

# Keep monitors
-keep class io.github.dorumrr.de1984.data.monitor.** { *; }

# Keep common utilities that use reflection
-keep class io.github.dorumrr.de1984.data.common.** { *; }

# ================================================================================================
# REMOVE TRACKING AND ANALYTICS (F-DROID COMPLIANCE)
# ================================================================================================

# Remove Google Analytics
-assumenosideeffects class com.google.android.gms.analytics.** { *; }
-assumenosideeffects class com.google.analytics.** { *; }
-assumenosideeffects class com.google.tagmanager.** { *; }

# Remove Firebase
-assumenosideeffects class com.google.firebase.analytics.** { *; }
-assumenosideeffects class com.google.firebase.crashlytics.** { *; }
-assumenosideeffects class com.google.firebase.perf.** { *; }

# Remove general tracking (be specific to avoid matching Object methods)
-assumenosideeffects class * {
    *** *analytics*(...);
    *** *telemetry*(...);
    *** *metrics*(...);
    *** *tracking*(...);
}

# ================================================================================================
# REMOVE LOGGING (RELEASE BUILDS)
# ================================================================================================

# Remove Android logging (keeps 'e' for crash reports)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}

# Remove printStackTrace (sensitive stack trace leaks)
-assumenosideeffects class * extends java.lang.Throwable {
    public void printStackTrace();
}

# ================================================================================================
# DEBUGGING ATTRIBUTES
# ================================================================================================

# Keep line numbers for stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep annotations
-keepattributes *Annotation*

# Keep generic signatures (for reflection)
-keepattributes Signature

# Keep inner classes
-keepattributes InnerClasses

# Keep enclosing method
-keepattributes EnclosingMethod

# ================================================================================================
# OPTIMIZATION SETTINGS
# ================================================================================================

# Optimization passes (balanced)
-optimizationpasses 5

# Safe optimizations
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# Don't preverify (not needed for Android)
-dontpreverify

# Allow access modification for optimization
-allowaccessmodification

# ================================================================================================
# WARNING SUPPRESSION (ONLY FOR KNOWN SAFE WARNINGS)
# ================================================================================================

# Suppress warnings for missing classes we don't use
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**

# ================================================================================================
# VERIFICATION
# ================================================================================================
# To verify this configuration:
# 1. ./gradlew clean assembleRelease
# 2. Check build/outputs/mapping/release/usage.txt for removed code
# 3. Install APK and test all features
# 4. Check Logcat for any ClassNotFoundException or NoSuchMethodError
# ================================================================================================
