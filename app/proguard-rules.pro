# kotlinx.serialization: keep generated serializers and the Companion.serializer() lookups for the app's own models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dk.azp.jellybook.**$$serializer { *; }
-keepclassmembers class dk.azp.jellybook.** {
    *** Companion;
}
-keepclasseswithmembers class dk.azp.jellybook.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp, Coil and Media3 ship their own consumer rules.
