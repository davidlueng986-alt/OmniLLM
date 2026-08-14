# Adversarial verify — Claim #3

**Claim:** No engine cell projects `SUPPORTED` without qualification evidence  
**Auditor:** independent (fail-closed); not relying on prior `09_engines.md` alone  
**Date:** 2026-08-12  
**Docs package:** `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents`  
**Implementation:** `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android`  
**Out dir:** `audit-reports/13_verify_3.md`

---

## Verdict

```yaml
claim_id: 3
claim: "No engine cell projects SUPPORTED without qualification evidence"
real: true
```

**Scope of “engine cell”:** `EngineQualificationCell` rows (capability-matrix seed / registry `putCell`) and their projection via `EngineRegistry.projectRuntimeCapability` / `resolveCapability`.  
**Out of scope (does not falsify claim):** feature-level `CapabilityPort` fakes (e.g. dashboard `AllSupportedCapabilityPort`), HTTP/test fixtures, and product-capability negotiation that projects `CONDITIONAL` for exploratory execute.

---

## reason

1. **Normative rule (docs + monorepo specs)** requires that only a non-expired evidence `PASS` under `QUALIFIED_WITH_ENVELOPE` may project runtime `SUPPORTED`; `UNQUALIFIED` must never project as `SUPPORTED`.
2. **All five catalog engines** in monorepo `specs/engine-qualification-status.yaml` and every per-pack `capability-matrix.yaml` cell are `qualificationStatus: UNQUALIFIED` + `evidenceStatus: NOT_EXECUTED` (or pack-level `registryExposure: UNKNOWN` / `runtimeCapabilityDefault: UNKNOWN`). Grep found **zero** matrix/YAML rows with `qualificationStatus: QUALIFIED*`, `evidenceStatus: PASS`, or `registryExposure: SUPPORTED` under `engines/` or monorepo `specs/engine-qualification-status.yaml`.
3. **Production seed paths** (`seedUnqualifiedPlaceholders` / `seedUnqualified` / `seedUnqualifiedCells`) hard-code `UNQUALIFIED` + `NOT_EXECUTED` only — no main-source assignment of `QUALIFIED_WITH_ENVELOPE` or `EvidenceStatusLabels.PASS` to cells.
4. **Projection gate** in `EngineRegistry` returns `SUPPORTED` **only** when both labels match; missing cell → `UNKNOWN`; `UNQUALIFIED` even with `PASS` evidence label → `UNKNOWN`.
5. **Dev ship execute path** widens to `CONDITIONAL` (not plain `SUPPORTED`) and documents that explicitly (COR-10). Attach assert in compliance mode fails if any cell would project `SUPPORTED` without evidence.

Therefore the claim holds for **engine cells** with concrete path evidence. Residual process gaps (label-only evidence, non-engine capability fakes) are documented below and do not show a seeded engine cell projecting `SUPPORTED` without qualification labels.

---

## evidence

### E1 — Docs package: dual-status + no fake SUPPORTED

| Path | Quote / fact |
|---|---|
| `…\OmniLLM_Product_Documents\specs\engine-qualification-status.yaml` L2 | `rule: A design may be BASELINE while every runtime cell remains UNQUALIFIED; only a non-expired PASS record can project SUPPORTED` |
| same L58 | `UNQUALIFIED never projects as SUPPORTED` |
| same L3–33 | All five engines: `qualificationStatus: UNQUALIFIED`, `runtimeCapabilityDefault: UNKNOWN` |
| `…\docs\80-engines\qualification-status-and-evidence.md` L18–21 | Pre-build packs may be `BASELINE + UNQUALIFIED`; “不允許 runtime 宣稱 supported”; only `QUALIFIED_WITH_ENVELOPE` may enter supported registry |
| same L23–24 | Product docs do not embed device/build evidence packages |

### E2 — Monorepo SSOT status (implementation)

| Path | Quote / fact |
|---|---|
| `omnillm-android\specs\engine-qualification-status.yaml` L2 | Same rule string as docs package |
| same L10–12, 22–24, 35–37, 55–57, 70–72 | Each engine: `qualificationStatus: UNQUALIFIED`, `registryExposure: UNKNOWN`, `runtimeCapabilityDefault: UNKNOWN` |
| same L7–8, 16–17, 49, 77 | Explicit notes: complete lock / INTEGRATED ≠ SUPPORTED; do not mark QUALIFIED/SUPPORTED |
| same L103 | `UNQUALIFIED never projects as SUPPORTED` |
| Grep `qualificationStatus: QUALIFIED\|evidenceStatus: PASS\|registryExposure: SUPPORTED` under `engines/` and monorepo `specs/` | **No matches** |

