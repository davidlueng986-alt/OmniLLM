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

    // Canonical catalogs + contracts (no invented types).
    api(project(":core:canonical"))
    api(project(":core:contracts"))
    api(project(":core:errors"))
    api(project(":core:identity"))
    api(project(":core:resource"))
    api(project(":core:state"))

    // Wire only through Orchestrator / Job / Model ports (AGENTS.md Feature Pack).
    // Keep Orchestrator / Job types on the Feature API surface (plan/submit claims).
    // Do NOT re-export engines SPI or model-store writers as api — :android:app-ui
    // depends on this Feature Pack and must not receive those on its compile
    // classpath (INV-001 soft/hard boundary; prefer Admin projections only).
    api(project(":runtime:orchestrator"))
    api(project(":runtime:job-manager"))
    implementation(project(":runtime:model-manager"))
    implementation(project(":runtime:policy"))
    implementation(project(":engines:api"))
    // Shared offline fixture catalog identities (FEAT-MODELHUB software E2E).
    implementation(project(":features:modelhub"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}
