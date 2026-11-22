# Add project specific ProGuard rules here.

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keepclassmembers @kotlinx.serialization.Serializable class * {
    <fields>;
}
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer $serializer;
}
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# Keep enum constants for serialization and name-based lookups (e.g., LayerType in OverlayStagePass)
-keepclassmembers enum * {
    *;
}

