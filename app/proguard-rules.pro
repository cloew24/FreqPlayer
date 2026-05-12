# =============================================================================
# Frequency Shift — R8 / ProGuard rules
# =============================================================================
# These rules are surgical: each block keeps only what's actually needed for
# reflection or runtime class-loading by the libraries we use. Aggressive
# `-keep class foo.** { *; }` is avoided because it disables shrinking and
# obfuscation for entire packages, defeating most of R8's value.
#
# When in doubt about whether a rule is still needed, run a release build
# (`./gradlew assembleRelease`) and check the produced `mapping.txt` and
# `usage.txt` to confirm the rule actually keeps a referenced class.

# ---- Kotlin metadata --------------------------------------------------------
# Compose, kotlinx-coroutines, and kotlin-reflect all use Kotlin metadata at
# runtime to resolve sealed classes, default arguments, and inline functions.
# Stripping it usually breaks Compose's @Composable boundaries.
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# ---- Coroutines -------------------------------------------------------------
# kotlinx-coroutines uses ServiceLoader for its main-dispatcher; the loader
# reflects on this class name. Without this entry, coroutines throw
# `IllegalStateException: Module with the Main dispatcher is missing` on
# release builds.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ---- Compose ----------------------------------------------------------------
# Compose's runtime tooling (LiveLiterals, source info) uses these annotations
# to map back to source. Removing them is fine for production but breaks
# Layout Inspector — we keep them since the binary cost is negligible.
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class **$Companion {
    public static final ** INSTANCE;
}

# ---- Media3 / ExoPlayer -----------------------------------------------------
# Media3 uses reflection in three specific places:
#
# 1. Renderer construction inside DefaultRenderersFactory — when extension
#    renderers (FFmpeg, Opus, FLAC) are present in the classpath, they're
#    discovered by classname. We don't ship those extensions, but Media3's
#    bundled EXTENSION_RENDERER_MODE_ON path still tries to load them.
#
# 2. MediaSession token serialization across processes — fields of the
#    SessionToken inner classes are read via reflection by the system.
#
# 3. Custom command parsing in the system Notification — the action's
#    Intent extras are decoded via reflective field lookup.
#
# These three rules cover all three paths without unlocking the whole package.
-dontwarn androidx.media3.exoplayer.ext.**
-keep class androidx.media3.exoplayer.ext.** { *; }
-keep class androidx.media3.session.SessionToken** { *; }
-keep class androidx.media3.session.MediaSession** { *; }
# DefaultRenderersFactory subclass: our DeepBufferRenderersFactory overrides
# `buildAudioSink`. Keep its constructor visible so reflective loaders that
# discover RenderersFactory implementations don't strip it.
-keep class com.aelant.freqshift.DeepBufferRenderersFactory {
    public <init>(android.content.Context);
    protected ** buildAudioSink(...);
}

# ---- DataStore --------------------------------------------------------------
# DataStore's preferences serializer uses reflection to construct
# Preferences instances. Without these the first read after a release build
# throws ClassNotFoundException.
-keep class androidx.datastore.preferences.protobuf.** { *; }
-keepclassmembers class androidx.datastore.preferences.protobuf.** {
    *;
}

# ---- Coil -------------------------------------------------------------------
# Coil 2.x uses ServiceLoader for image decoders. Our custom Track fetcher
# is referenced from FreqShiftApp.newImageLoader() and won't be stripped, but
# Coil's built-in components are loaded by name.
-keep class coil.** { *; }
-keep class coil.compose.** { *; }
-keepnames class okhttp3.OkHttpClient
-dontwarn coil.**

# ---- Compose / Material3 icons ---------------------------------------------
# Material icons are referenced as `Icons.Default.<name>` properties — code
# generation produces fields lazily. R8 normally strips unused icons, but
# the field-resolution pattern can confuse it on release builds. We keep the
# specific icons we use.
-keep class androidx.compose.material.icons.filled.** { *; }

# ---- Application class ------------------------------------------------------
# AndroidManifest references `.FreqShiftApp` by name. The `Application`
# subclass and its `ImageLoaderFactory` interface implementation must
# survive shrinking.
-keep class com.aelant.freqshift.FreqShiftApp { *; }

# ---- Project model classes --------------------------------------------------
# Track is used as a Coil model key. Coil compares Track instances via
# `equals`/`hashCode` to look up cached art. R8 may otherwise inline the
# data class away when used only as a generic argument.
-keep class com.aelant.freqshift.Track { *; }
-keep class com.aelant.freqshift.FrequencyPreset { *; }

# ---- General Android --------------------------------------------------------
# Required by Service registration and intent filtering — the manifest
# references PlaybackService by string.
-keep public class * extends android.app.Service
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity

# Keep all enum values() / valueOf() which our DataStore restore uses.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Source file / line numbers in stack traces (helpful even after obfuscation)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
