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
    testImplementation(libs.junit)
}
