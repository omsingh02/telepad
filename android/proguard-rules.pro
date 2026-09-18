# Telepad ProGuard rules.

# Keep noise-java classes (reflection-free crypto library).
-keep class com.southernstorm.noise.** { *; }
-dontwarn com.southernstorm.noise.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.omsingh.telepad.**$$serializer { *; }
-keepclassmembers class com.omsingh.telepad.** {
    *** Companion;
}
-keepclasseswithmembers class com.omsingh.telepad.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep our sealed input model — matched on by transport layer.
-keep class com.omsingh.telepad.core.input.InputEvent { *; }
-keep class com.omsingh.telepad.core.input.InputEvent$* { *; }
-keep class com.omsingh.telepad.core.input.ConnectionState { *; }
-keep class com.omsingh.telepad.core.input.ConnectionState$* { *; }
-keep class com.omsingh.telepad.core.input.ConnectionTarget { *; }
-keep class com.omsingh.telepad.core.input.ConnectionTarget$* { *; }

# Keep BluetoothHidDevice callbacks (binder-invoked).
-keepclassmembers class * extends android.bluetooth.BluetoothHidDevice$Callback {
    *;
}

# Required for EncryptedSharedPreferences master key.
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**
