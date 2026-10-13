import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

// Version shared with the Windows exe; tools/release.ps1 passes -PappVersion=X.Y.Z.
val appVersion = (findProperty("appVersion") as String?) ?: "0.1.0"
val appVersionCode = appVersion.split('.').map { it.toInt() }.let { (ma, mi, pa) -> ma * 10000 + mi * 100 + pa }

// Release signing (F5): create keystore.properties in the project root (it is git-ignored):
//   storeFile=release.jks
//   storePassword=...
//   keyAlias=usbip
//   keyPassword=...
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.jslsystems.usbip.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jslsystems.usbip"
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":usbip-core"))
}
