plugins {
    alias(libs.plugins.android.library)
}

// BLD-02: auditable dev-mode override. Per-variant default: debug=true, release=false.
// CI/release pipelines must NOT pass this property; it exists for developer auditing.
val omnillmDevShipModeOverride: Boolean? =
    providers.gradleProperty("omnillm.developmentShipMode").orNull?.toBooleanStrictOrNull()

android {
    namespace = "com.omnillm.android.runtimeservice"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        // BLD-02: per-buildType OMNILLM_DEV_SHIP_MODE field consumed by
        // RuntimeControlPlane when constructing ProductBuildMode.
        buildConfig = true
    }

    buildTypes {
        debug {
            // Debug/dev builds keep development ship mode ON (product needs it to
            // be shippable in dev); explicit -Pomnillm.developmentShipMode overrides.
            buildConfigField(
                "boolean",
                "OMNILLM_DEV_SHIP_MODE",
                (omnillmDevShipModeOverride ?: true).toString(),
            )
        }
        release {
            // Fail-closed: release is OFF unless an explicit override says otherwise.
            buildConfigField(
                "boolean",
                "OMNILLM_DEV_SHIP_MODE",
                (omnillmDevShipModeOverride ?: false).toString(),
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // ControlPlaneHttpHandler setting/JSON helpers (shared with :interfaces:http).
    implementation(libs.kotlinx.serialization.json)

    // Binder projections (IOmniRuntime / IOmniAdmin / IOmniBinding).
    api(project(":interfaces:aidl"))
    // Pure Admin API (LOCAL_UI) — AIDL facade projects into this.
    implementation(project(":interfaces:admin"))
    // Loopback HTTP gateway / OpenAPI projection (CORE-INTERFACE, FEAT-SERVER).
    implementation(project(":interfaces:http"))

    // RUNTIME FSM + fail-closed error catalog (no invented states/codes).
    implementation(project(":core:state"))
    implementation(project(":core:errors"))
    implementation(project(":core:canonical"))
    implementation(project(":core:contracts"))
    implementation(project(":core:identity"))

    // ADR-010 single-writer marker + claim ledgers (control plane sole writer).
    implementation(project(":data:persistence"))
    // Shared ledger/security port types (ARC-01/02): SingleWriterPolicy,
    // ControlPlaneWriter, IdempotentCommandClaimRow, store interfaces.
    implementation(project(":core:ports"))
    // Android SQLite driver for ControlPlaneDatabase (SQLDelight; opened only in :runtime).
    implementation(libs.sqldelight.android.driver)
    // Control-plane storage layout + quarantine (ANDROID-STORAGE).
    implementation(project(":data:model-store"))

    // Claim-or-return + jobs + policy + orchestrator (control-plane host; not opened from UI).
    implementation(project(":runtime:request-registry"))
    implementation(project(":runtime:job-manager"))
    implementation(project(":runtime:policy"))
    implementation(project(":runtime:orchestrator"))
    // Session delivery semantics (INV-006 / ADR-006) — control-plane only.
    implementation(project(":runtime:session"))
    implementation(project(":runtime:observability"))
    implementation(project(":runtime:model-manager"))
    implementation(project(":runtime:governor"))

    // Worker / parser process modules for manifest merge when app depends on runtime.
    // Companion is a separate applicationId APK (ADR-007) — never api()-merged here.
    api(project(":android:workers"))
    api(project(":android:parser-isolated"))

    // TrustPlacementPolicy for companion placement gate (SEC-PLACEMENT / ADR-007).
    implementation(project(":engines:api"))
    // Engine Packs attached to EngineRegistry on READY/DEGRADED (INV-001).
    // Only llama-cpp may load real native (JniNativeBackend); peers stay stub/UNKNOWN.
    // See EngineSelectionPolicy + docs/architecture/engine-registry-attachment.md.
    implementation(project(":engines:llama-cpp"))
    implementation(project(":engines:litert-lm"))
    implementation(project(":engines:mlc-llm"))
    implementation(project(":engines:mllm"))
    implementation(project(":engines:ort-genai"))
    // Packages libomnillm_llama.so into the runtime process (not loaded by app-ui).
    implementation(project(":android:native"))

    // Wave-A Feature Packs (admin, auto-setup, modelhub, playground, server, dashboard).
    implementation(project(":features:admin"))
    implementation(project(":features:auto-setup"))
    implementation(project(":features:modelhub"))
    implementation(project(":features:playground"))
    implementation(project(":features:server"))
    implementation(project(":features:dashboard"))

    // Wave-B Feature Packs hosted only on the control plane (ADR-010 / INV-001).
    implementation(project(":features:ai-content-report"))
    implementation(project(":features:lan"))
    implementation(project(":features:benchmark"))
    implementation(project(":features:diagnostics"))
    implementation(project(":features:routing"))
    implementation(project(":features:tools"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    // TST-04/COR-23c: real Ktor CIO bind in GatewayLifecycle tests (the engine
    // itself is an implementation dep of :interfaces:http — test-only here so
    // the bound-port + TCP-reachability contract runs against a REAL server).
    testImplementation(libs.ktor.server.cio)

    // C-07 host attach-test seams: the official LiteRT-LM JVM artifact and the
    // ONNX-Runtime-GenAI classes.jar (extracted from the pinned AAR, sha256 in
    // UPSTREAM.lock) put the real SDK/API surfaces on the host test classpath so
    // EnginePackAttachment attach tests exercise the REAL backend path (never
    // on Android packaging — host test only).
    testImplementation(libs.litertlm.jvm)
    testImplementation(files("../../engines/ort-genai/libs/onnxruntime-genai-android-0.14.0.jar"))

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
