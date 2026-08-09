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

    // Canonical catalogs / contracts / errors (no invented catalog enums).
    api(project(":core:canonical"))
    api(project(":core:contracts"))
    api(project(":core:errors"))
    api(project(":core:state"))

    // LOCAL_UI principal for app-internal surfaces (INV-001 / INV-011).
    api(project(":interfaces:admin"))

    // Evidence labels / redaction helpers for privacy-safe tool traces.
    api(project(":runtime:observability"))

    // Durable tool proposal ledger (ADR-010). implementation — do not api()-reexport
    // SQLite writers into app-ui (INV-001). Production opens via control plane only.
    // Tool proposal ledger ports live in :core:ports (ARC-02); the SQLite
    // adapter stays control-plane only (ADR-010 / INV-001).
    implementation(project(":core:ports"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}
