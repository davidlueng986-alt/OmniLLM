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

    // Canonical catalogs / results / evidence (no invented enums).
    api(project(":core:canonical"))
    api(project(":core:contracts"))
    api(project(":core:errors"))
    api(project(":core:resource"))
    api(project(":core:state"))

    // Health / metrics / traces (CORE-OBSERVABILITY). Feature reads only (INV-001).
    api(project(":runtime:observability"))
    // Resource ledger snapshots (RESOURCE_ACCOUNTING).
    api(project(":runtime:governor"))

    // LOCAL_UI principal / Admin composition surface.
    api(project(":interfaces:admin"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}
