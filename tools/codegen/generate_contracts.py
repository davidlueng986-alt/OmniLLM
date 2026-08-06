#!/usr/bin/env python3
"""
OmniLLM formal-contract codegen.

Reads authority catalogs under specs/ and emits Kotlin sources into:
  core/canonical  — digests, enums, ResourceVector, OmniResult, ACL, capabilities
  core/state      — FSM transition tables
  core/errors     — sealed error catalog

Usage:
  python tools/codegen/generate_contracts.py [--repo-root PATH] [--check]

--check: write to a temp tree and fail if generated sources would differ
         (CI drift gate between catalogs and committed generated code).

Authority: docs/30-core-platform/formal-contract-artifacts.md
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import re
import sys
import tempfile
from pathlib import Path
from typing import Any

try:
    import yaml
except ImportError as exc:  # pragma: no cover
    raise SystemExit(
        "PyYAML is required. Install with: pip install -r tools/codegen/requirements.txt"
    ) from exc


GENERATED_HEADER = """\
// ---------------------------------------------------------------------------
// GENERATED FILE — DO NOT EDIT BY HAND
// Source: tools/codegen/generate_contracts.py
// Authority: specs/*.yaml (formal-contract-artifacts)
// Re-run: ./gradlew generateContracts
// ---------------------------------------------------------------------------
"""

DIGEST_HEX64 = re.compile(r"^[0-9a-f]{64}$")


def load_yaml(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def kotlin_string(value: str) -> str:
    return (
        '"'
        + value.replace("\\", "\\\\")
            .replace('"', '\\"')
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        + '"'
    )


def kotlin_string_list(values: list[str]) -> str:
    if not values:
        return "emptyList()"
    inner = ", ".join(kotlin_string(v) for v in values)
    return f"listOf({inner})"


def safe_enum_name(raw: str) -> str:
    """Map catalog identifiers to valid Kotlin enum entry names."""
    name = raw.replace("-", "_").replace(".", "_").replace("/", "_")
    if name and name[0].isdigit():
        name = f"N_{name}"
    return name


def write_file(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    # Normalize to LF for stable cross-platform drift checks.
    text = content.replace("\r\n", "\n")
    if not text.endswith("\n"):
        text += "\n"
    path.write_text(text, encoding="utf-8", newline="\n")


def file_fingerprint(path: Path) -> str:
    data = path.read_bytes()
    return hashlib.sha256(data).hexdigest()


# ---------------------------------------------------------------------------
# Generators
# ---------------------------------------------------------------------------


def gen_canonical_encoding(out_dir: Path) -> None:
    content = GENERATED_HEADER + """
package com.omnillm.core.canonical.generated

/**
 * Digest / encoding rules from specs/canonical-types.yaml `encoding`.
 * Digests are always lower-case hexadecimal without prefix (INV identity hash).
 */
object CanonicalEncoding {
    const val DIGEST_PATTERN: String = "^[0-9a-f]{64}$"
    const val DIGEST_HEX_LENGTH: Int = 64

    /** Domain separators for identity hashes (UTF-8 + LF + RFC8785 bytes). */
    const val ARTIFACT_PACKAGE_ID_V1: String = "OmniLLM.ArtifactPackageId.v1"
    const val MODEL_REVISION_ID_V1: String = "OmniLLM.ModelRevisionId.v1"
    const val CONTENT_REPORT_PAYLOAD_V1: String = "OmniLLM.ContentReportPayload.v1"

    private val digestRegex = Regex(DIGEST_PATTERN)

    fun isDigestHex64(value: String): Boolean = digestRegex.matches(value)

    fun requireDigestHex64(value: String, label: String = "digest"): String {
        require(isDigestHex64(value)) {
            "\$label must be lower-case 64-char hex SHA-256, got length=\${value.length}"
        }
        return value
    }
}
"""
    write_file(out_dir / "CanonicalEncoding.kt", content)


def gen_digest_types(out_dir: Path) -> None:
    content = GENERATED_HEADER + """
package com.omnillm.core.canonical.generated

/**
 * Typed digest / identity wrappers from specs/canonical-types.yaml.
 * All digest strings are lower-case hex SHA-256 (64 chars).
 */
@JvmInline
value class BlobId private constructor(val hex: String) {
    init {
        CanonicalEncoding.requireDigestHex64(hex, "BlobId")
    }

    override fun toString(): String = hex

    companion object {
        fun parse(hex: String): BlobId = BlobId(hex.lowercase().also {
            CanonicalEncoding.requireDigestHex64(it, "BlobId")
        }.let { CanonicalEncoding.requireDigestHex64(hex.lowercase(), "BlobId") }.let { hex.lowercase() }.let {
            // ensure validated lower-case
            val v = hex.lowercase()
            CanonicalEncoding.requireDigestHex64(v, "BlobId")
            return BlobId(v)
        })

        fun ofValidated(hex: String): BlobId = BlobId(hex)
    }
}

