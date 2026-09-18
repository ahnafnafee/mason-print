# Release minification. Everything kotlinx.serialization and OkHttp need is already in the
# consumer rules those artifacts ship, so this file is only the things they cannot know about.

# The app's own types are the JSON contract with a server we do not control. R8 renaming a
# property would silently change the wire format, and `-keepclassmembers` alone is not enough
# because the models are read by kotlinx-serialization's *plugin-generated* serializer, which
# refers to the fields by name.
-keepclassmembers class dev.ahnafnafee.masonprint.data.model.** {
    <fields>;
    <init>(...);
}

# Enums decoded from server strings: valueOf/name must survive.
-keepclassmembers enum dev.ahnafnafee.masonprint.data.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# OkHttp 5's platform selection reflects on optional providers that are absent on most devices.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ML Kit barcode scanning (unbundled via play-services-mlkit-barcode-scanning). The GMS artifact
# ships its own consumer rules and the model lives in Play Services, so the bundled-proto keeps this
# file used to carry are gone. Keep the public vision API subtree defensively so R8 full mode cannot
# fold a field the scanner reads first.
-keep class com.google.mlkit.vision.barcode.** { *; }
-keep class com.google.mlkit.vision.common.** { *; }
-keep class com.google.mlkit.common.** { *; }

# Tink (pulled in by androidx.security:security-crypto) is compiled against annotation jars it
# never needs at runtime. R8 fails the build on absent classes unless told they are annotations.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.j2objc.annotations.**

