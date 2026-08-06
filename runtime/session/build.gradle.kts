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
    // PrefixDecision, digests, OmniResult — catalog types only.
    api(project(":core:canonical"))
    // SESSION FSM + SessionAggregate / OwnerKey / SessionId (DATA-STATES).
    api(project(":core:state"))
    // LoadKey, PrincipalId, SessionHandleId (ADR-002 contracts).
    api(project(":core:contracts"))
    // AllocationHandleId — resident KV/session charge (INV-005).
    api(project(":core:resource"))
    // OmniError / catalog codes for fail-closed results.
    api(project(":core:errors"))
    // Durable SessionLedgerPorts (ADR-010 sole writer; SQLDelight via control plane).
    api(project(":data:persistence"))
    testImplementation(libs.junit)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}
