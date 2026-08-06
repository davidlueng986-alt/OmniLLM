plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Ktor loopback (CIO) + LAN TLS (Netty SSL) gateways.
    api(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(project(":core:canonical"))
    implementation(project(":core:contracts"))
    implementation(project(":core:errors"))
    // JsonBodyAdmission uses JsonParseLimits / JsonTreeBudget (SEC-INPUT).
    implementation(project(":runtime:policy"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.ktor.server.cio)
    testImplementation("io.ktor:ktor-server-test-host:${libs.versions.ktor.get()}")
    testImplementation("io.ktor:ktor-client-content-negotiation:${libs.versions.ktor.get()}")
}
