package com.omnillm.engines.llamacpp.lock

import com.omnillm.core.contracts.EngineBuildId

/**
 * Parsed UPSTREAM.lock fields (ENGINE-LLAMACPP §1, ENGINE-STANDARD §4).
 *
 * Complete lock requires: repository, tag or commit, sourceDigest, patchDigest
 * (may be empty-string meaning "no patches" only when [patchSetPresent] is true),
 * toolchainDigest, abis non-empty, artifactDigest, observedAt, licenseDigest.
 *
 * Incomplete lock ⇒ [isComplete] false ⇒ Registry must not publish QUALIFIED.
 */
data class UpstreamLock(
    val schemaVersion: Int = 1,
    val engineId: String = "llama.cpp",
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
    val cmakeVersion: String? = null,
    val toolchainDigest: String? = null,
    val hostOs: String? = null,
    val hostArch: String? = null,
    val buildFlags: String? = null,
    val abis: List<String> = emptyList(),
    val pageSizeEvidence: String? = null,
    /**
     * Artifact digest. Legacy scalar locks record a single SHA-256 here.
     * Per-ABI locks (D2 "stripped-packaged") record `artifactDigest` as a
     * nested map (see [artifactDigestByAbi]) and expose a deterministic
     * aggregate ("<abi>=<digest>,..." sorted by ABI) so string consumers keep
     * non-blank evidence; use [artifactDigestFor] for a real ABI lookup.
     */
    val artifactDigest: String? = null,
    /**
     * Per-ABI artifact digests (D2 "stripped-packaged" policy, e.g.
     * `arm64-v8a: <sha256>`, `x86_64: <sha256>`). Empty for legacy scalar locks.
     */
    val artifactDigestByAbi: Map<String, String> = emptyMap(),
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
     * Digest for a specific ABI. With a per-ABI map, returns the recorded
     * digest or null (fail-closed) for unknown/blank entries. With a legacy
     * scalar lock the scalar applies to every ABI.
     */
    fun artifactDigestFor(abi: String): String? {
        if (artifactDigestByAbi.isNotEmpty()) {
            return artifactDigestByAbi[abi]?.takeIf { it.isNotBlank() }
        }
        return artifactDigest?.takeIf { it.isNotBlank() }
    }

    /**
     * Complete lock gate for qualification eligibility.
     * Empty digests or missing ABI/observedAt keep the lock incomplete.
     */
    fun isComplete(): Boolean {
        if (lockState != LOCK_STATE_LOCKED) {
            // Allow LOCKED only when all fields present; other states always incomplete.
        }
        val hasRef = !commit.isNullOrBlank() || !tag.isNullOrBlank()
        val hasSource = !sourceDigest.isNullOrBlank()
        val hasPatch = patchSetPresent
        val hasToolchain = !toolchainDigest.isNullOrBlank()
        val hasAbis = abis.isNotEmpty()
        val hasArtifact = !artifactDigest.isNullOrBlank() &&
            (artifactDigestByAbi.isEmpty() ||
                (abis.isNotEmpty() && abis.all { artifactDigestByAbi[it]?.isNotBlank() == true }))
        val hasObserved = !observedAt.isNullOrBlank()
        val hasLicense = !licenseDigest.isNullOrBlank()
        val hasBuildId = !engineBuildIdRaw.isNullOrBlank()
        return hasRef && hasSource && hasPatch && hasToolchain && hasAbis &&
            hasArtifact && hasObserved && hasLicense && hasBuildId
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
        observedAt?.let { put("observedAt", it) }
        licenseSpdx?.let { put("licenseSpdx", it) }
        put("abis", abis.joinToString(","))
    }

    companion object {
        const val LOCK_STATE_NOT_LOCKED: String = "NOT_LOCKED"
        const val LOCK_STATE_LOCKED: String = "LOCKED"
        const val DEFAULT_REPOSITORY: String = "https://github.com/ggml-org/llama.cpp"

        /** In-tree template (NOT_LOCKED) matching engines/llama-cpp/UPSTREAM.lock. */
        fun template(): UpstreamLock = UpstreamLock(
            schemaVersion = 1,
            engineId = "llama.cpp",
            lockState = LOCK_STATE_NOT_LOCKED,
            repository = DEFAULT_REPOSITORY,
            licenseSpdx = "MIT",
            ndkVersion = "28.2.13676358",
            cmakeVersion = "3.22.1",
            testedPlatform = "android",
            testedBackends = listOf("cpu"),
            testedFormats = listOf("GGUF"),
            patchSetPresent = false,
        )
    }
}

