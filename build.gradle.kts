plugins {
    // AGP 9.x provides built-in Kotlin for Android modules — do not apply kotlin-android.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Root aggregate: resolve the project graph without packaging an APK.
tasks.register("omnillmModules") {
    group = "help"
    description = "List OmniLLM monorepo modules"
    doLast {
        rootProject.subprojects.sortedBy { it.path }.forEach { println(it.path) }
    }
}

// ---------------------------------------------------------------------------
// Formal contract codegen (specs/ → core/canonical|state|errors generated/)
// Authority: docs/30-core-platform/formal-contract-artifacts.md
//
// CI note: checkContractDrift must fail when committed generated Kotlin drifts
// from specs catalogs. Do not run generateContracts immediately before the
// drift gate in the same job if you need to detect stale committed sources —
// regenerate in a separate step / commit, then let checkContractDrift verify.
// ---------------------------------------------------------------------------

fun pythonExecutable(): String {
    val fromProp = findProperty("omnillm.python") as String?
    if (!fromProp.isNullOrBlank()) return fromProp
    return "python"
}

val generateContracts by tasks.registering(Exec::class) {
    group = "omnillm"
    description =
        "Generate Kotlin contract sources from specs/*.yaml into core/canonical|state|errors"
    workingDir = rootDir
    commandLine(
        pythonExecutable(),
        "tools/codegen/generate_contracts.py",
        "--repo-root",
        rootDir.absolutePath,
    )
    inputs.files(
        fileTree("specs") {
            include(
                "canonical-types.yaml",
                "state-machines.yaml",
                "error-catalog.yaml",
                "access-control-catalog.yaml",
                "capability-catalog.yaml",
            )
        },
        file("tools/codegen/generate_contracts.py"),
    )
    outputs.dir("core/canonical/src/main/kotlin/com/omnillm/core/canonical/generated")
    outputs.dir("core/state/src/main/kotlin/com/omnillm/core/state/generated")
    outputs.dir("core/errors/src/main/kotlin/com/omnillm/core/errors/generated")
}

val checkContractDrift by tasks.registering(Exec::class) {
    group = "verification"
    description =
        "Fail the build when committed generated contracts drift from specs catalogs"
    workingDir = rootDir
    commandLine(
        pythonExecutable(),
        "tools/codegen/generate_contracts.py",
        "--repo-root",
        rootDir.absolutePath,
        "--check",
    )
    // Inputs are catalogs + generator only. Committed generated sources are
    // read at execution time by the Python --check mode (avoids a task-output
    // cycle with generateContracts, which writes the same directories).
    inputs.files(
        fileTree("specs") {
            include(
                "canonical-types.yaml",
                "state-machines.yaml",
                "error-catalog.yaml",
                "access-control-catalog.yaml",
                "capability-catalog.yaml",
            )
        },
        file("tools/codegen/generate_contracts.py"),
    )
    // Independent of generateContracts: never dependsOn(generateContracts).
    mustRunAfter(generateContracts)
}

// ---------------------------------------------------------------------------
// AIDL drift gate (API-20): specs/aidl/omnillm-aidl.yaml → interfaces/aidl
// ---------------------------------------------------------------------------
val checkAidlDrift by tasks.registering(Exec::class) {
    group = "verification"
    description =
        "Fail the build when committed .aidl files drift from specs/aidl/omnillm-aidl.yaml"
    workingDir = rootDir
    commandLine(
        pythonExecutable(),
        "tools/codegen/extract_aidl.py",
        "--repo-root",
        rootDir.absolutePath,
        "--check",
    )
    // Inputs are the AIDL catalog + generator only. Committed .aidl files are
    // read at execution time by the Python --check mode.
    inputs.files(
        file("specs/aidl/omnillm-aidl.yaml"),
        file("tools/codegen/extract_aidl.py"),
    )
    // Independent of generateContracts: never dependsOn(generateContracts).
    mustRunAfter(generateContracts)
}

// Alias: :tools:codegen equivalent at root (no tools Gradle project module).
tasks.register("toolsCodegen") {
    group = "omnillm"
    description = "Alias for generateContracts (tools/codegen)"
    dependsOn(generateContracts)
}

// ---------------------------------------------------------------------------
// llama.cpp stripped-packaged digest gate (BLD-D2 / ENGINE-LLAMACPP §4)
// Fail closed: the release APK's stripped libomnillm_llama.so per ABI must
// match engines/llama-cpp/UPSTREAM.lock artifactDigest (variant
// "stripped-packaged"). Also re-asserts the D1 build-info pin (upstream
// commit embedded). APK source: app-ui release APK, or the AGP
// stripped_native_libs intermediates when no APK is assembled yet.
// ---------------------------------------------------------------------------
val checkLlamaArtifactDigest by tasks.registering(Exec::class) {
    group = "verification"
    description =
        "Fail when stripped-packaged libomnillm_llama.so digests drift from engines/llama-cpp/UPSTREAM.lock (D1/D2)"
    workingDir = rootDir
    commandLine(
        pythonExecutable(),
        "tools/ci/verify_llama_digest.py",
        "--repo-root",
        rootDir.absolutePath,
    )
    inputs.file("tools/ci/verify_llama_digest.py")
    inputs.file("engines/llama-cpp/UPSTREAM.lock")
    // Not hard-depended on :android:app-ui:assembleRelease: the gate must be
    // able to fail fast when the APK is absent (missing artifact is a failure).
}

// ---------------------------------------------------------------------------
// Native 16 KB packaging gates (ANDROID-NATIVE / ANDROID-16KB)
// Fail closed: no .so at all is a build break (missing packaged natives), and
// misaligned .so also fail (see tools/ci/check_elf_16kb_alignment.py).
// ---------------------------------------------------------------------------
val checkNative16kb by tasks.registering(Exec::class) {
    group = "verification"
    description =
        "Scan monorepo *.so for ELF 16 KB LOAD alignment (ANDROID-NATIVE; fails when no .so found)"
    workingDir = rootDir
    commandLine(
        pythonExecutable(),
        "tools/ci/check_elf_16kb_alignment.py",
        "--min-align",
        "16384",
    )
    inputs.file("tools/ci/check_elf_16kb_alignment.py")
}

// INV-001 / ADR-010: forbidden direct module edges (UI↛engines/data, engines↛persistence).
val checkDependencyEdges by tasks.registering(Exec::class) {
    group = "verification"
    description =
        "Fail on forbidden Gradle module edges (INV-001 UI isolation / engines→data)"
    workingDir = rootDir
    commandLine(
        pythonExecutable(),
        "tools/ci/check_dependency_edges.py",
    )
    inputs.file("tools/ci/check_dependency_edges.py")
    // Explicit module build files only — do not fileTree(rootDir) (Gradle 9
    // validation conflicts with generateContracts / SQLDelight outputs).
    inputs.files(provider {
        files(
            rootProject.buildFile,
            *rootProject.subprojects.map { it.buildFile }.filter { it.exists() }.toTypedArray(),
        )
    })
}

tasks.register("check") {
    group = "verification"
    description =
        "Root verification (contract drift + AIDL drift + native 16 KB + packaged .so + dep edges)"
    dependsOn(checkContractDrift)
    dependsOn(checkAidlDrift)
    dependsOn(checkNative16kb)
    dependsOn(checkDependencyEdges)
    // BLD-13: aggregate the native packaging proof (libomnillm_llama.so for
    // arm64-v8a + x86_64) so root `check` fails closed when natives are missing.
    dependsOn(":android:native:verifyNativeLibsPresent")
    // BLD-D2: stripped-packaged llama.cpp digest lock gate.
    dependsOn(checkLlamaArtifactDigest)
}

// ---------------------------------------------------------------------------
// Unit / contract tests (fail closed)
//
//   ./gradlew test              → JVM + Android unit tests (host JVM)
//   ./gradlew jvmTest           → pure kotlin.jvm modules only
//   ./gradlew androidUnitTest   → AGP testDebugUnitTest across android modules
//   ./gradlew :core:state:test  → single module
//
// Android instrumentation / connected tests are NOT included; use
//   ./gradlew connectedDebugAndroidTest
// when device/emulator coverage is required.
// ---------------------------------------------------------------------------
val jvmTest by tasks.registering {
    group = "verification"
    description =
        "Run unit tests for all org.jetbrains.kotlin.jvm modules (contract + pure domain)"
}

val androidUnitTest by tasks.registering {
    group = "verification"
    description =
        "Run host unit tests (testDebugUnitTest) for all Android library/application modules"
}

// Root `test` aggregates JVM + Android host unit tests so CI cannot skip INV/security
// suites that live under :android:* (e.g. AIDL principal, companion isolation).
tasks.register("test") {
    group = "verification"
    description =
        "Fail-closed unit tests: all Kotlin JVM modules + Android testDebugUnitTest"
    dependsOn(jvmTest)
    dependsOn(androidUnitTest)
}

// ---------------------------------------------------------------------------
// Module dependency boundary gate (AGENTS.md / INV-001 / ADR-010 / ADR-007)
// ---------------------------------------------------------------------------
val checkModuleDependencyRules by tasks.registering(Exec::class) {
    group = "verification"
    description =
        "Fail closed when Gradle module edges violate UI/engine/single-writer rules"
    workingDir = rootDir
    commandLine(
        pythonExecutable(),
        "tools/ci/check_module_dependency_rules.py",
        "--repo-root",
        rootDir.absolutePath,
    )
    inputs.file("tools/ci/check_module_dependency_rules.py")
    // Explicit module build files only — broad fileTree(rootDir) makes Gradle 9
    // treat the monorepo root as an input and conflict with generateContracts /
    // SQLDelight generate tasks (implicit dependency validation failure).
    inputs.files(provider {
        files(
            rootProject.buildFile,
            *rootProject.subprojects.map { it.buildFile }.filter { it.exists() }.toTypedArray(),
        )
    })
}

tasks.named("check").configure {
    dependsOn(checkModuleDependencyRules)
}

// JVM modules: include drift gate in their check lifecycle; wire into jvmTest.
// Android modules: wire testDebugUnitTest into androidUnitTest + root test.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.matching { it.name == "check" }.configureEach {
            dependsOn(checkContractDrift)
        }
        plugins.withId("java") {
            // Standard Gradle `test` task on each JVM module.
            jvmTest.configure {
                dependsOn(tasks.named("test"))
            }
        }
        // Fallback if java plugin task name differs after configuration.
        afterEvaluate {
            tasks.findByName("test")?.let { t ->
                jvmTest.configure { dependsOn(t) }
            }
        }
    }

    // AGP library / application host unit tests (not instrumentation).
    listOf("com.android.library", "com.android.application").forEach { pluginId ->
        plugins.withId(pluginId) {
            afterEvaluate {
                tasks.findByName("testDebugUnitTest")?.let { t ->
                    androidUnitTest.configure { dependsOn(t) }
                }
            }
        }
    }
}
