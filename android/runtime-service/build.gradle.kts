plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.omnillm.android.runtimeservice"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
}
