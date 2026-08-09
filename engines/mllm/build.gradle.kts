import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

// ---------------------------------------------------------------------------
// mllm (UbiquitousLearning/mllm) real integration artifacts (ENGINE-MLLM).
//
// Supply chain (see UPSTREAM.lock, LOCKED 2026-08-09):
//   - mllm_server.aar  : upstream-prebuilt Go in-app server (gomllm.Gomllm).
//                        Tracked at engines/mllm/libs/ (sha256 pinned in lock).
//   - jniLibs.zip      : upstream release v2.0 CPU native libs (libMllmRT.so,
//                        libMllmCPUBackend.so, libMllmSdkC.so, libomp.so).
//                        NOT tracked in git (libMllmRT.so exceeds GitHub 100 MB
//                        file limit); fetched at build time from the pinned
//                        release asset and SHA-256 verified (fail closed).
//   - QNN/NPU libs are intentionally NOT packaged: upstream ships them 4 KB
//     page aligned only (see lock build.pageSizeEvidence) and the NPU path is
//     unqualified-by-default.
// ---------------------------------------------------------------------------

val mllmJniLibsUrl: String =
    "https://github.com/UbiquitousLearning/mllm-chat/releases/download/v2.0/jniLibs.zip"
val mllmJniLibsZipSha256: String =
    "E239C3202FE1B9F4172B1CBD8521619046A2243D93860B01101E9E10C8F87B13"

val mllmJniLibsCpuSo: List<String> = listOf(
    "libMllmRT.so",
    "libMllmCPUBackend.so",
    "libMllmSdkC.so",
    "libomp.so",
)

/**
 * Fetch + verify the pinned upstream jniLibs.zip and extract the CPU runtime
 * shared objects into build/mllm-jni/arm64-v8a. Fail closed on SHA-256 mismatch.
 */
val fetchMllmJniLibs by tasks.registering {
    val zipFile = layout.buildDirectory.file("mllm-jni/jniLibs.zip")
    val jniRoot = layout.buildDirectory.dir("mllm-jni")
    val jniAbiDir = layout.buildDirectory.dir("mllm-jni/arm64-v8a")
    inputs.property("jniLibsUrl", mllmJniLibsUrl)
    inputs.property("jniLibsZipSha256", mllmJniLibsZipSha256)
    outputs.dir(jniRoot)
    doLast {
        val out = zipFile.get().asFile
        out.parentFile.mkdirs()
        if (!out.isFile) {
            val connection = URL(mllmJniLibsUrl).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                val code = connection.responseCode
                if (code != 200) {
                    throw GradleException(
                        "mllm jniLibs download failed: HTTP $code ($mllmJniLibsUrl)",
                    )
                }
                connection.inputStream.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(out.readBytes())
            .joinToString("") { "%02X".format(it) }
        if (!digest.equals(mllmJniLibsZipSha256, ignoreCase = true)) {
            throw GradleException(
                "mllm jniLibs.zip SHA-256 mismatch: expected $mllmJniLibsZipSha256 got $digest",
            )
        }
        // Preserve the ABI directory level: build/mllm-jni/jniLibs/arm64-v8a/*.so
        // (zip layout is jniLibs/arm64-v8a/…; the jniLibs/ prefix is kept).
        copy {
            from(zipTree(out)) {
                include(*mllmJniLibsCpuSo.map { "jniLibs/arm64-v8a/$it" }.toTypedArray())
            }
            into(jniRoot.get().asFile)
            includeEmptyDirs = false
        }
        val dest = layout.buildDirectory.dir("mllm-jni/jniLibs/arm64-v8a").get().asFile
        val missing = mllmJniLibsCpuSo.filter { !dest.resolve(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "mllm jniLibs extraction incomplete; missing $missing in $dest",
            )
        }
    }
}

// Package UPSTREAM.lock + capability matrix next to classes for registration helpers
// (classpath resource path /com/omnillm/engines/mllm/UPSTREAM.lock).
val packageEnginePackResources by tasks.registering(Copy::class) {
    from(projectDir) {
        include("UPSTREAM.lock")
        include("capability-matrix.yaml")
    }
    into(layout.buildDirectory.dir("generated/resources/com/omnillm/engines/mllm"))
}

/**
 * Unpack the tracked upstream AAR. AGP 9 forbids direct local .aar file
 * dependencies when packaging an AAR, so we consume the AAR contents directly:
 * classes.jar (gomllm + go bindings) as a jar dependency and libgojni.so into
 * the jniLibs layout. Digest-pinned via the tracked file (see UPSTREAM.lock).
 */
val extractMllmServerAar by tasks.registering {
    val aarFile = file("libs/mllm_server.aar")
    val classesJar = layout.buildDirectory.file("mllm-server-aar/classes.jar")
    val gojniSo = layout.buildDirectory.file("mllm-jni/jniLibs/arm64-v8a/libgojni.so")
    inputs.file(aarFile)
    outputs.files(classesJar, gojniSo)
    doLast {
        check(aarFile.isFile) { "missing engines/mllm/libs/mllm_server.aar (tracked upstream artifact)" }
        copy {
            from(zipTree(aarFile)) { include("classes.jar") }
            into(layout.buildDirectory.dir("mllm-server-aar").get().asFile)
        }
        copy {
            from(zipTree(aarFile)) { include("jni/arm64-v8a/libgojni.so") }
            into(layout.buildDirectory.dir("mllm-jni/jniLibs").get().asFile)
            eachFile { path = "arm64-v8a/libgojni.so" } // strip the aar's jni/ prefix
            includeEmptyDirs = false
        }
        check(gojniSo.get().asFile.isFile) { "libgojni.so extraction failed" }
    }
}

android {
    namespace = "com.omnillm.engines.mllm"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("mllm-jni/jniLibs").get().asFile)
            resources.srcDir(layout.buildDirectory.dir("generated/resources").get().asFile)
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    implementation(libs.kotlin.stdlib)
    api(libs.kotlinx.coroutines.core)
    // OpenAI-compatible chat completions JSON + SSE transport for the in-app Go server.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // Upstream Go in-app server bindings (gomllm.Gomllm) — unpacked from the
    // tracked mllm_server.aar by extractMllmServerAar (AGP 9: no AAR-in-AAR).
    implementation(files(extractMllmServerAar.map { it.outputs.files.filter { f -> f.name == "classes.jar" }.singleFile }))

    // Engine SPI (CORE-ENGINE / ENGINE-STANDARD)
    api(project(":engines:api"))

    // Canonical types, contracts, errors, resource vectors, identity, state ids
    api(project(":core:canonical"))
    api(project(":core:contracts"))
    api(project(":core:errors"))
    api(project(":core:resource"))
    api(project(":core:identity"))
    api(project(":core:state"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.matching { it.name == "compileDebugKotlin" || it.name == "compileReleaseKotlin" }
    .configureEach {
        dependsOn(rootProject.tasks.named("generateContracts"))
        dependsOn(extractMllmServerAar)
    }

tasks.named("preBuild").configure {
    dependsOn(fetchMllmJniLibs, extractMllmServerAar, packageEnginePackResources)
}
