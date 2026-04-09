@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    `maven-publish`
}

android {
    namespace = "dev.eren.removebg"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++11 -frtti -fexceptions"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared"
                )
            }
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.runtime)

    // PyTorch (不支持 16KB page size)
    implementation(libs.pytorch.android.lite)
    implementation(libs.pytorch.android.torchvision.lite)

    // ONNX Runtime (支持 16KB page size)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}


publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate {
                from(components["release"])
            }

            groupId = "dev.eren"
            artifactId = "removebg"
            version = "1.0.4"
        }
    }
}