@JvmInline
value class ArtifactPackageId private constructor(val hex: String) {
    init {
        CanonicalEncoding.requireDigestHex64(hex, "ArtifactPackageId")
    }

    override fun toString(): String = hex

    companion object {
        fun parse(hex: String): ArtifactPackageId {
            val v = hex.lowercase()
            CanonicalEncoding.requireDigestHex64(v, "ArtifactPackageId")
            return ArtifactPackageId(v)
        }

        fun ofValidated(hex: String): ArtifactPackageId = ArtifactPackageId(hex)
    }
}

@JvmInline
value class ModelRevisionId private constructor(val hex: String) {
    init {
        CanonicalEncoding.requireDigestHex64(hex, "ModelRevisionId")
    }

    override fun toString(): String = hex

    companion object {
        fun parse(hex: String): ModelRevisionId {
            val v = hex.lowercase()
            CanonicalEncoding.requireDigestHex64(v, "ModelRevisionId")
            return ModelRevisionId(v)
        }

        fun ofValidated(hex: String): ModelRevisionId = ModelRevisionId(hex)
    }
}

@JvmInline
value class Sha256Digest private constructor(val hex: String) {
    init {
        CanonicalEncoding.requireDigestHex64(hex, "Sha256Digest")
    }

    override fun toString(): String = hex

    companion object {
        fun parse(hex: String): Sha256Digest {
            val v = hex.lowercase()
            CanonicalEncoding.requireDigestHex64(v, "Sha256Digest")
            return Sha256Digest(v)
        }

        fun ofValidated(hex: String): Sha256Digest = Sha256Digest(hex)
    }
}
"""
    # Fix BlobId.parse - I made a mess with nested lets. Rewrite cleanly.
    content = GENERATED_HEADER + """
package com.omnillm.core.canonical.generated

/**
 * Typed digest / identity wrappers from specs/canonical-types.yaml.
 * All digest strings are lower-case hex SHA-256 (64 chars).
 */
@JvmInline
value class BlobId private constructor(val hex: String) {
    init {
        CanonicalEncoding.requireDigestHex64(hex, "BlobId")
    }

    override fun toString(): String = hex

    companion object {
        fun parse(hex: String): BlobId {
            val v = hex.lowercase()
            CanonicalEncoding.requireDigestHex64(v, "BlobId")
            return BlobId(v)
        }

        fun ofValidated(hex: String): BlobId = BlobId(hex)
    }
}

@JvmInline
value class ArtifactPackageId private constructor(val hex: String) {
    init {
        CanonicalEncoding.requireDigestHex64(hex, "ArtifactPackageId")
    }

    override fun toString(): String = hex

    companion object {
        fun parse(hex: String): ArtifactPackageId {
            val v = hex.lowercase()
            CanonicalEncoding.requireDigestHex64(v, "ArtifactPackageId")
            return ArtifactPackageId(v)
        }

        fun ofValidated(hex: String): ArtifactPackageId = ArtifactPackageId(hex)
    }
}

@JvmInline
value class ModelRevisionId private constructor(val hex: String) {
    init {
        CanonicalEncoding.requireDigestHex64(hex, "ModelRevisionId")
    }

    override fun toString(): String = hex

    companion object {
        fun parse(hex: String): ModelRevisionId {
            val v = hex.lowercase()
            CanonicalEncoding.requireDigestHex64(v, "ModelRevisionId")
            return ModelRevisionId(v)
        }

        fun ofValidated(hex: String): ModelRevisionId = ModelRevisionId(hex)
    }
}

@JvmInline
value class Sha256Digest private constructor(val hex: String) {
    init {
        CanonicalEncoding.requireDigestHex64(hex, "Sha256Digest")
    }

    override fun toString(): String = hex

    companion object {
        fun parse(hex: String): Sha256Digest {
            val v = hex.lowercase()
            CanonicalEncoding.requireDigestHex64(v, "Sha256Digest")
            return Sha256Digest(v)
        }

        fun ofValidated(hex: String): Sha256Digest = Sha256Digest(hex)
    }
}
"""
    write_file(out_dir / "DigestTypes.kt", content)


def gen_enums(canonical: dict[str, Any], out_dir: Path) -> None:
    lines: list[str] = [
        GENERATED_HEADER,
        "package com.omnillm.core.canonical.generated",
        "",
        "/** Enum types from specs/canonical-types.yaml (kind: enum only). */",
        "",
    ]
    for t in canonical.get("types", []):
        if t.get("kind") != "enum":
            continue
        type_id = t["id"]
        values = t.get("values") or []
        lines.append(f"/** Catalog type `{type_id}`. */")
        lines.append(f"enum class {type_id} {{")
        for i, v in enumerate(values):
            comma = "," if i < len(values) - 1 else ""
            lines.append(f"    {safe_enum_name(v)}{comma}")
        lines.append("    ;")
        lines.append("")
        lines.append("    companion object {")
        lines.append(f"        fun fromCatalogName(name: String): {type_id}? =")
        lines.append("            entries.firstOrNull { it.name == name }")
        lines.append("")
        lines.append(f"        fun requireFromCatalogName(name: String): {type_id} =")
        lines.append(
            f"            fromCatalogName(name) ?: error(\"Unknown {type_id}: \$name\")"
        )
        lines.append("    }")
        lines.append("}")
        lines.append("")
    write_file(out_dir / "CanonicalEnums.kt", "\n".join(lines))


def gen_resource_vector(canonical: dict[str, Any], out_dir: Path) -> None:
    dims: list[str] = []
    for t in canonical.get("types", []):
        if t.get("id") == "ResourceVector":
            dims = list(t.get("dimensions") or [])
            break
    if not dims:
        raise SystemExit("ResourceVector dimensions missing from canonical-types.yaml")

    params = ",\n".join(f"    val {d}: Long = 0L" for d in dims)
    zeros = ",\n".join(f"            {d} = 0L" for d in dims)
    plus = ",\n".join(f"            {d} = Math.addExact(this.{d}, other.{d})" for d in dims)
    minus = ",\n".join(
        f"            {d} = Math.subtractExact(this.{d}, other.{d})" for d in dims
    )
    dominates = " &&\n            ".join(f"this.{d} >= other.{d}" for d in dims)
    non_neg = " &&\n            ".join(f"{d} >= 0L" for d in dims)
    dim_list = ", ".join(kotlin_string(d) for d in dims)

    content = GENERATED_HEADER + f"""
