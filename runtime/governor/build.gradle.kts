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
    // ResourceVector, Reservation, AllocationHandle, Conservation (CORE-RESOURCE / ADR-003).
    api(project(":core:resource"))
    // OmniResult / catalog types (transitive via resource, declared for clarity).
    api(project(":core:canonical"))
    // RESERVATION / ALLOCATION FSM definitions from specs/state-machines.yaml
    // (ARC-03: governor transitions run through the generated StateMachines).
    api(project(":core:state"))
    testImplementation(libs.junit)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}
