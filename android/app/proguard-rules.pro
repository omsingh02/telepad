# Tink & crypto dependencies
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Noise crypto
-keep class com.southernstorm.noise.** { *; }

# Serialization
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
