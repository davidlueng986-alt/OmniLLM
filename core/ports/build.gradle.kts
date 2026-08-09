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

/**
 * :core:ports — portable persistence / durability ports shared by the data
 * layer and the runtime control plane (ARC-01 / ARC-02).
 *
 * Dependency direction (AGENTS.md): core <- data <- runtime. Store interfaces
 * and their pure record types live here so that:
 *   - :data:persistence implements them without depending on :runtime:policy
 *     (removes the old reverse data -> runtime edge, ARC-01);
 *   - :runtime:policy consumes them as constructor ports (policy stays free of
 *     SQLDelight / Android, ARC-01);
 *   - Feature Packs and transport facades (:features:tools,
 *     :features:ai-content-report, :interfaces:admin) consume them without
 *     compiling against :data:* writers (ARC-02 / INV-001).
 *
 * No Android SDK, no SQL, no I/O side effects — pure Kotlin ports only.
 */
dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(project(":core:state"))
    testImplementation(libs.junit)
}
