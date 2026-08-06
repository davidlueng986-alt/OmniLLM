package com.omnillm.engines.mllm.lock

import com.omnillm.core.contracts.EngineBuildId

/**
 * Parsed UPSTREAM.lock fields (ENGINE-MLLM §1, ENGINE-STANDARD §4).
 *
 * Complete lock requires: repository, tag or commit, sourceDigest, patchDigest
 * field present, toolchainDigest (Go+NDK fingerprint), abis non-empty,
 * artifactDigest (AAR/native), observedAt, licenseDigest, engineBuildId.
 *
 * Incomplete lock ⇒ [isComplete] false ⇒ Registry must not publish QUALIFIED.
 * Product label: NOT_QUALIFIED_UNTIL_UPSTREAM_LOCK.
 */
data class UpstreamLock(
    val schemaVersion: Int = 1,
    val engineId: String = "mllm",
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
    val ndkVersion: String? = null,
    val goVersion: String? = null,
    val cmakeVersion: String? = null,
    val toolchainDigest: String? = null,
    val hostOs: String? = null,
    val hostArch: String? = null,
    val buildFlags: String? = null,
    val abis: List<String> = emptyList(),
    val pageSizeEvidence: String? = null,
    val artifactDigest: String? = null,
    val serverProtocolDigest: String? = null,
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
     * Complete lock gate for qualification eligibility (ENGINE-MLLM §1).
     * Empty digests or missing ABI/observedAt/serverProtocol keep the lock incomplete.
     * Incomplete lock ⇒ Registry must not publish QUALIFIED / SUPPORTED.
     */
    fun isComplete(): Boolean {
        val hasRef = !commit.isNullOrBlank() || !tag.isNullOrBlank()
        val hasSource = !sourceDigest.isNullOrBlank()
        val hasPatch = patchSetPresent
        val hasToolchain = !toolchainDigest.isNullOrBlank()
        val hasGo = !goVersion.isNullOrBlank()
        val hasAbis = abis.isNotEmpty()
        val hasArtifact = !artifactDigest.isNullOrBlank()
        val hasProtocol = !serverProtocolDigest.isNullOrBlank()
        val hasObserved = !observedAt.isNullOrBlank()
        val hasLicense = !licenseDigest.isNullOrBlank()
        val hasBuildId = !engineBuildIdRaw.isNullOrBlank()
        return hasRef && hasSource && hasPatch && hasToolchain && hasGo && hasAbis &&
            hasArtifact && hasProtocol && hasObserved && hasLicense && hasBuildId
    }

    fun toRegistrationFields(): Map<String, String> = buildMap {
        put("engineId", engineId)
        put("lockState", if (isComplete()) LOCK_STATE_LOCKED else LOCK_STATE_NOT_LOCKED)
        put("repository", repository)
        put("upstreamCommitOrTag", upstreamCommitOrTag())
        sourceDigest?.let { put("sourceDigest", it) }
        patchDigest?.let { put("patchDigest", it) }
        toolchainDigest?.let { put("toolchainDigest", it) }
        artifactDigest?.let { put("artifactDigest", it) }
        serverProtocolDigest?.let { put("serverProtocolDigest", it) }
        goVersion?.let { put("goVersion", it) }
        observedAt?.let { put("observedAt", it) }
        licenseSpdx?.let { put("licenseSpdx", it) }
        put("abis", abis.joinToString(","))
    }

    companion object {
        const val LOCK_STATE_NOT_LOCKED: String = "NOT_LOCKED"
        const val LOCK_STATE_LOCKED: String = "LOCKED"
        const val DEFAULT_REPOSITORY: String = "https://github.com/UbiquitousLearning/mllm"

        /** In-tree template (NOT_LOCKED) matching engines/mllm/UPSTREAM.lock. */
        fun template(): UpstreamLock = UpstreamLock(
            schemaVersion = 1,
            engineId = "mllm",
            lockState = LOCK_STATE_NOT_LOCKED,
            repository = DEFAULT_REPOSITORY,
            ndkVersion = "28.2.13676358",
            testedPlatform = "android",
            testedBackends = emptyList(),
            testedFormats = emptyList(),
            patchSetPresent = false,
        )
    }
}

/**
 * Loads [UpstreamLock] from classpath resource or falls back to template.
 * Does not perform network I/O; lock completion is offline evidence only.
 */
object UpstreamLockLoader {
    private const val RESOURCE_PATH: String = "/com/omnillm/engines/mllm/UPSTREAM.lock"

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
            engineId = g("engineId") ?: "mllm",
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
            ndkVersion = g("toolchain.ndkVersion", "ndkVersion")?.ifBlank { null },
            goVersion = g("toolchain.goVersion", "goVersion")?.ifBlank { null },
            cmakeVersion = g("toolchain.cmakeVersion", "cmakeVersion")?.ifBlank { null },
            toolchainDigest = g("toolchain.toolchainDigest", "toolchainDigest")?.ifBlank { null },
            hostOs = g("toolchain.hostOs")?.ifBlank { null },
            hostArch = g("toolchain.hostArch")?.ifBlank { null },
            buildFlags = g("build.buildFlags", "buildFlags")?.ifBlank { null },
            abis = list("build.abis", "abis"),
            pageSizeEvidence = g("build.pageSizeEvidence")?.ifBlank { null },
            artifactDigest = g("artifact.artifactDigest", "artifactDigest")?.ifBlank { null },
            serverProtocolDigest = g("artifact.serverProtocolDigest")?.ifBlank { null },
            symbolIndexDigest = g("artifact.symbolIndexDigest")?.ifBlank { null },
            engineBuildIdRaw = g("artifact.engineBuildId", "engineBuildId")?.ifBlank { null },
            testedPlatform = g("testedProfile.platform")?.ifBlank { null },
            testedBackends = list("testedProfile.backends"),
            testedFormats = list("testedProfile.formats"),
            profileId = g("testedProfile.profileId")?.ifBlank { null },
        )
    }
}
