plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get()))
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    implementation(libs.kotlin.stdlib)
    api(libs.kotlinx.coroutines.core)

    // NOTE (Stage 2E): MLC-LLM ships NO Maven Central / GitHub-release .aar for
    // Android (verified 2026-08-09). The runtime (ai.mlc.mlcllm.* + libtvm4j_runtime_packed.so)
    // is generated per-app by upstream `mlc_llm package` (dist/lib/mlc4j) and must be
    // added at the app level; this module binds it reflectively via MlcRuntimeBridge
    // and fail-closes when absent — no dependency to declare here, no silent stub.

    // Engine SPI (CORE-ENGINE / ENGINE-STANDARD)
    api(project(":engines:api"))

    // Canonical types, contracts, errors, resource vectors, identity, state ids
    api(project(":core:canonical"))
    api(project(":core:contracts"))
    api(project(":core:errors"))
    api(project(":core:resource"))
    api(project(":core:identity"))
    api(project(":core:state"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}

// Package UPSTREAM.lock + capability matrix next to classes for registration helpers.
tasks.named<ProcessResources>("processResources") {
    from(projectDir) {
        include("UPSTREAM.lock")
        include("capability-matrix.yaml")
        into("com/omnillm/engines/mlcllm")
    }
}
