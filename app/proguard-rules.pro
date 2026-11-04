# Room entities / DAOs are generated and kept by Room's own rules.
# Media3 ships consumer rules. Keep enum names used for persisted settings.
-keepclassmembers enum app.vesper.player.** { *; }
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
