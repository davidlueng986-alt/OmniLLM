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

    api(project(":core:canonical"))
    api(project(":core:contracts"))
    api(project(":core:errors"))
    api(project(":core:resource"))
    api(project(":core:state"))
    api(project(":core:identity"))

    // Claim-or-return ledger (ADR-004/005).
    // implementation (not api): :runtime:request-registry api()-exports
    // :data:persistence. Re-exporting it would put DB writer types on the
    // compile classpath of Feature Packs pulled into :android:app-ui
    // (INV-001 / ADR-010). Control-plane hosts that construct Orchestrator
    // with RequestRegistry must depend on :runtime:request-registry directly.
    implementation(project(":runtime:request-registry"))
    // Multi-dimensional admission (CORE-RESOURCE / ADR-003).
    api(project(":runtime:governor"))
    // Engine plan/commit SPI — no native adapters.
    api(project(":engines:api"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}
