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
    // OmniResult references sealed OmniError from the error catalog.
    api(project(":core:errors"))
    testImplementation(libs.junit)
}

// generateContracts writes into src/main/kotlin/.../generated (Gradle 9 task validation).
tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}
