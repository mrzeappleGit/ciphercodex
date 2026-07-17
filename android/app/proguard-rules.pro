# kotlinx.serialization — keep generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class tech.mrzeapple.ciphercodex.**$$serializer { *; }
-keepclassmembers class tech.mrzeapple.ciphercodex.** { *** Companion; }
-keepclasseswithmembers class tech.mrzeapple.ciphercodex.** { kotlinx.serialization.KSerializer serializer(...); }

# Onyx Pen SDK — raw input dispatches through JNI/reflection into SDK classes
-keep class com.onyx.** { *; }
-dontwarn com.onyx.**

# HiddenApiBypass resolves its own members via MethodHandles — names must survive
-keep class org.lsposed.hiddenapibypass.** { *; }

# Suppress warnings for optional/indirect transitive dependencies
-dontwarn org.joda.convert.**
-dontwarn org.slf4j.impl.**
