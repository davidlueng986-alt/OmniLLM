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

    // Canonical catalogs / contracts / errors (no invented enums).
    api(project(":core:canonical"))
    api(project(":core:contracts"))
    api(project(":core:errors"))
    api(project(":core:identity"))
    api(project(":core:resource"))
    api(project(":core:state"))

    // Wire through Orchestrator ports only (AGENTS.md Feature Pack / ADR-010).
    api(project(":runtime:orchestrator"))
    // engines:api stays implementation (ARC-02): routing internals use
    // PlacementClassLabels but must not re-export the engine SPI to app-ui.
    implementation(project(":engines:api"))

    // LOCAL_UI principal for UI-facing API.
    api(project(":interfaces:admin"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}
