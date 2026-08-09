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
    // Opaque IDs / digests / OmniResult.
    implementation(project(":core:canonical"))
    implementation(project(":core:contracts"))
    implementation(project(":core:errors"))
    // REQUEST / COMMAND FSM terminal sets (StateMachines).
    implementation(project(":core:state"))
    // Claim/commit ledger types appear in public registry APIs — api, not implementation.
    api(project(":data:persistence"))
    // Shared ledger port rows (CommandLedgerStates / IdempotentCommandClaimRow /
    // SingleWriterPolicy) live in :core:ports (ARC-02).
    implementation(project(":core:ports"))

    testImplementation(libs.junit)
}
