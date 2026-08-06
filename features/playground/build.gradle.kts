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

    // Control-plane ports are feature-internal; do not api()-export orchestrator
    // (which re-exports :data:persistence / :engines:api) into app-ui (INV-001).
    implementation(project(":runtime:orchestrator"))
    api(project(":runtime:observability"))
    implementation(project(":runtime:request-registry"))

    // LOCAL_UI principal for app-internal playground (INV-001 / INV-011).
    api(project(":interfaces:admin"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}
