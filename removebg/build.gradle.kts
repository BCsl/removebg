import org.gradle.api.tasks.bundling.Zip

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
}

val libraryVersion = "1.0.4"
val aarBaseName = "removebg-sdk"

android {
    namespace = "dev.eren.removebg"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        ndkVersion = "28.2.13676358"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.mlkit.subject.segmentation)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

tasks.matching { it.name == "bundleReleaseAar" }.configureEach {
    (this as Zip).archiveFileName.set("$aarBaseName-$libraryVersion.aar")
}
