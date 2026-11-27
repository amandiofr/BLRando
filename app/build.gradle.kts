import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release key lives outside the repo; keystore.path (git-ignored) points to its keystore.properties
val keystoreProps = rootProject.file("keystore.path").takeIf { it.exists() }
    ?.let { file(it.readText().trim()) }?.takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    compileSdk = 36
    defaultConfig {
        applicationId = "fr.amandio.blrando"
        minSdk = 30
        // Play Protect blocks sideloaded apps that target an old API level
        targetSdk = 35
        versionCode = 100
        versionName = "1.0.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    lint {
        abortOnError = false
    }
    namespace = "fr.amandio.blrando"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

// The watch interface (../watch) is bundled in the APK as assets/watch/, served to the watch browser by WatchServer
val watchAssetsDir = layout.buildDirectory.dir("generated/watchAssets")
val copyWatchAssets by tasks.registering(Copy::class) {
    from(rootProject.file("watch")) { include("*.html", "*.css", "*.js", "*.png") }
    into(watchAssetsDir.map { it.dir("watch") })
}
android.sourceSets["main"].assets.srcDir(watchAssetsDir)
tasks.named("preBuild") { dependsOn(copyWatchAssets) }

dependencies {
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
