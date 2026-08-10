plugins {
    alias(libs.plugins.android.application)
    // AGP 9.x embeds Kotlin — do not apply org.jetbrains.kotlin.android (duplicate kotlin extension).
}

import java.io.FileInputStream
import java.util.Properties

android {
    namespace = "com.omnillm.companion"
    // Same Play target/compile lock as main app (PLAY-TARGET-API-2026). Companion is a
    // separate APK/applicationId (ADR-007) — never merge into com.omnillm AAB.
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    // Local release signing (BLD-07 / RELEASE_CHECKLIST §B): sign release builds
    // with the gitignored root keystore.properties when present — same upload key
    // as the main app (ADR-007 BIND_SANDBOX signature permission). Absent file
    // (CI/PR) leaves the release signingConfig unset => unsigned fallback.
    signingConfigs {
        val ksFile = rootProject.file("keystore.properties")
        if (ksFile.exists()) {
            val props = Properties()
            ksFile.inputStream().use { props.load(it) }
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        // Different package / Linux UID from main app (com.omnillm) — ADR-007 / SEC-EXTERNAL-SANDBOX.
        // No sharedUserId, no shared storage.
        applicationId = "com.omnillm.companion"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // Keep in lockstep with main app version catalog unless protocol forces a
        // companion-only bump (android/companion-sandbox/PACKAGING.md).
        versionCode = libs.versions.companionVersionCode.get().toInt()
        versionName = libs.versions.companionVersionName.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // R8 skeleton present; enable minify only after companion keep-rules smoke.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign with the local upload keystore when keystore.properties exists
            // (same key as main app — ADR-007 BIND_SANDBOX signature permission);
            // otherwise the APK stays unsigned (CI/PR builds never sign locally).
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Optional companion AAB (not required for Play main listing when distributed as APK
    // multi-package / same-signer sideload). Prefer APK for companion unless Play multi-APK
    // packaging policy is re-validated. See PACKAGING.md.
    bundle {
        language { enableSplit = false }
        density { enableSplit = true }
        abi { enableSplit = true }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
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

    // Narrow: canonical digests / results + catalog errors only.
    // No :data:*, no token vault, no control-plane DB, no model-store writers.
    implementation(project(":core:canonical"))
    implementation(project(":core:errors"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
