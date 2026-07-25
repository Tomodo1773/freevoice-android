import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localSigningPropertiesFile = rootProject.file(".signing/signing.properties")
val localSigningProperties = Properties().apply {
    if (localSigningPropertiesFile.isFile) {
        localSigningPropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "com.tomodo.freevoice"
    compileSdk = 36

    buildFeatures {
        viewBinding = true
    }

    defaultConfig {
        applicationId = "com.tomodo.freevoice"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    if (localSigningPropertiesFile.isFile) {
        signingConfigs {
            create("localRelease") {
                storeFile = localSigningPropertiesFile.parentFile.resolve(localSigningProperties.required("storeFile"))
                storePassword = localSigningProperties.required("storePassword")
                keyAlias = localSigningProperties.required("keyAlias")
                keyPassword = localSigningProperties.required("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (localSigningPropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("localRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

private fun Properties.required(key: String): String =
    getProperty(key)?.takeIf(String::isNotBlank) ?: error(".signing/signing.properties の $key が未設定です")

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar の org.json はスタブなので、JVM テストでは実装を差す。
    testImplementation("org.json:json:20250107")
}
