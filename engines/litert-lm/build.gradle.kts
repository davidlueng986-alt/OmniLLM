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

    // Optional official LiteRT-LM AAR (ENGINE-LITERT §2).
    // Default: OFF — host CI/unit tests must not require Google Maven artifacts.
    // Human pin: set -Pomnillm.litertlm.sdkVersion=<version> (and lock digests in UPSTREAM.lock).
    // Never use "latest.release" for qualification. RealSdkBackend still fails closed until
    // lock is complete + exploratory policy allows execute; AAR presence ≠ SUPPORTED.
    val litertSdkVersion = (findProperty("omnillm.litertlm.sdkVersion") as String?)?.trim().orEmpty()
    if (litertSdkVersion.isNotEmpty()) {
        // compileOnly: adapter compiles against types only when human enables the property.
        // Runtime packaging of the AAR belongs to :android:runtime-service / companion — not UI.
        compileOnly("com.google.ai.edge.litertlm:litertlm-android:$litertSdkVersion")
    }

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
        into("com/omnillm/engines/litertlm")
    }
}