/**
 * Loads [UpstreamLock] from classpath resource or falls back to template.
 * Does not perform network I/O; lock completion is offline evidence only.
 */
object UpstreamLockLoader {
    private const val RESOURCE_PATH: String = "/com/omnillm/engines/llamacpp/UPSTREAM.lock"

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
        val mapValues = mutableMapOf<String, MutableMap<String, String>>()
        var section = ""
        // Pending nested map header (path + indent), e.g. "artifactDigest:"
        // followed by indented "<abi>: <digest>" entries.
        var mapHeader: Pair<String, Int>? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.substringBefore('#').trimEnd()
            if (line.isBlank()) continue
            val indent = line.length - line.trimStart().length
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) continue
            // section headers like "upstream:" with no value, and nested map
            // headers like "artifactDigest:" with no value
            if (trimmed.endsWith(":") && !trimmed.contains(" ")) {
                val key = trimmed.removeSuffix(":").trim()
                if (!key.contains(".")) {
                    if (indent == 0) {
                        section = key
                        mapHeader = null
                    } else {
                        mapHeader = "$section.$key" to indent
                    }
                    continue
                }
            }
            if (mapHeader != null && indent <= mapHeader.second) {
                mapHeader = null
            }
            if (!trimmed.contains(':')) continue
            val colon = trimmed.indexOf(':')
            val key = trimmed.substring(0, colon).trim()
            var value = trimmed.substring(colon + 1).trim()
            if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                value = value.substring(1, value.length - 1)
            }
            if (mapHeader != null && indent > mapHeader.second) {
                mapValues.getOrPut(mapHeader.first) { mutableMapOf() }[key] = value
                continue
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
                // empty scalar still recorded as present
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
        // Per-ABI artifactDigest map (D2 "stripped-packaged"): nested entries
        // under artifact.artifactDigest, e.g. arm64-v8a / x86_64.
        val artifactDigestByAbi: Map<String, String> =
            mapValues["artifact.artifactDigest"]?.toMap() ?: emptyMap()
        // Legacy scalar (single digest for every ABI) or deterministic
        // aggregate ("<abi>=<digest>" pairs sorted by ABI) so string consumers
        // keep non-blank evidence without pretending to be a single digest.
        val artifactDigest: String? =
            if (artifactDigestByAbi.isNotEmpty()) {
                artifactDigestByAbi.entries
                    .sortedBy { it.key }
                    .joinToString(",") { "${it.key}=${it.value}" }
            } else {
                g("artifact.artifactDigest", "artifactDigest")?.ifBlank { null }
            }
        return UpstreamLock(
            schemaVersion = g("schemaVersion")?.toIntOrNull() ?: 1,
            engineId = g("engineId") ?: "llama.cpp",
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
            cmakeVersion = g("toolchain.cmakeVersion", "cmakeVersion")?.ifBlank { null },
            toolchainDigest = g("toolchain.toolchainDigest", "toolchainDigest")?.ifBlank { null },
            hostOs = g("toolchain.hostOs")?.ifBlank { null },
            hostArch = g("toolchain.hostArch")?.ifBlank { null },
            buildFlags = g("build.buildFlags", "buildFlags")?.ifBlank { null },
            abis = list("build.abis", "abis"),
            pageSizeEvidence = g("build.pageSizeEvidence")?.ifBlank { null },
            artifactDigest = artifactDigest,
            artifactDigestByAbi = artifactDigestByAbi,
            symbolIndexDigest = g("artifact.symbolIndexDigest")?.ifBlank { null },
            engineBuildIdRaw = g("artifact.engineBuildId", "engineBuildId")?.ifBlank { null },
            testedPlatform = g("testedProfile.platform")?.ifBlank { null },
            testedBackends = list("testedProfile.backends"),
            testedFormats = list("testedProfile.formats"),
            profileId = g("testedProfile.profileId")?.ifBlank { null },
        )
    }
}
