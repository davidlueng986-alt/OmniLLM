plugins {
    alias(libs.plugins.android.application)
    // Compose compiler for Kotlin 2.x (AGP 9 embeds Kotlin runtime; compose plugin is separate).
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.omnillm.ui"
    // PLAY-TARGET-API-2026 / ANDROID-BASELINE: Play builds lock compile + target to API 36.
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.omnillm"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = libs.versions.appVersionName.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // BLD-10 ABI strategy: ship arm64-v8a (all production devices) + x86_64
        // (emulator/CI instrumentation). No 32-bit ABIs — native engines target
        // 64-bit only, and omitting armeabi-v7a keeps the merged native payload
        // (llama-cpp, litert, mlc, mllm, ort-genai) minimal for Play.
        // AAB-level abi splits are enabled below (bundle.abi.enableSplit), so
        // each device downloads only its own native library slice.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 skeleton wired for Play release. Keep minify off until keep-rules are
            // validated against full engine native + AIDL surfaces (see proguard-rules.pro
            // and gradle/RELEASE_CHECKLIST.md). Flip to true only after release smoke.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    // Google Play App Bundle packaging (ANDROID-DIST §2).
    // Produce AAB via: ./gradlew :android:app-ui:bundleRelease
    bundle {
        language {
            // Keep all languages in the base module until per-locale DFM is designed.
            enableSplit = false
        }
        density {
            enableSplit = true
        }
        abi {
            // BLD-10: ABI splits in AAB; abiFilters still constrain which ABIs are packaged.
            enableSplit = true
        }
    }

    packaging {
        jniLibs {
            // Uncompressed native libs for 16 KB zip alignment (ANDROID-NATIVE §2).
            // UI process must not load engines (INV-001); packaging still inherits
            // merged libs from dependent modules — keep legacy packaging off.
            useLegacyPackaging = false
        }
        resources {
            // Exclude duplicate META-INF entries from Netty/Ktor multi-JAR merge
            // (mergeReleaseJavaResource fails closed on META-INF/INDEX.LIST, etc.).
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
            )
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Compose (Material3) — primary work areas (UX-IA / UX-ARCH).
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Process topology: :runtime services + control plane (INV-001: UI uses Admin only).
    implementation(project(":android:runtime-service"))
    // AIDL stubs for IOmniAdmin client.
    implementation(project(":interfaces:aidl"))
    // Admin facade models / LOCAL_UI principal (no DB writers from UI).
    implementation(project(":interfaces:admin"))
    implementation(project(":core:canonical"))
    implementation(project(":core:contracts"))
    implementation(project(":core:errors"))

    // Feature Pack view models + UI projections (JVM pure; INV-001 boundary).
    implementation(project(":features:admin"))
    implementation(project(":features:modelhub"))
    implementation(project(":features:playground"))
    implementation(project(":features:server"))
    implementation(project(":features:lan"))
    implementation(project(":features:dashboard"))
    implementation(project(":features:diagnostics"))
    implementation(project(":features:auto-setup"))
    implementation(project(":features:ai-content-report"))
    implementation(project(":features:routing"))
    implementation(project(":features:benchmark"))
    implementation(project(":features:tools"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
