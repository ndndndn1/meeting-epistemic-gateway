import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val versions =
    Properties().apply { rootProject.file("toolchain.properties").inputStream().use(::load) }

android {
    namespace = "io.github.ndndndn1.meg"
    compileSdk = versions.getProperty("android.compileSdk").toInt()
    buildToolsVersion = versions.getProperty("android.buildTools")
    ndkVersion = "30.0.15729638"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    defaultConfig {
        applicationId = "io.github.ndndndn1.meg"
        minSdk = 34
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("distribution") {
            storeFile = file(System.getenv("MEG_KEYSTORE") ?: "/signing/release.jks")
            storePassword = System.getenv("MEG_STORE_PASSWORD")
            keyAlias = "meg"
            keyPassword = System.getenv("MEG_STORE_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") {
            if (System.getenv("MEG_SIGN_DEBUG") == "1")
                signingConfig = signingConfigs.getByName("distribution")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("distribution")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("test").resources.srcDir("../../contracts")
        getByName("main").assets.srcDir("../../contracts")
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    implementation(
        platform("androidx.compose:compose-bom:${versions.getProperty("compose.bom.version")}")
    )
    implementation(
        "androidx.activity:activity-compose:${versions.getProperty("activity.compose.version")}"
    )
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
