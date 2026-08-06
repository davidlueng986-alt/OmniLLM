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
    // Opaque IDs, OmniResult, ResourceEnvelope — generated canonical types only.
    api(project(":core:canonical"))
    // ReservationId / AllocationHandleId / OperatingConstraint (ADR-003).
    api(project(":core:resource"))
    testImplementation(libs.junit)
    // OmniErrorCode for commit-binding reconcile fixtures (RR-007).
    testImplementation(project(":core:errors"))
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}