package com.omnillm.core.canonical.generated

/**
 * ResourceVector from specs/canonical-types.yaml.
 * All dimensions are additive charges; no peak field is embedded in this type.
 * Arithmetic uses checked overflow (fail closed).
 */
data class ResourceVector(
{params},
) {{
    init {{
        require(
            {non_neg}
        ) {{ "ResourceVector dimensions must be non-negative" }}
    }}

    fun plus(other: ResourceVector): ResourceVector = ResourceVector(
{plus},
    )

    fun minus(other: ResourceVector): ResourceVector = ResourceVector(
{minus},
    )

    /** True when every dimension of this vector is >= [other]. */
    fun dominates(other: ResourceVector): Boolean =
        {dominates}

    companion object {{
        val ZERO: ResourceVector = ResourceVector(
{zeros},
        )

        /** Catalog dimension names in declared order. */
        val DIMENSION_NAMES: List<String> = listOf({dim_list})
    }}
}}

/**
 * ResourceEnvelope: steady is resident after commit; peak is max during the op.
 * Each peak dimension MUST be >= steady.
 */
data class ResourceEnvelope(
    val steady: ResourceVector,
    val peak: ResourceVector,
) {{
    init {{
        require(peak.dominates(steady)) {{
            "ResourceEnvelope.peak must dominate steady on every dimension"
        }}
    }}
}}
"""
    write_file(out_dir / "ResourceVector.kt", content)


def gen_omni_result(out_dir: Path) -> None:
    content = GENERATED_HEADER + """
package com.omnillm.core.canonical.generated

import com.omnillm.core.errors.generated.OmniError

/**
 * Canonical operation result. Cross-module suspend APIs return OmniResult<T>
 * rather than throwing for expected semantic failures.
 */
sealed class OmniResult<out T> {
    data class Ok<T>(val value: T) : OmniResult<T>()
    data class Err(val error: OmniError) : OmniResult<Nothing>()

    val isOk: Boolean get() = this is Ok
    val isErr: Boolean get() = this is Err

    fun getOrNull(): T? = when (this) {
        is Ok -> value
        is Err -> null
    }

    fun errorOrNull(): OmniError? = when (this) {
        is Ok -> null
        is Err -> error
    }

