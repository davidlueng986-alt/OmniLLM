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

    api(project(":core:canonical"))
    api(project(":core:contracts"))
    api(project(":core:errors"))
    api(project(":core:identity"))
    api(project(":core:resource"))
    api(project(":core:state"))

    // Engine planLoad/commitLoad SPI (no native adapters).
    api(project(":engines:api"))
    // Quarantine / atomic promote / ready FD ports.
    api(project(":data:model-store"))
    // SQLDelight installation / revision-lease ledger ports (ADR-010 sole writer).
    api(project(":data:persistence"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    // JDBC SQLite for durable install state-machine tests.
    testImplementation(libs.sqldelight.sqlite.driver)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}
