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

    // Canonical catalogs, errors, FSM definitions (no Android).
    implementation(project(":core:canonical"))
    implementation(project(":core:contracts"))
    implementation(project(":core:errors"))
    implementation(project(":core:state"))

    // Admin transport facade + job records (Feature Pack composes platform; no DB writers).
    implementation(project(":interfaces:admin"))
    implementation(project(":runtime:job-manager"))
    implementation(project(":runtime:policy"))
    implementation(project(":runtime:request-registry"))

    // Claim ledger in-memory fixtures used by AdminModule.createService in tests.
    testImplementation(project(":data:persistence"))
    testImplementation(libs.junit)
}
