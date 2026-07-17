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
