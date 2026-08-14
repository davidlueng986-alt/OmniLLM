plugins {
    alias(libs.plugins.android.application)
    // Compose compiler for Kotlin 2.x (AGP 9 embeds Kotlin runtime; compose plugin is separate).
    alias(libs.plugins.kotlin.compose)
}

import java.io.FileInputStream
import java.util.Properties

android {
    namespace = "com.omnillm.ui"
    // PLAY-TARGET-API-2026 / ANDROID-BASELINE: Play builds lock compile + target to API 36.
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    // Local release signing (BLD-07 / RELEASE_CHECKLIST §B): sign release builds
    // with the gitignored root keystore.properties when present (points to the
    // offline upload keystore — never commit keystores). Absent file (CI/PR)
    // leaves the release signingConfig unset => unsigned fallback.
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
            // Sign with the local upload keystore when keystore.properties exists;
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
                // D10 (GA-GAPS FIX): strip JVM-only runtime garbage pulled in via
                // releaseRuntimeClasspath — jansi 2.4.1 (io.ktor:ktor-server-core-jvm
                // runtime scope; Mac/Windows natives ~343KB, never used on Android) and
                // sqlite-jdbc 3.45.2.0 (app.cash.sqldelight:sqlite-driver ← :data:persistence;
                // 6MB Mac/Windows natives + DriverManager registration — Android production
                // opens AndroidSqliteDriver in :android:runtime-service instead).
                // Class files stay (JdbcSqliteDriver is referenced by :data:persistence main);
                // only the JVM-only payloads are excluded. ~6.4MB APK reduction.
                "org/fusesource/jansi/**",
                "org/sqlite/native/**",
                "sqlite-jdbc.properties",
                "META-INF/native-image/jansi/**",
                "META-INF/native-image/org.xerial/**",
                // AGP's default merge pattern `META-INF/services/**` wins over
                // excludes, so carve the JDBC registration out of the merge set
                // below (otherwise sqlite-jdbc's java.sql.Driver service file
                // survives packaging — verified empirically on AGP 9.3.0).
                "META-INF/services/java.sql.Driver",
            )
            // D10: drop the blanket META-INF/services merge so the java.sql.Driver
            // exclude above actually applies, then re-merge ONLY the service files
            // that are genuinely multi-provider in the release classpath merge
            // (kotlin-reflect BuiltInsLoader helpers + Netty BlockHound hook).
            // Single-provider service files (ktor, coroutines) merge to themselves
            // and are unaffected. Without this carve-out, `META-INF/services/**`
            // (an AGP default) shadows the exclude.
            merges -= setOf("META-INF/services/**", "/META-INF/services/**")
            merges += setOf(
                "META-INF/services/kotlin.reflect.jvm.internal.impl.resolve.ExternalOverridabilityCondition",
                "META-INF/services/reactor.blockhound.integration.BlockHoundIntegration",
            )
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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
    // AndroidJUnitRunner (androidx.test:runner) — required for instrumented smoke
    // tests to start (BLD-11 / launch-readiness fix; CI gate connectedAndroidTest).
    androidTestImplementation(libs.androidx.test.runner)
}
