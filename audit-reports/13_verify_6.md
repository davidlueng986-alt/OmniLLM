# Adversarial verification — Claim #6

| Field | Value |
|---|---|
| **Claim** | Companion sandbox is different package/UID and not same-UID security sandbox |
| **real** | **true** |
| **Auditor** | Independent re-read of NEW docs package + monorepo (not prior audit prose alone) |
| **Date** | 2026-08-12 |
| **Out path** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\audit-reports\13_verify_6.md` |

---

## Verdict

**real: true** — concrete path evidence shows:

1. Product design (NORMATIVE) requires different package / different Linux UID for the companion, and forbids treating same-UID workers as a security sandbox.
2. Implementation ships companion as a **separate `android.application` APK** with `applicationId = "com.omnillm.companion"` vs main `"com.omnillm"`.
3. No `android:sharedUserId` appears in any `AndroidManifest.xml` under the monorepo (only a comment forbidding it).
4. Host placement / package probe **hard-fails** when companion UID equals main UID or companion is missing for untrusted acceleration; policy never maps `EXTERNAL_UID_ACCELERATED` onto same-UID workers.

This is a **software-architecture / packaging** claim (L1 module + L2 control-plane gate). It is **not** a claim that multi-UID device adversarial Q-008 was executed end-to-end (that remains residual; see below).

---

## reason

Android assigns a distinct Linux UID per installed applicationId unless `sharedUserId` unifies them. OmniLLM:

- Declares companion as a **different applicationId** and forbids `sharedUserId` in docs and packaging.
- Implements companion as a **standalone application module**, not a library merged into the main APK process graph for untrusted accel.
- Explicitly labels **same-UID** units (`:engine_worker`, `:runtime`) as **crash containment only**, and gates untrusted accelerated placement to `EXTERNAL_UID_ACCELERATED` **only when** companion is installed, different UID, same-signer, protocol-compatible.

Therefore the companion sandbox is **by design and by code packaging** a different package/UID boundary, **not** a same-UID “security sandbox.”

---

## evidence

### A. Product docs (NEW package, NORMATIVE)

| Source | Quote / rule |
|---|---|
| `...\OmniLLM_Product_Documents\docs\50-security-reliability\external-sandbox-companion.md` (SEC-EXTERNAL-SANDBOX) | “同UID worker無法保護App private data…”; “以**不同package、不同Linux UID**的optional companion承載此路徑”; “不使用sharedUserId或shared storage”; missing companion → `TRUST_PLACEMENT_REQUIRED`, “而不是退回same-UID「高效能沙箱」” |
| `...\OmniLLM_Product_Documents\governance\adr\ADR-007.md` | Title/decision: “不受信任加速執行使用 different package/UID”; alternative rejected: “同 UID remote process” |
| `...\OmniLLM_Product_Documents\docs\20-architecture\process-trust-topology.md` | Table: `:engine_worker` = **App UID**, “不能視為 security sandbox”; **Companion Sandbox App** = “不同 package／UID”; §2: same-UID only crash containment; untrusted accelerated path must use different package/UID companion |
| `...\OmniLLM_Product_Documents\docs\20-architecture\architecture-invariants.md` | `INV-009`: untrusted native accelerated execution must not share writable UID with privileged app data/secrets |
| `...\OmniLLM_Product_Documents\docs\00-product\product-modes.md` | “同 UID worker 不能構成安全邊界，不受信任 accelerated inference 必須使用不同 package／UID 的 companion sandbox” |

### B. Packaging / package identity (implementation L1)

| Path | Evidence |
|---|---|
| `android/companion-sandbox/build.gradle.kts` | Plugin: `android.application`; `applicationId = "com.omnillm.companion"`; comment: “Different package / Linux UID from main app (com.omnillm)… No sharedUserId, no shared storage”; “never merge into com.omnillm AAB” |
| `android/app-ui/build.gradle.kts` | Main: `applicationId = "com.omnillm"` |
| `android/companion-sandbox/src/main/AndroidManifest.xml` | Header: “applicationId = com.omnillm.companion … never sharedUserId with main app”; signature permission `com.omnillm.companion.permission.BIND_SANDBOX`; service exported under that permission only |
| `android/companion-sandbox/PACKAGING.md` | Package/UID different from `com.omnillm`; `sharedUserId` **Forbidden**; do not merge companion so code runs under `com.omnillm` UID |
| `android/companion-sandbox/src/main/kotlin/.../CompanionSandboxModule.kt` | `PACKAGE_NAME = "com.omnillm.companion"`; `MAIN_APP_PACKAGE = "com.omnillm"`; comment “no sharedUserId” |
| `settings.gradle.kts` | Includes `":android:companion-sandbox"` as its own module |
| Grep `sharedUserId` over `**/AndroidManifest.xml` | **Only** the companion manifest **comment** forbidding it — **no** `android:sharedUserId` attribute anywhere |

### C. Contrast: same-UID worker is *not* the companion

| Path | Evidence |
|---|---|
| `android/workers/build.gradle.kts` | `android.library` (merged into host APK), **not** separate applicationId |
| `android/workers/src/main/AndroidManifest.xml` | `android:process=":engine_worker"`; comment: “shares App UID with :runtime — crash isolation only, NOT a security sandbox. Untrusted accelerated inference must use companion” |
| `android/workers/.../WorkerCommandGate.kt` | Rejects non-allowed placements with `TRUST_PLACEMENT_REQUIRED` / “not allowed in same-UID worker” |

### D. Host control plane enforces different UID (L2)

| Path | Evidence |
|---|---|
| `android/runtime-service/.../CompanionPackageProbe.kt` | Compares `companionInfo.uid` vs `mainUid`; if equal: `detail = "companion UID equals main app UID; sharedUserId forbidden"` and `differentUid = false` |
| `android/runtime-service/.../CompanionAvailability.kt` | `isAvailable = installed && differentUid && sameSigner && protocolCompatible`; comments: host never treats same-UID as security sandbox substitute |
| `android/runtime-service/.../CompanionHostConstants` (same file) | `MAIN_APP_PACKAGE = "com.omnillm"`; `COMPANION_PACKAGE = "com.omnillm.companion"`; host must not merge companion as library |
| `android/runtime-service/.../CompanionPlacementGate.kt` | “Never rewrite EXTERNAL_UID_ACCELERATED to same-UID worker”; on missing companion: “never same-UID security sandbox fallback” |
| `android/runtime-service/.../CompanionHostClient.kt` | Missing companion / bind failure ⇒ unavailable / `TRUST_PLACEMENT_REQUIRED`, “Never returns same-UID security sandbox when companion is missing” |
| `engines/api/.../TrustPlacementPolicy.kt` | “Untrusted accelerated inference **must** use different-package/UID companion. Same-UID workers are crash containment only”; `allowedInSameUidWorker` only for `PRIVILEGED_TRUSTED` / `CRASH_CONTAINED_TRUSTED` (not `EXTERNAL_UID_ACCELERATED`) |
| `engines/api/src/test/.../TrustPlacementPolicyTest.kt` | Asserts `allowedInSameUidWorker(EXTERNAL_UID_ACCELERATED) == false`; untrusted+accelerator without companion → `TRUST_PLACEMENT_REQUIRED` |

### E. Policy unit tests on companion module

| Path | Evidence |
|---|---|
| `android/companion-sandbox/src/test/.../CompanionIsolationPolicyTest.kt` | `companionPackageDiffersFromMainApp`; documents no `sharedUserId`; `companionMustNotClaimSameUidSecuritySandboxRole` ties placement to `EXTERNAL_UID_ACCELERATED` |

### F. UX / product copy (consistency, not sole proof)

| Path | Evidence |
|---|---|
| `android/app-ui/src/main/res/values/strings.xml` | `placement_same_uid`: crash isolation only, shares app account; `placement_companion`: separate install unit / system account |

---

## counter_evidence

Searched for evidence that would **falsify** the claim (companion is actually same-UID or claimed as same-UID security sandbox):

| Probe | Result |
|---|---|
| `sharedUserId` in any `AndroidManifest.xml` | **Absent** as attribute (comment-only forbid) |
| Companion as `android.library` merged into main for sandbox execution | **No** — companion uses `android.application` with its own `applicationId` |
| Host accepting `EXTERNAL_UID_ACCELERATED` when `differentUid=false` | **No** — `isAvailable` requires `differentUid`; probe fails closed on UID equality |
| Policy mapping untrusted accel → same-UID worker | **No** — explicit hard rules + unit tests reject it |
| Workers module rebranded as security sandbox | **No** — manifests/comments state crash-only; gates reject external/isolated placements |

**No counter-evidence found** that the companion sandbox is implemented or documented as a same-UID security sandbox.

---

## residual

| Residual | Impact on claim |
|---|---|
| Full dual-APK multi-UID **device** adversarial suite (Q-008: companion process cannot open main CE/DE private paths / DB / token vault / Keystore) is documented as plan in `CompanionIsolationPolicyTest` KDoc and prior CI audit notes as **not fully executed in-repo** | Does **not** falsify packaging/UID identity; weakens **runtime isolation proof**, not the “different package/UID vs same-UID sandbox” definition claim |
| `injectBoundBinderForTests` same-process test hook in `CompanionHostClient` | Test-only; does not change production applicationId/UID packaging |
| Debug `applicationIdSuffix` → `com.omnillm.debug` / `com.omnillm.companion.debug` | Still **two packages**; `companionPackageForMain` maps debug↔debug |
| Residual driver/kernel/DoS risks (docs §9) | Docs already state different UID is not absolute hardware safety; orthogonal to same-UID vs different-UID claim |

---

## Search / read log (fail-closed discipline)

**Docs grepped/read:** `external-sandbox-companion.md`, `ADR-007.md`, `process-trust-topology.md`, `architecture-invariants.md` (INV-009), product-modes snippet via repo grep.

**Code grepped:** `companion`, `sandbox`, `sharedUserId`, `applicationId`, `same.?UID`, `EXTERNAL_UID`, `allowedInSameUidWorker`, `BIND_SANDBOX` under monorepo and docs package.

**Key files read:** companion `build.gradle.kts`, `AndroidManifest.xml`, `PACKAGING.md`, `CompanionSandboxModule.kt`, `CompanionIsolationPolicyTest.kt`; app-ui `build.gradle.kts` (applicationId); workers `build.gradle.kts` + manifest + `WorkerCommandGate.kt`; runtime `CompanionPackageProbe.kt`, `CompanionAvailability.kt`, `CompanionPlacementGate.kt`, `CompanionHostClient.kt` (partial); `TrustPlacementPolicy.kt` + test.

**Empty negative finding:** zero `android:sharedUserId` attributes in manifests — documented as supporting evidence, not an absence of search.

---

## Mapping note (levels)

| Level | Status for this claim |
|---|---|
| **L1** companion module exists as separate package | **Supported** (`:android:companion-sandbox`, `com.omnillm.companion`) |
| **L2** control plane treats it as different-UID / not same-UID sandbox | **Supported** (probe, availability, placement gate, policy) |
| **L3** product journey + device multi-UID adversarial complete | **Out of scope for “real” on this claim**; residual only |

---

## Final line

```yaml
claim: "Companion sandbox is different package/UID and not same-UID security sandbox"
real: true
```
