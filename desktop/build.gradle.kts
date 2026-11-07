import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

val appVersion = (System.getenv("FLOYA_VERSION") ?: "1.0.0")

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.json:json:20240303")
    implementation("net.jthink:jaudiotagger:3.0.1")
}

kotlin {
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "app.floya.desktop.MainKt"
        jvmArgs += listOf("-Xmx512m", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Floya Music"
            packageVersion = appVersion
            description = "Floya Music — музыкальный плеер"
            vendor = "Floya"
            // ffmpeg.exe and yt-dlp.exe are put here by the build (see .github/workflows/desktop.yml).
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appResources"))
            modules("java.sql", "java.naming", "jdk.unsupported", "java.net.http", "jdk.crypto.ec")
            windows {
                iconFile.set(project.file("icon.ico"))
                menuGroup = "Floya"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "5c3f6a2e-9b1d-4f7e-a8c2-3d4e5f60718a"
            }
        }
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}
