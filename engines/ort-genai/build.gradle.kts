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

    // ONNX Runtime GenAI (ENGINE-ORTGENAI §2 / §9) — INLINE PIN, do not move to
    // libs.versions.toml: upstream publishes NO Maven Central artifact (verified
    // 2026-08-09: `onnxruntime-genai-android` is a GitHub release asset, and the
    // JVM Java API "package publication is pending"). The Java API
    // (ai.onnxruntime.genai.*) is taken from the official AAR's classes.jar into
    // libs/ (sha256 recorded in UPSTREAM.lock; extract: unzip onnxruntime-genai-
    // android-0.14.0.aar classes.jar).
    // compileOnly: API compile against real classes only — never packaged here.
    // Runtime packaging (genai AAR + base onnxruntime-android AAR jniLibs) belongs
    // to the consuming Android module (:android:runtime-service) — Stage 5.
    compileOnly(files("libs/onnxruntime-genai-android-0.14.0.jar"))
    // Test classpath keeps the classes so native-availability detection and the
    // fail-closed paths are exercised for real on host JVM (AAR .so are Android
    // ELF; GenAI.init() throws LinkageError without them — no fake availability).
    testImplementation(files("libs/onnxruntime-genai-android-0.14.0.jar"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}

// Package UPSTREAM.lock + capability matrix next to classes for registration helpers.
tasks.named<ProcessResources>("processResources") {
    from(projectDir) {
        include("UPSTREAM.lock")
        include("capability-matrix.yaml")
        into("com/omnillm/engines/ortgenai")
    }
}
