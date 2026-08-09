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
    implementation(project(":core:canonical"))
    implementation(project(":core:contracts"))
    implementation(project(":core:errors"))
    implementation(project(":core:state"))
    // Persistence ports (AccessTokenStore / PairingChallengeStore /
    // RevocationEpochStore / EncryptedKeyBlobStore + records) live in
    // :core:ports (ARC-01): policy consumes ports, :data:persistence
    // implements them — no reverse data -> policy edge.
    implementation(project(":core:ports"))
    testImplementation(libs.junit)
}
