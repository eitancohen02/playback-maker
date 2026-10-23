# onnxruntime calls back into its Java classes from native code.
-keep class ai.onnxruntime.** { *; }

# youtubedl-android parses yt-dlp/GitHub JSON with Jackson (reflection on its mapper classes).
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn com.fasterxml.jackson.databind.**
-dontwarn java.beans.**

# youtubedl-android unpacks Python/ffmpeg with commons-compress, whose zip extra-field registry
# instantiates classes by reflection ("class ... is not a concrete class" when shrunk).
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
