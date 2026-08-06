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
    // Download URL policy (SEC-INPUT §3 / SEC-SUPPLY §5) at job create.
    // api: JobManager constructor exposes DownloadUrlPolicy.Policy.
    api(project(":runtime:policy"))
    // Durable JobLedgerPorts binding is control-plane only (ADR-010).
    // implementation (not api): Feature Packs re-export JobManager types to UI via
    // api(project(":runtime:job-manager")); persistence must never reach the UI
    // compile classpath (INV-001 / check_module_dependency_rules hard gate).
    // Callers of JobManagerModule.createDurableManager must depend on
    // :data:persistence themselves (e.g. :android:runtime-service).
    implementation(project(":data:persistence"))
    // JSON body for canonical_spec_json / checkpoint_json (no Room entities).
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
