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

    // Canonical catalogs / IDs / results (no invented enums).
    api(project(":core:canonical"))
    api(project(":core:contracts"))
    api(project(":core:errors"))
    api(project(":core:identity"))
    api(project(":core:state"))

    // JobProgress appears on public ModelHub API projections — keep api for that type only.
    // model-manager + model-store stay implementation so UI classpath does not
    // re-export engines:api / data:model-store via api() (INV-001 module edges).
    api(project(":runtime:job-manager"))
    implementation(project(":runtime:model-manager"))

    // Quarantine key / declared file types used when advancing acquisition.
    implementation(project(":data:model-store"))

    // HTTPS download URL policy (SEC-INPUT §3 / SEC-SUPPLY §5).
    implementation(project(":runtime:policy"))

    // Admin command / LOCAL_UI principal shapes for UI-facing API.
    api(project(":interfaces:admin"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.named("compileKotlin").configure {
    dependsOn(rootProject.tasks.named("generateContracts"))
}
