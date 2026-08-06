package com.omnillm.engines.litertlm.lock

import com.omnillm.core.contracts.EngineBuildId

/**
 * Parsed UPSTREAM.lock fields (ENGINE-LITERT §1, ENGINE-STANDARD §4).
 *
 * Complete lock requires: repository, tag or commit, source or AAR digest,
 * patch set recorded, toolchainDigest, abis non-empty, artifactDigest,
 * observedAt, licenseDigest, and engineBuildId.
 *
 * Incomplete lock ⇒ [isComplete] false ⇒ Registry must not publish QUALIFIED.
 * Design eligibility label: NOT_QUALIFIED_UNTIL_UPSTREAM_LOCK.
 */
data class UpstreamLock(
    val schemaVersion: Int = 1,
    val engineId: String = "LiteRT-LM",
    val lockState: String = LOCK_STATE_NOT_LOCKED,
    val repository: String = DEFAULT_REPOSITORY,
    val tag: String? = null,
    val commit: String? = null,
    val sourceDigest: String? = null,
    val patchDigest: String? = null,
    /** True when patchDigest field was intentionally recorded (including empty = no patches). */
    val patchSetPresent: Boolean = false,
    val observedAt: String? = null,
    val licenseSpdx: String? = null,
    val licenseDigest: String? = null,
    val noticeDigest: String? = null,
    val dependencyReportDigest: String? = null,
    val sbomDigest: String? = null,
    val ndkVersion: String? = null,
    val agpVersion: String? = null,
    val toolchainDigest: String? = null,
    val hostOs: String? = null,
    val hostArch: String? = null,
    val buildFlags: String? = null,
    val abis: List<String> = emptyList(),
    val pageSizeEvidence: String? = null,
    val mavenCoordinate: String? = null,
    val aarDigest: String? = null,
    val nativeLibsDigest: String? = null,
    val apiSurfaceVersion: String? = null,
    val modelArtifactSchema: String? = null,
    val mavenIndexUrl: String? = null,
    val artifactDigest: String? = null,
    val symbolIndexDigest: String? = null,
    val engineBuildIdRaw: String? = null,
    val testedPlatform: String? = null,
    val testedBackends: List<String> = emptyList(),
    val testedFormats: List<String> = emptyList(),
    val profileId: String? = null,
) {
    init {
        require(engineId.isNotEmpty()) { "engineId must be non-empty" }
        require(lockState.isNotEmpty()) { "lockState must be non-empty" }
        require(repository.isNotEmpty()) { "repository must be non-empty" }
    }

    fun upstreamCommitOrTag(): String =
        when {
            !commit.isNullOrBlank() && !tag.isNullOrBlank() -> "$tag@$commit"
            !commit.isNullOrBlank() -> commit
            !tag.isNullOrBlank() -> tag
            else -> ""
        }

    fun resolvedEngineBuildId(): EngineBuildId? {
        val raw = engineBuildIdRaw?.takeIf { it.isNotBlank() } ?: return null
        return EngineBuildId.parse(raw)
    }

    /**
     * Complete lock gate for qualification eligibility.
     * Empty digests or missing ABI/observedAt keep the lock incomplete.
     * Either sourceDigest or aarDigest may satisfy the artifact pin (SDK form).
     */
    fun isComplete(): Boolean {
        val hasRef = !commit.isNullOrBlank() || !tag.isNullOrBlank()
        val hasSourceOrAar =
            !sourceDigest.isNullOrBlank() || !aarDigest.isNullOrBlank()
        val hasPatch = patchSetPresent
        val hasToolchain = !toolchainDigest.isNullOrBlank()
        val hasAbis = abis.isNotEmpty()
        val hasArtifact = !artifactDigest.isNullOrBlank()
        val hasObserved = !observedAt.isNullOrBlank()
        val hasLicense = !licenseDigest.isNullOrBlank()
        val hasBuildId = !engineBuildIdRaw.isNullOrBlank()
        return hasRef && hasSourceOrAar && hasPatch && hasToolchain && hasAbis &&
            hasArtifact && hasObserved && hasLicense && hasBuildId
    }

    fun toRegistrationFields(): Map<String, String> = buildMap {
        put("engineId", engineId)
        put("lockState", if (isComplete()) LOCK_STATE_LOCKED else LOCK_STATE_NOT_LOCKED)
        put("repository", repository)
        put("upstreamCommitOrTag", upstreamCommitOrTag())
        sourceDigest?.let { put("sourceDigest", it) }
        aarDigest?.let { put("aarDigest", it) }
        patchDigest?.let { put("patchDigest", it) }
        toolchainDigest?.let { put("toolchainDigest", it) }
        artifactDigest?.let { put("artifactDigest", it) }
        observedAt?.let { put("observedAt", it) }
        licenseSpdx?.let { put("licenseSpdx", it) }
        put("abis", abis.joinToString(","))
        put("lockEligibility", LOCK_ELIGIBILITY)
    }

    companion object {
        const val LOCK_STATE_NOT_LOCKED: String = "NOT_LOCKED"
        const val LOCK_STATE_LOCKED: String = "LOCKED"
        const val LOCK_ELIGIBILITY: String = "NOT_QUALIFIED_UNTIL_UPSTREAM_LOCK"
        const val DEFAULT_REPOSITORY: String =
            "https://github.com/google-ai-edge/LiteRT-LM"

        /** In-tree template (NOT_LOCKED) matching engines/litert-lm/UPSTREAM.lock. */
        fun template(): UpstreamLock = UpstreamLock(
            schemaVersion = 1,
            engineId = "LiteRT-LM",
            lockState = LOCK_STATE_NOT_LOCKED,
            repository = DEFAULT_REPOSITORY,
            ndkVersion = "28.2.13676358",
            testedPlatform = "android",
            testedBackends = listOf("cpu"),
            testedFormats = listOf("litertlm"),
            patchSetPresent = false,
        )
    }
}

