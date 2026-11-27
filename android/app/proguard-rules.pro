# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.skillions.deskpilot.** {
    *** Companion;
}
-keepclasseswithmembers class com.skillions.deskpilot.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.**
