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

    // Job lifecycle for CONTENT_REPORT recoverability (ADR-010 / FEAT map).
    implementation(project(":runtime:job-manager"))
    api(project(":runtime:observability"))

    // LOCAL_UI principal + access-control shapes for trusted local review.
    api(project(":interfaces:admin"))

    // Durable ContentReport ledger (ADR-010). implementation — do not api()-reexport
    // SQLite writers into app-ui (INV-001). Production opens via control plane only.
    implementation(project(":data:persistence"))

    // Secret Broker REPORT_QUEUE_ENCRYPTION for draft/queue payload seal (FEAT-AI-CONTENT-REPORT §6).
    implementation(project(":runtime:policy"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}