### E3 — Capability matrices: all cells UNQUALIFIED / NOT_EXECUTED

| Pack matrix | Pack-level | Sample cell |
|---|---|---|
| `engines/llama-cpp/capability-matrix.yaml` | L13–15 `UNQUALIFIED` / `UNKNOWN` | L152–153 cells `UNQUALIFIED` + `NOT_EXECUTED` (all listed cells same) |
| `engines/litert-lm/capability-matrix.yaml` | L18 `UNQUALIFIED` | L171–172 etc. all `UNQUALIFIED` + `NOT_EXECUTED` |
| `engines/mlc-llm/capability-matrix.yaml` | L13 `UNQUALIFIED` | L159–160 etc. |
| `engines/mllm/capability-matrix.yaml` | L17 `UNQUALIFIED` | L169–170 etc. |
| `engines/ort-genai/capability-matrix.yaml` | UNQUALIFIED pack | L188–189 etc. |

`runtimeDefault:` values observed are `UNKNOWN` only (no `SUPPORTED` runtimeDefault in matrices).

### E4 — Registry projection (code authority)

```91:103:C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\engines\api\src\main\kotlin\com\omnillm\engines\api\EngineRegistry.kt
    fun projectRuntimeCapability(
        qualificationStatus: String,
        evidenceStatus: String = EvidenceStatusLabels.DEFAULT,
    ): CapabilityState {
        if (qualificationStatus != EngineQualificationCellStatus.QUALIFIED_WITH_ENVELOPE) {
            return CapabilityState.UNKNOWN
        }
        return when (evidenceStatus) {
            EvidenceStatusLabels.PASS -> CapabilityState.SUPPORTED
            EvidenceStatusLabels.FAIL -> CapabilityState.UNSUPPORTED
            // NOT_EXECUTED, EXPIRED, INVALIDATED, or anything else ⇒ fail closed to UNKNOWN
            else -> CapabilityState.UNKNOWN
        }
    }
```

- Missing cell: `resolveCapability` → `UNKNOWN` (L78–80).
- Unit test enforces gate: `EngineRegistryTest.onlyQualifiedWithEnvelopeAndPass_isSupported` — `UNQUALIFIED`+`PASS` still `UNKNOWN`; only both labels → `SUPPORTED`.
- Unit test: `QUALIFIED_WITH_ENVELOPE` + `NOT_EXECUTED`/`EXPIRED` → `UNKNOWN`; + `FAIL` → `UNSUPPORTED`.

### E5 — Seeds never mint SUPPORTED

| Module | Evidence |
|---|---|
| `engines/llama-cpp/.../LlamaCppModule.kt` L164–165 | `qualificationStatus = UNQUALIFIED`, `evidenceStatus = NOT_EXECUTED` |
| `engines/litert-lm/.../LitertLmModule.kt` (seedUnqualifiedPlaceholders) | same pattern (grep main) |
| `engines/mllm/.../qualification/QualificationCells.kt` L99–100 | same |
| `engines/mlc-llm/.../QualificationCells.kt` | comments + seed UNQUALIFIED only |
| `engines/ort-genai/.../OrtGenaiModule.kt` seed | UNQUALIFIED cells |
| Grep `qualificationStatus = EngineQualificationCellStatus.QUALIFIED` / `EvidenceStatusLabels.PASS` under `**/main/**/*.kt` | **No matches** (PASS/QUALIFIED only in tests + comments) |

### E6 — Control-plane honesty: execute may be CONDITIONAL, not SUPPORTED

| Path | Quote / fact |
|---|---|
| `EngineExecuteBinding.kt` L124–125, L180–183 | Dev mode: bound engine projects **CONDITIONAL** (never plain SUPPORTED) for generation caps |
| `EngineSelectionPolicy.kt` L23–25, L63–66 | Policy never claims `SUPPORTED` without evidence; `anyExecutableCell` in dev means “executable”, not “projects SUPPORTED” |
| `EngineSelectionPolicy.projectsSupported` L87–89 | `QUALIFIED_WITH_ENVELOPE && PASS` only |
| `EnginePackAttachment.kt` L95–98 | Compliance attach: `check(!anyExecutableCell…)` → “must not project SUPPORTED without qualification evidence” |

### E7 — Docs-package PASS that is **not** an engine cell

| Path | Fact |
|---|---|
| `…\specs\quality-scenarios.yaml` Q-017 L116–126 | `evidenceStatus: PASS` for **documentation** repository lint (`tools/validate_repository.py`) — area `documentation`, not engine qualification cell |