    inline fun <R> map(transform: (T) -> R): OmniResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }

    inline fun <R> flatMap(transform: (T) -> OmniResult<R>): OmniResult<R> = when (this) {
        is Ok -> transform(value)
        is Err -> this
    }

    inline fun getOrElse(default: (OmniError) -> @UnsafeVariance T): T = when (this) {
        is Ok -> value
        is Err -> default(error)
    }

    companion object {
        fun <T> ok(value: T): OmniResult<T> = Ok(value)
        fun err(error: OmniError): OmniResult<Nothing> = Err(error)
    }
}
"""
    write_file(out_dir / "OmniResult.kt", content)


def gen_access_control(acl: dict[str, Any], out_dir: Path) -> None:
    principals = acl.get("principals") or []
    scopes = acl.get("scopes") or []
    profiles = acl.get("profiles") or []
    invariants = acl.get("invariants") or []

    lines: list[str] = [
        GENERATED_HEADER,
        "package com.omnillm.core.canonical.generated",
        "",
        "/** Access-control catalog from specs/access-control-catalog.yaml. */",
        "",
        "enum class PrincipalKind {",
    ]
    for i, p in enumerate(principals):
        comma = "," if i < len(principals) - 1 else ""
        lines.append(f"    {safe_enum_name(p['id'])}{comma}")
    lines.append("    ;")
    lines.append("")
    lines.append("    companion object {")
    lines.append("        fun fromId(id: String): PrincipalKind? =")
    lines.append("            entries.firstOrNull { it.name == id }")
    lines.append("    }")
    lines.append("}")
    lines.append("")
    lines.append("enum class AccessScope(val id: String) {")
    for i, s in enumerate(scopes):
        comma = "," if i < len(scopes) - 1 else ""
        sid = s["id"]
        lines.append(f"    {safe_enum_name(sid)}({kotlin_string(sid)}){comma}")
    lines.append("    ;")
    lines.append("")
    lines.append("    companion object {")
    lines.append("        fun fromId(id: String): AccessScope? =")
    lines.append("            entries.firstOrNull { it.id == id }")
    lines.append("")
    lines.append("        fun requireFromId(id: String): AccessScope =")
    lines.append(
        '            fromId(id) ?: error("Unknown scope (fail closed): $id")'
    )
    lines.append("    }")
    lines.append("}")
    lines.append("")
    lines.append("enum class AccessProfile(val id: String) {")
    for i, p in enumerate(profiles):
        comma = "," if i < len(profiles) - 1 else ""
        pid = p["id"]
        lines.append(f"    {safe_enum_name(pid)}({kotlin_string(pid)}){comma}")
    lines.append("    ;")
    lines.append("")
    lines.append("    companion object {")
    lines.append("        fun fromId(id: String): AccessProfile? =")
    lines.append("            entries.firstOrNull { it.id == id }")
    lines.append("    }")
    lines.append("}")
    lines.append("")
    lines.append("data class ScopeDefinition(")
    lines.append("    val id: AccessScope,")
    lines.append("    val operations: List<String>,")
    lines.append("    val transport: String? = null,")
    lines.append(")")
    lines.append("")
    lines.append("data class ProfileDefinition(")
    lines.append("    val id: AccessProfile,")
    lines.append("    val scopes: List<String>,")
    lines.append("    val transport: String? = null,")
    lines.append("    val wildcardScopes: Boolean = false,")
    lines.append("    val requiresExplicitLocalIssuance: Boolean = false,")
    lines.append("    val acceptedOnLanListener: Boolean? = null,")
    lines.append("    val requiresExplicitApprovalForEveryScope: Boolean = false,")
    lines.append(")")
    lines.append("")
    lines.append("object AccessControlCatalog {")
    lines.append(f"    const val SCHEMA_VERSION: Int = {int(acl.get('schemaVersion', 2))}")
    lines.append("")
    lines.append("    val SCOPES: List<ScopeDefinition> = listOf(")
    for s in scopes:
        ops = s.get("operations") or []
        transport = s.get("transport")
        t_arg = f", transport = {kotlin_string(transport)}" if transport else ""
        lines.append(
            f"        ScopeDefinition(AccessScope.{safe_enum_name(s['id'])}, "
            f"{kotlin_string_list(ops)}{t_arg}),"
        )
    lines.append("    )")
    lines.append("")
    lines.append("    val PROFILES: List<ProfileDefinition> = listOf(")
    for p in profiles:
        sc = p.get("scopes") or []
        wildcard = sc == ["*"] or sc == "*"
        scope_list = [] if wildcard else list(sc)
        transport = p.get("transport")
        parts = [
            f"AccessProfile.{safe_enum_name(p['id'])}",
            kotlin_string_list(scope_list),
        ]
        kwargs = []
        if transport:
            kwargs.append(f"transport = {kotlin_string(transport)}")
        if wildcard:
            kwargs.append("wildcardScopes = true")
        if p.get("requiresExplicitLocalIssuance"):
            kwargs.append("requiresExplicitLocalIssuance = true")
        if "acceptedOnLanListener" in p:
            kwargs.append(
                f"acceptedOnLanListener = {'true' if p['acceptedOnLanListener'] else 'false'}"
            )
        if p.get("requiresExplicitApprovalForEveryScope"):
            kwargs.append("requiresExplicitApprovalForEveryScope = true")
        arg = ", ".join(parts + kwargs)
        lines.append(f"        ProfileDefinition({arg}),")
    lines.append("    )")
    lines.append("")
    lines.append(f"    val INVARIANTS: List<String> = {kotlin_string_list(list(invariants))}")
    lines.append("")
    lines.append("    fun profileAllowsScope(profile: AccessProfile, scope: AccessScope): Boolean {")
    lines.append("        val def = PROFILES.firstOrNull { it.id == profile } ?: return false")
    lines.append("        if (def.wildcardScopes) return true")
    lines.append("        return def.scopes.contains(scope.id)")
    lines.append("    }")
    lines.append("")
    lines.append("    fun requireKnownScope(id: String): AccessScope = AccessScope.requireFromId(id)")
    lines.append("}")
    lines.append("")
    write_file(out_dir / "AccessControlCatalog.kt", "\n".join(lines))


def gen_capabilities(cap: dict[str, Any], out_dir: Path) -> None:
    states = cap.get("states") or []
    capabilities = cap.get("capabilities") or []
    evidence = cap.get("requiredEvidenceBinding") or []

    lines: list[str] = [
        GENERATED_HEADER,
        "package com.omnillm.core.canonical.generated",
        "",
        "/** Capability catalog from specs/capability-catalog.yaml. */",
        "",
        "/** Runtime availability states for a capability cell. */",
        "enum class CapabilityAvailability {",
    ]
    for i, s in enumerate(states):
        comma = "," if i < len(states) - 1 else ""
        lines.append(f"    {safe_enum_name(s)}{comma}")
    lines.append("}")
    lines.append("")
    lines.append("enum class CapabilityId(val id: String) {")
    for i, c in enumerate(capabilities):
        comma = "," if i < len(capabilities) - 1 else ""
        cid = c["id"]
        lines.append(f"    {safe_enum_name(cid)}({kotlin_string(cid)}){comma}")
    lines.append("    ;")
    lines.append("")
    lines.append("    companion object {")
    lines.append("        fun fromId(id: String): CapabilityId? =")
    lines.append("            entries.firstOrNull { it.id == id }")
    lines.append("")
    lines.append("        /** Unknown capability IDs fail closed (INV-018). */")
    lines.append("        fun requireFromId(id: String): CapabilityId =")
    lines.append(
        '            fromId(id) ?: error("Unknown capability (fail closed): $id")'
    )
    lines.append("    }")
    lines.append("}")
    lines.append("")
    lines.append("data class CapabilityDefinition(")
    lines.append("    val id: CapabilityId,")
    lines.append("    val owner: String,")
    lines.append("    val dependsOn: List<CapabilityId>,")
    lines.append(")")
    lines.append("")
    lines.append("object CapabilityCatalog {")
    lines.append(f"    const val SCHEMA_VERSION: Int = {int(cap.get('schemaVersion', 2))}")
    lines.append(
        f"    val REQUIRED_EVIDENCE_BINDING: List<String> = {kotlin_string_list(list(evidence))}"
    )
    lines.append("")
    lines.append("    val ALL: List<CapabilityDefinition> = listOf(")
    for c in capabilities:
        deps = c.get("dependsOn") or []
        dep_expr = (
            "emptyList()"
            if not deps
            else "listOf("
            + ", ".join(f"CapabilityId.{safe_enum_name(d)}" for d in deps)
            + ")"
        )
        lines.append(
            f"        CapabilityDefinition(CapabilityId.{safe_enum_name(c['id'])}, "
            f"{kotlin_string(c.get('owner', ''))}, {dep_expr}),"
        )
    lines.append("    )")
    lines.append("")
    lines.append("    private val byId: Map<CapabilityId, CapabilityDefinition> =")
    lines.append("        ALL.associateBy { it.id }")
    lines.append("")
    lines.append("    fun get(id: CapabilityId): CapabilityDefinition =")
    lines.append("        byId.getValue(id)")
    lines.append("")
    lines.append("    fun requireKnown(id: String): CapabilityId = CapabilityId.requireFromId(id)")
    lines.append("}")
    lines.append("")
    write_file(out_dir / "CapabilityCatalog.kt", "\n".join(lines))


def gen_errors(errors_doc: dict[str, Any], out_dir: Path) -> None:
    errors = errors_doc.get("errors") or []
    stream_rule = errors_doc.get("streamRule") or ""

    # OmniErrorCode enum with metadata
    lines: list[str] = [
        GENERATED_HEADER,
        "package com.omnillm.core.errors.generated",
        "",
        "/** Error catalog from specs/error-catalog.yaml. */",
        "",
        "enum class ErrorCategory {",
        "    SEMANTIC,",
        "    TRANSPORT,",
        "}",
        "",
        "enum class OmniErrorCode(",
        "    val code: String,",
        "    val httpStatus: Int,",
        "    val category: ErrorCategory,",
        "    val retryable: Boolean,",
        "    val requiredClientAction: String,",
        ") {",
    ]
    for i, e in enumerate(errors):
        comma = "," if i < len(errors) - 1 else ""
        cat = str(e.get("category", "semantic")).upper()
        if cat not in ("SEMANTIC", "TRANSPORT"):
            cat = "SEMANTIC"
        retry = "true" if e.get("retryable") else "false"
        lines.append(
            f"    {safe_enum_name(e['code'])}("
            f"{kotlin_string(e['code'])}, "
            f"{int(e['httpStatus'])}, "
            f"ErrorCategory.{cat}, "
            f"{retry}, "
            f"{kotlin_string(str(e.get('requiredClientAction', '')))}"
            f"){comma}"
        )
    lines.append("    ;")
    lines.append("")
    lines.append("    companion object {")
    lines.append("        fun fromCode(code: String): OmniErrorCode? =")
    lines.append("            entries.firstOrNull { it.code == code }")
    lines.append("")
    lines.append("        fun requireFromCode(code: String): OmniErrorCode =")
    lines.append(
        '            fromCode(code) ?: error("Unknown error code (fail closed): $code")'
    )
    lines.append("    }")
    lines.append("}")
    lines.append("")
    write_file(out_dir / "OmniErrorCode.kt", "\n".join(lines))

    # Sealed OmniError
    sealed: list[str] = [
        GENERATED_HEADER,
        "package com.omnillm.core.errors.generated",
        "",
        "/**",
        " * Sealed error hierarchy derived from specs/error-catalog.yaml.",
        " * Unknown codes must not be constructed at the boundary (fail closed).",
        " */",
        "sealed class OmniError {",
        "    abstract val code: OmniErrorCode",
        "    abstract val message: String?",
        "    abstract val details: Map<String, String>",
        "",
        "    val httpStatus: Int get() = code.httpStatus",
        "    val retryable: Boolean get() = code.retryable",
        "    val category: ErrorCategory get() = code.category",
        "    val requiredClientAction: String get() = code.requiredClientAction",
        "",
    ]
    for e in errors:
        c = safe_enum_name(e["code"])
        sealed.append(f"    data class {c}(")
        sealed.append("        override val message: String? = null,")
        sealed.append("        override val details: Map<String, String> = emptyMap(),")
        sealed.append(f"    ) : OmniError() {{")
        sealed.append(f"        override val code: OmniErrorCode = OmniErrorCode.{c}")
        sealed.append("    }")
        sealed.append("")
    sealed.append("    companion object {")
    sealed.append(f"        val STREAM_RULE: String = {kotlin_string(stream_rule)}")
    sealed.append("")
    sealed.append("        fun of(")
    sealed.append("            code: OmniErrorCode,")
    sealed.append("            message: String? = null,")
    sealed.append("            details: Map<String, String> = emptyMap(),")
    sealed.append("        ): OmniError = when (code) {")
    for e in errors:
        c = safe_enum_name(e["code"])
        sealed.append(f"            OmniErrorCode.{c} -> {c}(message, details)")
    sealed.append("        }")
    sealed.append("")
    sealed.append("        fun ofCode(")
    sealed.append("            code: String,")
    sealed.append("            message: String? = null,")
    sealed.append("            details: Map<String, String> = emptyMap(),")
    sealed.append("        ): OmniError = of(OmniErrorCode.requireFromCode(code), message, details)")
    sealed.append("    }")
    sealed.append("}")
    sealed.append("")
    write_file(out_dir / "OmniError.kt", "\n".join(sealed))


def normalize_from_states(from_field: Any) -> list[str]:
    if isinstance(from_field, list):
        return [str(x) for x in from_field]
    return [str(from_field)]


def normalize_named_map(raw: Any) -> list[str]:
    """guards/actions may be a map of name->desc or a list of names."""
    if raw is None:
        return []
    if isinstance(raw, dict):
        return [str(k) for k in raw.keys()]
    if isinstance(raw, list):
        return [str(x) for x in raw]
    return []


def gen_state_machines(sm_doc: dict[str, Any], out_dir: Path) -> None:
    machines = sm_doc.get("machines") or []
    lines: list[str] = [
        GENERATED_HEADER,
        "package com.omnillm.core.state.generated",
        "",
        "/**",
        " * FSM catalogs from specs/state-machines.yaml.",
        " * Structural transitions only; guards are catalog labels evaluated by runtime.",
        " */",
        "",
        "data class FsmTransition(",
        "    val id: String,",
        "    val from: String,",
        "    val event: String,",
        "    val to: String,",
        "    val guard: String,",
        "    val actions: List<String>,",
        ")",
        "",
        "data class FsmMachineDefinition(",
        "    val id: String,",
        "    val initial: String,",
        "    val terminal: Set<String>,",
        "    val states: Set<String>,",
        "    val guards: Set<String>,",
        "    val actions: Set<String>,",
        "    val transitions: List<FsmTransition>,",
        ") {",
        "    private val byFromEvent: Map<Pair<String, String>, List<FsmTransition>> =",
        "        transitions.groupBy { it.from to it.event }",
        "",
        "    fun isTerminal(state: String): Boolean = state in terminal",
        "",
        "    fun isKnownState(state: String): Boolean = state in states",
        "",
        "    fun candidates(from: String, event: String): List<FsmTransition> =",
        "        byFromEvent[from to event].orEmpty()",
        "",
        "    /**",
        "     * Structural step: fails closed when no transition exists.",
        "     * When multiple transitions share (from, event), returns all candidates",
        "     * so the runtime can evaluate guards (e.g. TOKEN DRAIN_COMPLETE).",
        "     */",
        "    fun step(from: String, event: String): FsmStepResult {",
        "        if (!isKnownState(from)) {",
        "            return FsmStepResult.Illegal(",
        '                machineId = id,',
        "                from = from,",
        "                event = event,",
        '                reason = "unknown state",',
        "            )",
        "        }",
        "        if (isTerminal(from)) {",
        "            return FsmStepResult.Illegal(",
        "                machineId = id,",
        "                from = from,",
        "                event = event,",
        '                reason = "terminal state has no outbound transitions",',
        "            )",
        "        }",
        "        val list = candidates(from, event)",
        "        if (list.isEmpty()) {",
        "            return FsmStepResult.Illegal(",
        "                machineId = id,",
        "                from = from,",
        "                event = event,",
        '                reason = "no transition for event",',
        "            )",
        "        }",
        "        if (list.size == 1) {",
        "            return FsmStepResult.Taken(list.single())",
        "        }",
        "        return FsmStepResult.Ambiguous(list)",
        "    }",
        "}",
        "",
        "sealed class FsmStepResult {",
        "    data class Taken(val transition: FsmTransition) : FsmStepResult()",
        "    data class Ambiguous(val candidates: List<FsmTransition>) : FsmStepResult()",
        "    data class Illegal(",
        "        val machineId: String,",
        "        val from: String,",
        "        val event: String,",
        "        val reason: String,",
        "    ) : FsmStepResult()",
        "}",
        "",
        "object StateMachines {",
    ]

    machine_ids: list[str] = []
    for m in machines:
        mid = str(m["id"])
        machine_ids.append(mid)
        prop = safe_enum_name(mid)
        initial = str(m["initial"])
        terminal = [str(x) for x in (m.get("terminal") or [])]
        states = [str(x) for x in (m.get("states") or [])]
        guards = normalize_named_map(m.get("guards"))
        actions = normalize_named_map(m.get("actions"))
        transitions = m.get("transitions") or []

        lines.append(f"    val {prop}: FsmMachineDefinition = FsmMachineDefinition(")
        lines.append(f"        id = {kotlin_string(mid)},")
        lines.append(f"        initial = {kotlin_string(initial)},")
        lines.append(f"        terminal = setOf({', '.join(kotlin_string(t) for t in terminal)}),")
        lines.append(f"        states = setOf({', '.join(kotlin_string(s) for s in states)}),")
        lines.append(
            f"        guards = setOf({', '.join(kotlin_string(g) for g in guards)}),"
            if guards
            else "        guards = emptySet(),"
        )
        lines.append(
            f"        actions = setOf({', '.join(kotlin_string(a) for a in actions)}),"
            if actions
            else "        actions = emptySet(),"
        )
        lines.append("        transitions = listOf(")
        for tr in transitions:
            from_states = normalize_from_states(tr.get("from"))
            event = str(tr.get("event"))
            to = str(tr.get("to"))
            guard = str(tr.get("guard", "true"))
            tr_actions = [str(a) for a in (tr.get("actions") or [])]
            tid = str(tr.get("id"))
            for fs in from_states:
                lines.append("            FsmTransition(")
                lines.append(f"                id = {kotlin_string(tid)},")
                lines.append(f"                from = {kotlin_string(fs)},")
                lines.append(f"                event = {kotlin_string(event)},")
                lines.append(f"                to = {kotlin_string(to)},")
                lines.append(f"                guard = {kotlin_string(guard)},")
                lines.append(f"                actions = {kotlin_string_list(tr_actions)},")
                lines.append("            ),")
        lines.append("        ),")
        lines.append("    )")
        lines.append("")

    lines.append("    val ALL: List<FsmMachineDefinition> = listOf(")
    for mid in machine_ids:
        lines.append(f"        {safe_enum_name(mid)},")
    lines.append("    )")
    lines.append("")
    lines.append("    private val byId: Map<String, FsmMachineDefinition> = ALL.associateBy { it.id }")
    lines.append("")
    lines.append("    fun get(id: String): FsmMachineDefinition? = byId[id]")
    lines.append("")
    lines.append("    fun require(id: String): FsmMachineDefinition =")
    lines.append(
        '        byId[id] ?: error("Unknown state machine (fail closed): $id")'
    )
    lines.append("}")
    lines.append("")
    write_file(out_dir / "StateMachines.kt", "\n".join(lines))


def gen_catalog_manifest(
    canonical_dir: Path,
    state_dir: Path,
    errors_dir: Path,
    sources: dict[str, str],
) -> None:
    """Write a small manifest used by drift checks / diagnostics."""
    lines = [
        GENERATED_HEADER,
        "package com.omnillm.core.canonical.generated",
        "",
        "/** Fingerprints of authority YAML used for this generation. */",
        "object ContractCatalogManifest {",
        "    const val GENERATOR: String = \"tools/codegen/generate_contracts.py\"",
    ]
    for name, digest in sorted(sources.items()):
        key = safe_enum_name(name.upper().replace("-", "_").replace(".", "_"))
        lines.append(f"    const val SRC_{key}: String = {kotlin_string(digest)}")
    lines.append("}")
    lines.append("")
    write_file(canonical_dir / "ContractCatalogManifest.kt", "\n".join(lines))


# ---------------------------------------------------------------------------
# Orchestration
# ---------------------------------------------------------------------------


def generate_all(repo_root: Path, dest_root: Path | None = None) -> list[Path]:
    root = dest_root if dest_root is not None else repo_root
    specs = repo_root / "specs"

    canonical = load_yaml(specs / "canonical-types.yaml")
    state_machines = load_yaml(specs / "state-machines.yaml")
    errors = load_yaml(specs / "error-catalog.yaml")
    access = load_yaml(specs / "access-control-catalog.yaml")
    capabilities = load_yaml(specs / "capability-catalog.yaml")

    sources = {
        "canonical-types.yaml": file_fingerprint(specs / "canonical-types.yaml"),
        "state-machines.yaml": file_fingerprint(specs / "state-machines.yaml"),
        "error-catalog.yaml": file_fingerprint(specs / "error-catalog.yaml"),
        "access-control-catalog.yaml": file_fingerprint(
            specs / "access-control-catalog.yaml"
        ),
        "capability-catalog.yaml": file_fingerprint(specs / "capability-catalog.yaml"),
    }

    can_dir = (
        root
        / "core"
        / "canonical"
        / "src"
        / "main"
        / "kotlin"
        / "com"
        / "omnillm"
        / "core"
        / "canonical"
        / "generated"
    )
    state_dir = (
        root
        / "core"
        / "state"
        / "src"
        / "main"
        / "kotlin"
        / "com"
        / "omnillm"
        / "core"
        / "state"
        / "generated"
    )
    err_dir = (
        root
        / "core"
        / "errors"
        / "src"
        / "main"
        / "kotlin"
        / "com"
        / "omnillm"
        / "core"
        / "errors"
        / "generated"
    )

    gen_canonical_encoding(can_dir)
    gen_digest_types(can_dir)
    gen_enums(canonical, can_dir)
    gen_resource_vector(canonical, can_dir)
    gen_access_control(access, can_dir)
    gen_capabilities(capabilities, can_dir)
    gen_catalog_manifest(can_dir, state_dir, err_dir, sources)
    # OmniResult depends on errors module — generate after errors types exist
    gen_errors(errors, err_dir)
    gen_omni_result(can_dir)
    gen_state_machines(state_machines, state_dir)

    written: list[Path] = []
    for base in (can_dir, state_dir, err_dir):
        if base.exists():
            written.extend(sorted(base.rglob("*.kt")))
    return written


def collect_generated_rel_paths(repo_root: Path) -> list[Path]:
    rels = [
        Path("core/canonical/src/main/kotlin/com/omnillm/core/canonical/generated"),
        Path("core/state/src/main/kotlin/com/omnillm/core/state/generated"),
        Path("core/errors/src/main/kotlin/com/omnillm/core/errors/generated"),
    ]
    files: list[Path] = []
    for rel in rels:
        d = repo_root / rel
        if d.is_dir():
            for p in sorted(d.rglob("*.kt")):
                files.append(p.relative_to(repo_root))
    return files


def check_drift(repo_root: Path) -> int:
    with tempfile.TemporaryDirectory(prefix="omnillm-codegen-") as tmp:
        tmp_root = Path(tmp)
        # Mirror only the paths we write; generate_all uses dest_root structure.
        generate_all(repo_root, dest_root=tmp_root)

        committed = collect_generated_rel_paths(repo_root)
        generated = collect_generated_rel_paths(tmp_root)

        committed_set = set(committed)
        generated_set = set(generated)

        missing = sorted(generated_set - committed_set)
        extra = sorted(committed_set - generated_set)
        diffs: list[str] = []

        for rel in sorted(committed_set & generated_set):
            a = (repo_root / rel).read_text(encoding="utf-8").replace("\r\n", "\n")
            b = (tmp_root / rel).read_text(encoding="utf-8").replace("\r\n", "\n")
            if a != b:
                ud = difflib.unified_diff(
                    a.splitlines(),
                    b.splitlines(),
                    fromfile=f"committed/{rel.as_posix()}",
                    tofile=f"generated/{rel.as_posix()}",
                    lineterm="",
                )
                diffs.append("\n".join(list(ud)[:80]))

        if not missing and not extra and not diffs:
            print("Contract drift check: OK (generated sources match catalogs).")
            return 0

        print("Contract drift check: FAILED", file=sys.stderr)
        print(
            "Catalogs and committed generated Kotlin diverged. "
            "Run: ./gradlew generateContracts",
            file=sys.stderr,
        )
        if missing:
            print("Missing committed files:", file=sys.stderr)
            for m in missing:
                print(f"  + {m.as_posix()}", file=sys.stderr)
        if extra:
            print("Extra committed files (not produced by generator):", file=sys.stderr)
            for e in extra:
                print(f"  - {e.as_posix()}", file=sys.stderr)
        for d in diffs:
            print(d, file=sys.stderr)
        return 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="OmniLLM formal contract codegen")
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=None,
        help="Repository root (default: parent of tools/)",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Fail if committed generated sources drift from catalogs",
    )
    args = parser.parse_args(argv)

    script_path = Path(__file__).resolve()
    default_root = script_path.parents[2]  # tools/codegen -> repo root
    repo_root = (args.repo_root or default_root).resolve()

    if not (repo_root / "specs" / "canonical-types.yaml").is_file():
        print(f"specs not found under {repo_root}", file=sys.stderr)
        return 2

    if args.check:
        return check_drift(repo_root)

    written = generate_all(repo_root)
    print(f"Generated {len(written)} Kotlin sources under {repo_root}:")
    for p in written:
        print(f"  {p.relative_to(repo_root).as_posix()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
