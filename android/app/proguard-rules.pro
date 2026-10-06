# R8 / ProGuard rules for Telepad release builds.
#
# Most libraries ship their own consumer rules (Compose, AndroidX, kotlinx.serialization,
# Tink behind EncryptedSharedPreferences). Only what they cannot know about is here.

# noise-java is looked up by algorithm name ("Noise_IK_25519_ChaChaPoly_BLAKE2s") through
# factory classes that R8 cannot see being used, so keep the whole library.
-keep class com.southernstorm.noise.** { *; }
-dontwarn com.southernstorm.noise.**

# Tink (used by androidx.security.crypto) references annotation-only classes.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Keep the generated serializers of the app's own @Serializable classes.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep,includedescriptorclasses class com.omsingh.telepad.**$$serializer { *; }
-keepclassmembers class com.omsingh.telepad.** {
    *** Companion;
}
-keepclasseswithmembers class com.omsingh.telepad.** {
    kotlinx.serialization.KSerializer serializer(...);
}
