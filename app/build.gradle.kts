import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Ключ релизной подписи — в local.properties (не в git): release.storeFile/storePassword/keyAlias/keyPassword.
val localProps = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

android {
    namespace = "ru.kost.ruvoice"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.kost.ruvoice"
        minSdk = 26
        targetSdk = 35
        versionCode = 1201
        versionName = "0.12.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        localProps.getProperty("release.storeFile")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = localProps.getProperty("release.storePassword")
                keyAlias = localProps.getProperty("release.keyAlias")
                keyPassword = localProps.getProperty("release.keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        // debug тем же ключом: ставится поверх релиза с телефона без удаления настроек и словарей
        debug { signingConfigs.findByName("release")?.let { signingConfig = it } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("ptl", "pte", "json", "bin") }
    // libfbjni/libc++_shared есть и в pytorch_android_lite, и в executorch; из lite годятся обоим
    packaging { jniLibs.useLegacyPackaging = false; jniLibs.pickFirsts += listOf("lib/*/libfbjni.so", "lib/*/libc++_shared.so") }
}

dependencies {
    implementation("org.pytorch:pytorch_android_lite:2.1.0")
    // ExecuTorch 1.5.0 + XNNPACK для бэкбона вокодера (backbone.pte): в Maven Central только 0.6.0. AAR собран
    // tools/build_executorch_aar.sh — готовый с ossci-android.s3.amazonaws.com падает SIGILL на ARMv8.0 (см. скрипт).
    implementation(files("libs/executorch-1.5.0-xnnpack.aar"))
    implementation("com.facebook.soloader:soloader:0.10.5")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
