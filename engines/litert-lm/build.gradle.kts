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

    // Engine SPI (CORE-ENGINE / ENGINE-STANDARD)
    api(project(":engines:api"))

    // Canonical types, contracts, errors, resource vectors, identity, state ids
    api(project(":core:canonical"))
    api(project(":core:contracts"))
    api(project(":core:errors"))
    api(project(":core:resource"))
    api(project(":core:identity"))
    api(project(":core:state"))

    // Official LiteRT-LM Kotlin API surface — pinned 0.15.0 (UPSTREAM.lock; NEVER drift
    // to "latest.release" for qualification). The JVM artifact exposes the same
    // com.google.ai.edge.litertlm.* Engine/EngineConfig/Conversation/Backend surface as
    // the Android AAR (litertlm-android), with no android.* types — it is the compile-time
    // type surface for this JVM engine module and is NOT packaged for Android.
    //
    // Packaging split (INV-001): the Android runtime process (runtime-service/companion)
    // must package `com.google.ai.edge.litertlm:litertlm-android:0.15.0` (native libs
    // inside the AAR) instead of this JVM jar. Host JVM probes may use litertlm-jvm.
    // OfficialLitertLmSdkBridge fails closed (NOT_AVAILABLE) when the SDK is absent.
    // Version pinned via gradle/libs.versions.toml `litertlm` (R10 consolidation).
    compileOnly(libs.litertlm.jvm)

    // Real SDK classes on the host test classpath: mapping tests construct genuine
    // EngineConfig/Backend/Contents/Message objects (no native calls — Engine/Conversation
    // are never instantiated in host unit tests).
    testImplementation(libs.litertlm.jvm)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}

// litertlm-jvm 0.15.0 ships Java 21 bytecode (class file major 65) — host unit tests
// must run on a JVM ≥ 21 while the module's compile toolchain stays at the product
// default (libs.versions.jdk). Android packaging is unaffected (D8 consumes bytecode).
tasks.named<Test>("test") {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

// Package UPSTREAM.lock + capability matrix next to classes for registration helpers.
tasks.named<ProcessResources>("processResources") {
    from(projectDir) {
        include("UPSTREAM.lock")
        include("capability-matrix.yaml")
        into("com/omnillm/engines/litertlm")
    }
}
