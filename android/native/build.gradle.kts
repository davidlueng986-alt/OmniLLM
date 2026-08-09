plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.omnillm.android.nativelib"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")

        // Production ABI set (ANDROID-NATIVE / AbiPackaging.DEFAULT_ABIS).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // NDK r28+ flexible page sizes (ANDROID-NATIVE §2).
                arguments += listOf(
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DANDROID_STL=c++_shared",
                )
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Uncompressed native libs in APK for 16 KB zip alignment (ANDROID-NATIVE §2).
            useLegacyPackaging = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.androidx.core.ktx)
    // Engine SPI / NativeBackend live in pure JVM module; this module only packages .so.
    // Runtime process loads via engines:llama-cpp JniNativeBackend + this AAR's jni/.
    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// Packaging gates (ANDROID-16KB) — fail closed: missing .so is a build break.
// Wired from root `checkNative16kb` as well (BLD-13).
// ---------------------------------------------------------------------------
tasks.register<Exec>("checkElf16kbAlignment") {
    group = "verification"
    description =
        "Scan packaged/prebuilt .so ELF LOAD segment alignment for 16 KB (ANDROID-NATIVE; fails when no .so found)"
    workingDir = rootProject.projectDir
    val script = rootProject.file("tools/ci/check_elf_16kb_alignment.py")
    val searchRoots = listOf(
        layout.buildDirectory.dir("intermediates").get().asFile,
        file("src/main/jniLibs"),
        file("src/main/cpp"),
    )
    val existing = searchRoots.filter { it.exists() }
    commandLine(
        listOf(
            (project.findProperty("omnillm.python") as String?) ?: "python",
            script.absolutePath,
            "--min-align",
            "16384",
        ) + existing.map { it.absolutePath },
    )
}

tasks.register("checkNativePackaging") {
    group = "verification"
    description = "Native packaging gates for this module (ELF 16 KB when .so present)"
    dependsOn("checkElf16kbAlignment")
}

/**
 * After a successful externalNativeBuild, assert libomnillm_llama.so exists for
 * production ABIs under build intermediates (host proof without device).
 */
tasks.register("assertLlamaNativeSoPackaged") {
    group = "verification"
    description = "Prove libomnillm_llama.so was built for arm64-v8a and x86_64"
    // Depend on common CMake tasks when present (debug library).
    listOf(
        "externalNativeBuildDebug",
        "buildCMakeDebug[arm64-v8a]",
        "buildCMakeDebug[x86_64]",
        "mergeDebugJniLibFolders",
        "copyDebugJniLibsProjectOnly",
    ).forEach { name ->
        tasks.findByName(name)?.let { dependsOn(it) }
    }
    doLast {
        val buildDir = layout.buildDirectory.get().asFile
        val soFiles = buildDir.walkTopDown()
            .filter { it.isFile && it.name == "libomnillm_llama.so" }
            .toList()
        if (soFiles.isEmpty()) {
            throw GradleException(
                "libomnillm_llama.so not found under ${buildDir.absolutePath} — " +
                    "ensure NDK ${libs.versions.ndk.get()} + CMake ${libs.versions.cmake.get()} " +
                    "and run :android:native:assembleDebug",
            )
        }
        val required = setOf("arm64-v8a", "x86_64")
        val text = soFiles.joinToString("\n") { it.absolutePath }
        val missingStill = required.filter { abi ->
            soFiles.none { it.absolutePath.contains(abi) }
        }
        if (missingStill.isNotEmpty()) {
            throw GradleException(
                "libomnillm_llama.so missing ABIs $missingStill. Found:\n$text",
            )
        }
        logger.lifecycle(
            "assertLlamaNativeSoPackaged: OK (${soFiles.size} file(s)) " +
                soFiles.joinToString { it.toRelativeString(buildDir) },
        )
    }
}

/**
 * Product gate alias (GAP_CLOSEOUT / readiness): packaged natives present for
 * production ABIs. Prefer this name in CI scripts.
 */
tasks.register("verifyNativeLibsPresent") {
    group = "verification"
    description =
        "Alias: prove libomnillm_llama.so present for arm64-v8a + x86_64"
    dependsOn("assertLlamaNativeSoPackaged")
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn("checkNativePackaging")
}
