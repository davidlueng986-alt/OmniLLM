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

    // Canonical catalogs / contracts / errors / state (no invented enums).
    api(project(":core:canonical"))
    api(project(":core:contracts"))
    api(project(":core:errors"))
    api(project(":core:state"))

    // LOCAL_UI principal for UI-facing API (INV-001).
    api(project(":interfaces:admin"))

    // HTTP path constants / transport kinds for policy alignment (ADR-011).
    api(project(":interfaces:http"))

    // Settings / ACL / revocation epoch via ports only (ADR-010).
    api(project(":runtime:policy"))
    api(project(":runtime:observability"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}