---

## counter_evidence

Examined and **rejected as falsifiers** of the claim (scoped to engine cells):

| Candidate | Why not a false SUPPORTED engine cell |
|---|---|
| **Dev / exploratory execute** (`EngineExecuteBinding` → `CONDITIONAL`) | Projects `CONDITIONAL` + conditions like `development_ship_mode`, not `CapabilityState.SUPPORTED`. Allows execute without PASS packs; does **not** advertise engine cell SUPPORTED. |
| **`allowSupportedProjectionWithoutPass()`** (`ProductBuildMode.kt`) | In DEVELOPMENT_SHIP_MODE, skips attach assert that zero cells project SUPPORTED; does **not** write SUPPORTED cells. Name is about allowing attach when “executable” without qualification, not inventing SUPPORTED projection. |
| **INTEGRATED / LOCKED notes** in `engine-qualification-status.yaml` | Explicitly “NOT a SUPPORTED claim”; cells remain UNQUALIFIED. |
| **16 KB / AAR digests in evidenceNotes** | Supply-chain / packaging notes; not `evidenceStatus: PASS` on phase cells. |
| **Unit tests putting QUALIFIED+PASS** (`EngineRegistryTest`, attach tests) | Construct cells to prove the gate; not production seed or shipped matrix. |
| **Feature ports returning SUPPORTED** (e.g. `DashboardPorts.AllSupportedCapabilityPort`, routing test fakes) | Product/feature capability IDs — **not** `EngineQualificationCell` projection. Adjacent honesty risk; does not place an engine matrix cell into SUPPORTED. |
| **Q-017 PASS** | Documentation integrity scenario, not engine backend×device×model cell. |

No counter-example found of a **seeded or production-path engine cell** projecting `SUPPORTED` while `qualificationStatus ≠ QUALIFIED_WITH_ENVELOPE` or `evidenceStatus ≠ PASS`.

---

## residual

1. **Label-only evidence:** `EngineRegistry.putCell` accepts `QUALIFIED_WITH_ENVELOPE` + `PASS` without verifying an on-disk evidence package (build-log, device profile, etc. per schema `qualificationEvidence`). A compromised or mistaken caller could mint SUPPORTED by labels alone. **Today:** no main production caller does this; seeds refuse it. Process control still required at Stage 5.
2. **Scope boundary:** Claim is true for **engine cells**. Adjacent systems may still show UI “supported-ish” operability via feature capability ports or CONDITIONAL exploratory execute — do not conflate with engine registry SUPPORTED.
3. **No device PASS packs in repo:** Absence of false SUPPORTED is expected given UNQUALIFIED/NOT_EXECUTED; this verify does **not** invent device/OEM PASS results.
4. **Search completeness:** Grepped `engines/**` matrices, monorepo `specs/engine-qualification*`, main Kotlin seed/registry/execute paths, docs package qualification YAML/MD, quality-scenarios. Empty positive hits for false SUPPORTED cells is intentional and documented.

---

## methods (what was grepped/read)

| Action | Targets |
|---|---|
| Read | docs `engine-qualification-status.yaml`, `engine-qualification-schema.yaml`, `qualification-status-and-evidence.md`; monorepo same specs; `EngineRegistry.kt`, `EngineQualificationLabels.kt`, `EngineSelectionPolicy.kt`, `EngineExecuteBinding.kt`, `EnginePackAttachment.kt` (attach assert); `LlamaCppModule.seed*`, `mllm QualificationCells`; sample matrix heads/cells for all 5 packs; `EngineRegistryTest.kt`; Q-017 quality scenario |
| Grep | `SUPPORTED\|QUALIFIED\|qualification` under engines + specs; `QUALIFIED_WITH_ENVELOPE\|seedUnqualified\|projectRuntimeCapability` under engines/api + runtime-service; `evidenceStatus: PASS\|qualificationStatus: QUALIFIED\|registryExposure: SUPPORTED` under engines/ and monorepo specs (zero); main-source `EvidenceStatusLabels.PASS` assignment (zero); docs package QUALIFIED/PASS (schema + Q-017 only) |

---

## conclusion

**`real: true`** — With path evidence above, no engine qualification cell in the monorepo seed/matrix/status SSOT projects runtime `SUPPORTED` without the required qualification evidence labels (`QUALIFIED_WITH_ENVELOPE` + non-expired `PASS`). The registry projection and seed paths enforce fail-closed honesty; exploratory product use uses `CONDITIONAL`, not false engine-cell `SUPPORTED`.
