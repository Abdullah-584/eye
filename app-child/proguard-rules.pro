# Keep rules for FamilyGuard.
#
# R8 is disabled for release in M1 (isMinifyEnabled = false); these rules are
# here so enabling minification later does not silently strip anything.
#
# supabase-kt / kotlinx.serialization: keep serializers for the models we
# decode reflectively through Postgrest.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.familyguard.core.domain.** {
    *** Companion;
}
-keepclasseswithmembers class com.familyguard.core.domain.** {
    kotlinx.serialization.KSerializer serializer(...);
}
