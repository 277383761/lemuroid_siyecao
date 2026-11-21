## Options
-dontoptimize
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*,!code/allocation/variable
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault
-keepattributes SourceFile,LineNumberTable,Signature,JavascriptInterface,Exceptions
-verbose

## Arch Components
-keep class * implements android.arch.lifecycle.GeneratedAdapter {<init>(...);}

## Fabric
-dontnote com.google.android.gms.**
-dontnote com.google.firebase.crash.FirebaseCrash

## Kotlin
-dontwarn kotlin.**
-dontnote kotlin.**
-dontwarn org.jetbrains.annotations.**
-keep class kotlin.Metadata { *; }
-keep class android.arch.lifecycle.**
-dontwarn kotlinx.coroutines.flow.**

## Okio
-dontwarn okio.**

## OkHttp
-dontwarn okhttp3.**
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontnote com.android.org.conscrypt.SSLParametersImpl
-dontnote dalvik.system.CloseGuard
-dontnote sun.security.ssl.SSLContextImpl
-dontnote org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontnote org.conscrypt.ConscryptEngineSocket

## Retrofit
-dontwarn retrofit2.Platform$Java8

## Moshi
-dontnote sun.misc.Unsafe

## Google API
-dontwarn com.google.api.client.json.jackson2.JacksonFactory
-keepclassmembers class * {
  @com.google.api.client.util.Key <fields>;
}

## Guava
-dontnote com.google.appengine.api.ThreadManager
-dontnote com.google.apphosting.api.ApiProxy
-dontwarn java.lang.ClassValue
-dontwarn com.google.j2objc.annotations.Weak
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn org.apache.commons.codec.binary.Base64
-dontwarn org.apache.commons.codec.binary.StringUtils

## Retrograde
-keep class **.model.**
-keepclassmembers class **.model.** {
  <init>(...);
  <fields>;
}

## Misc
-dontwarn com.uber.javaxextras.**
-dontwarn java.lang.management.**
-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn junit.**
-dontwarn com.google.errorprone.**
-dontnote android.net.http.*

## Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.swordfish.lemuroid.**$$serializer { *; }
-keepclassmembers class com.swordfish.lemuroid.** {
    *** Companion;
}
-keepclasseswithmembers class com.swordfish.lemuroid.** {
    kotlinx.serialization.KSerializer serializer(...);
}

## Harmony
# The cached instances are cleared by class/field name through reflection so the long-lived
# game process picks up the values written by the main process. R8 renames the class otherwise,
# the reflection silently fails and the game keeps reading default options.
-keep class com.frybits.harmony.** { *; }

## LibretroDroid
-keep class com.swordfish.libretrodroid.** { *; }

## Media3 / ExoPlayer
# AudioCapabilitiesReceiver.AudioDeviceCallbackV23 extends android.media.AudioDeviceCallback
# which was added in API 23. On Android 5 (API 21) this causes NoClassDefFoundError
# when the class is loaded. Since we only use ExoPlayer for mute game preview videos,
# the audio sink and audio capability listening are not needed. Tell R8 these calls
# have no side effects so it can strip them out.
-dontwarn android.media.AudioDeviceCallback
-dontwarn android.media.AudioDeviceInfo
-assumenosideeffects class androidx.media3.exoplayer.audio.AudioCapabilitiesReceiver {
    public <init>(...);
    public *** register();
    public void unregister();
}
-assumenosideeffects class androidx.media3.exoplayer.audio.DefaultAudioSink {
    *;
}
