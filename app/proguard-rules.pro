# Room entities / DAOs are generated and kept by Room's own rules.
# Media3 ships consumer rules. Keep enum names used for persisted settings.
-keepclassmembers enum app.vesper.player.** { *; }
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn afu.org.checkerframework.**
-dontwarn javax.lang.model.**
-dontwarn kotlin.annotations.jvm.**
-dontwarn org.jspecify.annotations.**
# youtubedl-android (Jackson mapping of yt-dlp JSON)
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.databind.**
-dontwarn java.beans.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
