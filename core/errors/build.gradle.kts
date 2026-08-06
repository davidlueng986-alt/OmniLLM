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
    testImplementation(libs.junit)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}