/**
 * Loads [UpstreamLock] from classpath resource or falls back to template.
 * Does not perform network I/O; lock completion is offline evidence only.
 */
object UpstreamLockLoader {
    private const val RESOURCE_PATH: String = "/com/omnillm/engines/litertlm/UPSTREAM.lock"

    fun loadFromClasspathOrTemplate(): UpstreamLock {
        val stream = UpstreamLockLoader::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: return UpstreamLock.template()
        return stream.bufferedReader().use { reader ->
            parseSimpleLock(reader.readText())
        }
    }

    /**
     * Minimal YAML-ish key parser for the lock template (no full YAML dependency).
     * Only recognizes flat and one-level nested keys used by [UpstreamLock].
     */
    fun parseSimpleLock(text: String): UpstreamLock {
        val flat = mutableMapOf<String, String>()
        val listValues = mutableMapOf<String, MutableList<String>>()
        var section = ""
        for (rawLine in text.lineSequence()) {
            val line = rawLine.substringBefore('#').trimEnd()
            if (line.isBlank()) continue
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) continue
            if (trimmed.endsWith(":") && !trimmed.contains(" ")) {
                val key = trimmed.removeSuffix(":").trim()
                if (!key.contains(".")) {
                    section = key
                    continue
                }
            }
            if (!trimmed.contains(':')) continue
            val colon = trimmed.indexOf(':')
            val key = trimmed.substring(0, colon).trim()
            var value = trimmed.substring(colon + 1).trim()
            if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                value = value.substring(1, value.length - 1)
            }
            val path = if (section.isNotEmpty()) "$section.$key" else key
            if (value.startsWith("[") && value.endsWith("]")) {
                val inner = value.removePrefix("[").removeSuffix("]").trim()
                val items = if (inner.isEmpty()) {
                    emptyList()
                } else {
                    inner.split(',').map {
                        it.trim().removeSurrounding("\"").removeSurrounding("'")
                    }.filter { it.isNotEmpty() }
                }
                listValues.getOrPut(path) { mutableListOf() }.addAll(items)
            } else if (value.isNotEmpty()) {
                flat[path] = value
            } else {
                flat[path] = ""
            }
        }

        fun g(vararg keys: String): String? {
            for (k in keys) {
                flat[k]?.let { return it }
            }
            return null
        }

        fun list(vararg keys: String): List<String> {
            for (k in keys) {
                listValues[k]?.let { return it.toList() }
            }
            return emptyList()
        }

        val patchRaw = g("upstream.patchDigest", "patchDigest")
        return UpstreamLock(
            schemaVersion = g("schemaVersion")?.toIntOrNull() ?: 1,
            engineId = g("engineId") ?: "LiteRT-LM",
            lockState = g("lockState") ?: UpstreamLock.LOCK_STATE_NOT_LOCKED,
            repository = g("upstream.repository", "repository")
                ?: UpstreamLock.DEFAULT_REPOSITORY,
            tag = g("upstream.tag", "tag")?.ifBlank { null },
            commit = g("upstream.commit", "commit")?.ifBlank { null },
            sourceDigest = g("upstream.sourceDigest", "sourceDigest")?.ifBlank { null },
            patchDigest = patchRaw?.ifBlank { null },
            patchSetPresent = flat.containsKey("upstream.patchDigest") ||
                flat.containsKey("patchDigest"),
            observedAt = g("upstream.observedAt", "observedAt")?.ifBlank { null },
            licenseSpdx = g("license.spdx", "spdx")?.ifBlank { null },
            licenseDigest = g("license.licenseDigest", "licenseDigest")?.ifBlank { null },
            noticeDigest = g("license.noticeDigest")?.ifBlank { null },
            dependencyReportDigest = g("license.dependencyReportDigest")?.ifBlank { null },
            sbomDigest = g("license.sbomDigest")?.ifBlank { null },
            ndkVersion = g("toolchain.ndkVersion", "ndkVersion")?.ifBlank { null },
            agpVersion = g("toolchain.agpVersion")?.ifBlank { null },
            toolchainDigest = g("toolchain.toolchainDigest", "toolchainDigest")?.ifBlank { null },
            hostOs = g("toolchain.hostOs")?.ifBlank { null },
            hostArch = g("toolchain.hostArch")?.ifBlank { null },
            buildFlags = g("build.buildFlags", "buildFlags")?.ifBlank { null },
            abis = list("build.abis", "abis"),
            pageSizeEvidence = g("build.pageSizeEvidence")?.ifBlank { null },
            mavenCoordinate = g("sdk.mavenCoordinate")?.ifBlank { null },
            aarDigest = g("sdk.aarDigest")?.ifBlank { null },
            nativeLibsDigest = g("sdk.nativeLibsDigest")?.ifBlank { null },
            apiSurfaceVersion = g("sdk.apiSurfaceVersion")?.ifBlank { null },
            modelArtifactSchema = g("sdk.modelArtifactSchema")?.ifBlank { null },
            mavenIndexUrl = g("sdk.mavenIndexUrl")?.ifBlank { null },
            artifactDigest = g("artifact.artifactDigest", "artifactDigest")?.ifBlank { null },
            symbolIndexDigest = g("artifact.symbolIndexDigest")?.ifBlank { null },
            engineBuildIdRaw = g("artifact.engineBuildId", "engineBuildId")?.ifBlank { null },
            testedPlatform = g("testedProfile.platform")?.ifBlank { null },
            testedBackends = list("testedProfile.backends"),
            testedFormats = list("testedProfile.formats"),
            profileId = g("testedProfile.profileId")?.ifBlank { null },
        )
    }
}
