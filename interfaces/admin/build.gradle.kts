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

    // Canonical catalogs + opaque IDs (no Android).
    implementation(project(":core:canonical"))
    implementation(project(":core:contracts"))
    implementation(project(":core:errors"))
    implementation(project(":core:state"))

    // Control-plane surfaces only (ADR-010 single writer lives in runtime host).
    implementation(project(":runtime:request-registry"))
    implementation(project(":runtime:job-manager"))
    implementation(project(":runtime:policy"))
    implementation(project(":data:persistence"))

    testImplementation(libs.junit)
}
