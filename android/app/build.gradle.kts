plugins {
    id("com.android.application")
}

val projectRoot = rootProject.projectDir.parentFile
val rexSdkDir = file("${projectRoot.absolutePath}/rexglue-sdk")
val rexPortDir = file("${projectRoot.absolutePath}/rexlego")
val hostSmoke = project.hasProperty("hostSmoke")

android {
    namespace = "com.ylports.dimensions"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.ylports.dimensionsrecomp"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-bootstrap"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DREX_PROJECT_ROOT=${projectRoot.absolutePath}",
                    "-DREXSDK_DIR=${rexSdkDir.absolutePath}",
                    "-DREX_PORT_DIR=${rexPortDir.absolutePath}"
                )
                if (hostSmoke) {
                    arguments += "-DREX_ANDROID_HOST_SMOKE=ON"
                }
                cppFlags += listOf("-std=c++23")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.1"
        }
    }

    sourceSets {
        getByName("main") {
            // Use the SDL3 Android Java bridge from the SDK submodule so it
            // stays in lock-step with the SDL3 native sources ReXGlue builds.
            java.srcDir(
                file("${rexSdkDir.absolutePath}/thirdparty/sdl3/android-project/app/src/main/java")
            )
        }
    }

    packaging {
        jniLibs {
            // ReXGlue loads the Xenos backend with dlopen from nativeLibraryDir.
            useLegacyPackaging = true
        }
    }

    buildTypes {
        debug {
            isJniDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
