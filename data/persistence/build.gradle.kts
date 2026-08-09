plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.sqldelight)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get()))
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

/**
 * SQLDelight projects the subset of tables needed for control-plane ledgers.
 * Full authority remains specs/database/omnillm-schema.sql (also packaged under
 * src/main/resources/db/). Room entities may be added later for Android runtime;
 * writers remain exclusive to the runtime control plane (ADR-010 / INV-001).
 */
sqldelight {
    databases {
        create("OmniLlmDatabase") {
            packageName.set("com.omnillm.data.persistence")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:${libs.versions.sqldelight.get()}")
            schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
            verifyMigrations.set(false)
        }
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.coroutines)
    // JdbcSqliteDriver for ControlPlaneDatabase.openJdbcFile / openInMemory (tests + non-Android).
    // Android production opens AndroidSqliteDriver in :android:runtime-service and passes SqlDriver.
    implementation(libs.sqldelight.sqlite.driver)
    implementation(project(":core:canonical"))
    implementation(project(":core:identity"))
    implementation(project(":core:state"))
    // Persistence ports (AccessTokenStore / PairingChallengeStore /
    // RevocationEpochStore / EncryptedKeyBlobStore + ledger port rows) live in
    // :core:ports (ARC-01 / ARC-02). Secret-ledger adapters implement them here
    // without a reverse dependency on :runtime:policy.
    implementation(project(":core:ports"))
    testImplementation(libs.junit)
    testImplementation(project(":core:contracts"))
    testImplementation(project(":core:errors"))
    // Integration tests exercise the SQLite adapters against the real policy
    // services (TokenService / PairingChallengeService / vault). Test-only
    // reverse edge — production main sources never depend on :runtime:policy.
    testImplementation(project(":runtime:policy"))
}
