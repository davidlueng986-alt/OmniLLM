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

    // LOCAL_UI principal + HTTP path constants for SDK sample fixtures.
    api(project(":interfaces:admin"))
    api(project(":interfaces:http"))

    // Orchestrator is feature-internal wiring; demote from api so app-ui does not
    // compile against :data:persistence / :engines:api via api() (INV-001).
    implementation(project(":runtime:orchestrator"))
    api(project(":runtime:observability"))
    api(project(":runtime:policy"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}
