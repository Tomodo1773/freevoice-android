import java.util.Properties

plugins {
    id("com.android.application")
}

val localSigningPropertiesFile = rootProject.file(".signing/signing.properties")
val localSigningProperties = Properties().apply {
    if (localSigningPropertiesFile.isFile) {
        localSigningPropertiesFile.inputStream().use(::load)
    }
}

val ciSigningStoreFile = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val ciSigningStorePassword = providers.environmentVariable("ANDROID_STORE_PASSWORD").orNull
val ciSigningKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val ciSigningKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val hasCiSigning = listOf(
    ciSigningStoreFile,
    ciSigningStorePassword,
    ciSigningKeyAlias,
    ciSigningKeyPassword,
).all { !it.isNullOrBlank() }

val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orElse("1")
val releaseVersionName = providers.gradleProperty("releaseVersionName").orElse("0.1.0")

android {
    namespace = "com.tomodo.freevoice"
    compileSdk = 36

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
            )
        }
    }

    defaultConfig {
        applicationId = "com.tomodo.freevoice"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode.get().toInt()
        versionName = releaseVersionName.get()

        // Speech SDK はネイティブライブラリを ABI ごとに同梱する。実機とエミュレータの
        // ぶんだけ残し、APK が不要に膨らまないようにする。
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    if (hasCiSigning || localSigningPropertiesFile.isFile) {
        signingConfigs {
            create("localRelease") {
                if (hasCiSigning) {
                    storeFile = rootProject.file(checkNotNull(ciSigningStoreFile))
                    storePassword = checkNotNull(ciSigningStorePassword)
                    keyAlias = checkNotNull(ciSigningKeyAlias)
                    keyPassword = checkNotNull(ciSigningKeyPassword)
                } else {
                    storeFile = localSigningPropertiesFile.parentFile.resolve(localSigningProperties.required("storeFile"))
                    storePassword = localSigningProperties.required("storePassword")
                    keyAlias = localSigningProperties.required("keyAlias")
                    keyPassword = localSigningProperties.required("keyPassword")
                }
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
            if (hasCiSigning || localSigningPropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("localRelease")
            }
        }
    }

    // Kotlin の jvmTarget は AGP 内蔵 Kotlin が targetCompatibility に合わせる。
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

dependencies {
    // Azure Speech の連続認識（WebSocket）。REST と違い発話中に認識が進む。
    implementation("com.microsoft.cognitiveservices.speech:client-sdk:1.51.2")
    // Gemini Live API 用。Android SDK に WebSocket クライアントがない。
    // 5.5.0 以降は compileSdk 37 を要求するので、36 で通る最後の版に留める。
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar の org.json はスタブなので、JVM テストでは実装を差す。
    testImplementation("org.json:json:20260814")
}
