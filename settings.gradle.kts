pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "omnillm-android"

// ---------------------------------------------------------------------------
// core — pure Kotlin, no Android/OS types
// ---------------------------------------------------------------------------
include(
    ":core:canonical",
    ":core:state",
    ":core:contracts",
    ":core:errors",
    ":core:resource",
    ":core:identity",
)

// ---------------------------------------------------------------------------
// data — persistence + content-addressed model store (control plane only)
// ---------------------------------------------------------------------------
include(
    ":data:persistence",
    ":data:model-store",
)

// ---------------------------------------------------------------------------
// runtime — single-writer control plane
// ---------------------------------------------------------------------------
include(
    ":runtime:request-registry",
    ":runtime:orchestrator",
    ":runtime:governor",
    ":runtime:session",
    ":runtime:model-manager",
    ":runtime:job-manager",
    ":runtime:policy",
    ":runtime:observability",
)

// ---------------------------------------------------------------------------
// engines — Engine Pack adapters (no Orchestrator/transport semantic changes)
// ---------------------------------------------------------------------------
include(
    ":engines:api",
    ":engines:llama-cpp",
    ":engines:litert-lm",
    ":engines:mlc-llm",
    ":engines:mllm",
    ":engines:ort-genai",
)

// ---------------------------------------------------------------------------
// interfaces — HTTP / AIDL / Admin transport adapters
// ---------------------------------------------------------------------------
include(
    ":interfaces:http",
    ":interfaces:aidl",
    ":interfaces:admin",
)

// ---------------------------------------------------------------------------
// features — Feature Packs (capability-scoped; no engine params on all requests)
// ---------------------------------------------------------------------------
include(
    ":features:auto-setup",
    ":features:modelhub",
    ":features:playground",
    ":features:server",
    ":features:lan",
    ":features:dashboard",
    ":features:benchmark",
    ":features:diagnostics",
    ":features:routing",
    ":features:tools",
    ":features:admin",
    ":features:ai-content-report",
)

// ---------------------------------------------------------------------------
// android — process/topology adapters (INV-001: UI never loads native / writes DB)
// ---------------------------------------------------------------------------
include(
    ":android:app-ui",
    ":android:runtime-service",
    ":android:workers",
    ":android:parser-isolated",
    ":android:companion-sandbox",
    ":android:native",
)
