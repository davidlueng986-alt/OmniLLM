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

    // Canonical catalogs / IDs / results (no invented enums).
    api(project(":core:canonical"))
    api(project(":core:contracts"))
    api(project(":core:errors"))
    api(project(":core:state"))

    // Wire through Job + Observability ports only (ADR-010 / AGENTS.md Feature Pack).
    // job-manager stays implementation unless public API re-exports Job types.
    implementation(project(":runtime:job-manager"))
    api(project(":runtime:observability"))

    // LOCAL_UI principal + access-control shapes for UI-facing API.
    api(project(":interfaces:admin"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}
