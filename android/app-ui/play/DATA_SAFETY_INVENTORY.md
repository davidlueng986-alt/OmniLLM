# OmniLLM Data Safety inventory (Play Console)

**Authority:** `SEC-PRIVACY`, `ANDROID-DIST` §4, `FEAT-AI-CONTENT-REPORT`, `specs/retention-policy.yaml`, `specs/platform-policy-register.yaml`  
**Package:** `com.omnillm` (`:android:app-ui`)  
**Companion package (separate):** `com.omnillm.companion` — see `android/companion-sandbox/PACKAGING.md`  
**As-of policy register:** `2026-07-31` (recheck before every Play submission)  
**Status:** Implementation inventory for declaration — **not** a claim that Play Data Safety form is already approved.

This document maps **actual product data classes** to Play Data Safety form answers.  
Do **not** invent collection practices. If a row is “not collected / not shared”, the code path must match.

---

## 1. Product privacy defaults (normative)

| Rule | Product authority | Play implication |
|---|---|---|
| Prompt, output, image/audio content, embeddings **do not leave the device** by default | `SEC-PRIVACY` §1 | Do not declare “App functionality” sharing of user content unless a cloud connector feature is shipped |
| Optional conversation history is **on-device** and user-controlled | `SEC-PRIVACY` §2 | Device storage; not shared |
| Telemetry (if ever enabled) is **opt-in**, aggregated allowlist only, **default off** | `SEC-PRIVACY` §3 | Currently: declare **not collected** until an opt-in pipeline ships |
| AI content report is a **separate stream**, not telemetry; user reviews every field | `SEC-PRIVACY` §7, `FEAT-AI-CONTENT-REPORT` | Declare only if developer report endpoint is configured for the release |
| Diagnostics export requires **explicit user action** | `SEC-PRIVACY` §4 | User-initiated share; not automatic collection |
| Backup of private model/trust/token/DB is **disabled** | `SEC-PRIVACY` §5, manifests `allowBackup=false` | Matches “data not backed up by app backup” |

---

## 2. Data types inventory

For each row: **Purpose**, **Collected?**, **Shared?**, **Ephemeral?**, **Required?**, **User control**.

### 2.1 App activity / diagnostics (operational)

| Data class (product) | Play type (approx.) | Collected | Shared | Purpose | Retention (product) | Notes |
|---|---|---|---|---|---|---|
| Inference request ledger (no prompt) | App activity / Other | On-device | No | App functionality, crash recovery, idempotency | 30 days default (`retention-policy`) | `containsPrompt: false` |
| Command claim/result ledger | App activity | On-device | No | App functionality | 30 days after terminal | Digests / states only |
| Job events (download/import) | App activity | On-device | No | App functionality | 30 days after terminal | |
| Request traces (redacted) | App info and performance | On-device | No | Analytics **disabled by default**; local diagnostics only | 7 days | No raw prompt/token/path |
| Metrics rollup | App info and performance | On-device | No | Dashboard / local monitoring | 90 days | Method version required |
| Crash / diagnostic events (local) | App info and performance | On-device | No | Diagnostics | Until export or cleanup | Redacted |
| Diagnostic export bundle | Files and docs (user-created) | Only if user exports | Only if user **shares** | Support / debug (user-initiated) | Temporary file, default 24h | Random ID; optional encryption |
| Compatibility evidence | Device or other IDs (device fingerprint class) | On-device | No | App functionality (routing/qualification) | Until profile expiry | Not a Play “advertising ID” |
| Security audit events | App activity | On-device | No | Security | 90 days local | Principal pseudonymization |
| License events | App activity | On-device | No | Legal / license | Append-only while required | Content-addressed text |

### 2.2 Files and media

| Data class | Play type | Collected | Shared | Purpose | Notes |
|---|---|---|---|---|---|
| Model artifacts (weights / revisions) | Files and docs | On-device (user install/download/SAF) | No by default | App functionality | Not shipped inside AAB; acquired via catalog/SAF (`ANDROID-DIST` §1) |
| Model metadata / license / source | App info | On-device | No | App functionality, compliance | |
| Partial output (optional recovery) | Personal info / Other text | On-device only if policy enables | No | App functionality | `containsPrompt: true`; encrypted; user delete |

### 2.3 Messages / generative content (local)

| Data class | Play type | Collected | Shared | Purpose | Notes |
|---|---|---|---|---|---|
| Prompt / completion text | Messages or Other text | **On-device only** by default | **No** by default | App functionality (playground) | Never default telemetry |
| Embeddings | Other | On-device | No | App functionality | Do not leave device by default |
| Optional conversation history | Messages | On-device if user enables | No | App functionality | User control / delete |

### 2.4 AI content report (user-initiated, separate stream)

| Field | Collected (when user submits) | Shared with developer endpoint | Required for app use | Notes |
|---|---|---|---|---|
| `reportId`, category, timestamps, appBuild | Yes (on submit/queue) | Yes (to configured developer endpoint) | No — optional safety feature | Catalog: `ContentReportPayload` |
| modelRevisionId, engineBuildId, backend, local policy version | Yes | Yes | No | |
| output digest | Yes | Yes | No | Digest only, not full output |
| user locale | Yes | Yes | No | |
| prompt/output **excerpt** | **Only if user opts in** | Only if selected | No | Default exclude full prompt/output |
| free-text description | Only if user enters | Yes | No | Input validation / caps |
| diagnostic summary | Only if user selects | Yes | No | |
| Tokens, private paths, other principal data | **Never** | **Never** | — | Fail closed |

**Stream kind:** `AI_CONTENT_REPORT` ≠ telemetry (`FEAT-AI-CONTENT-REPORT`, `ContentReportPolicy`).  
**Retention (local drafts/queue):** 24h unless submitted/cancelled earlier; encrypted at rest; receipt metadata 30 days without prompt/excerpt (`retention-policy` `ai-content-report-drafts`).  
**Play AI-generated content policy:** in-app report/flag is **required** for generative UI (`PLAY-AI-REPORTING`). Do not satisfy this by external-web-only contact links.

If the **Play release profile** has **no** developer reporting endpoint configured, treat report submit as fail-visible offline queue / endpoint-not-configured — still ship the UI surface; do not silently redirect payloads to telemetry.

### 2.5 Account / identity

| Data class | Collected | Shared | Notes |
|---|---|---|---|
| Google account | No | No | Not required |
| Client registration / tokens (local loopback/LAN) | On-device | No (token never in telemetry) | User-approved clients |
| Android user / profile principal | OS-bound | No | Multi-user isolation (`SEC-PRIVACY` §6) |

### 2.6 Location / contacts / photos / mic / ads

| Category | Status |
|---|---|
| Approximate / precise location | **Not collected** (no permission declared for location) |
| Contacts | **Not collected** |
| Photos / videos (as a gallery product) | **Not collected** by default; multimodal playground may process **user-selected** assets **on-device** only |
| Microphone / voice | **Not collected** by default; only if multimodal feature requests runtime permission later — re-open privacy review |
| Advertising ID / ads | **Not used** |
| Web browsing history | **Not collected** |

---

## 3. Permissions ↔ Data Safety mapping

| Permission | Why present | Data Safety note |
|---|---|---|
| `INTERNET` | Model download/import; AI content report HTTPS submit; optional LAN/server features | Network access ≠ automatic data sharing |
| `ACCESS_NETWORK_STATE` | Offline report queue / transfer gating | Not personal data |
| `FOREGROUND_SERVICE` | Base FGS | Declare FGS use in Play Console (not Data Safety) |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Inference control-plane FGS | Play FGS declaration form |
| `FOREGROUND_SERVICE_DATA_SYNC` | User-started transfer FGS | Play FGS declaration form |
| `POST_NOTIFICATIONS` | FGS notification (API 33+) | Runtime permission UX |
| `com.omnillm.permission.BIND_RUNTIME` | Discovery/filter only | Not a security boundary |
| `com.omnillm.companion.permission.BIND_SANDBOX` | Same-signer bind to companion | Separate package |

Companion (`com.omnillm.companion`) must **not** hold `INTERNET` / contacts / media / broad storage.

---

## 4. Security practices checklist (Play form)

| Practice | OmniLLM posture |
|---|---|
| Data encrypted in transit | TLS required for report endpoint / remote catalog; cleartext disabled except loopback developer server |
| Data encrypted at rest | App-private storage; report queue encrypted; Android filesystem encryption assumed |
| Users can request deletion | Local delete for history/reports/diagnostics; submitted reports follow developer retention disclosure |
| Independent security review | Not claimed here — do not check unless evidence exists |
| Committed to Play Families / etc. | N/A unless product opts in |

---

## 5. Data sharing summary (honest)

| Destination | What | When |
|---|---|---|
| **No automatic third party** | — | Default local inference |
| **Developer content-report endpoint** (allowlisted) | Minimal `ContentReportPayload` after consent | User completes in-app report flow |
| **Model catalog / download hosts** (user-started) | Model bytes / metadata | User initiates acquire |
| **User-chosen share target** (system sheet) | Diagnostic bundle | User exports + shares |
| **LAN / loopback clients** | API traffic per user-approved registration | Explicit server/LAN enable |

Do **not** declare advertising, fraud analytics vendors, or “AI training with user content” unless a separate feature + consent ships.

---

## 6. Play Console Data Safety form — suggested answers (current skeleton)

> Recheck when features land. Skeleton release defaults:

| Question | Suggested answer |
|---|---|
| Does your app collect or share user data? | **Yes** if report endpoint or diagnostic share is in the build; else evaluate on-device-only carefully — Play still expects disclosure of on-device collected categories when encrypted and not shared if you “collect”. Prefer accurate on-device collection disclosure for history/metrics even when not shared. |
| Is all user data encrypted in transit? | **Yes** for network paths that leave the device (HTTPS). |
| Do you provide a way for users to request deletion? | **Yes** (in-app discard/delete for reports, history, diagnostics; document developer endpoint retention for submitted reports). |
| UGC / AI-generated content | App **generates** AI content on-device; **in-app reporting** available (`Content report` + per-result action). |
| Children | Follow product legal decision; do not claim Families without review. |

---

## 7. Evidence links (repo)

| Artifact | Path |
|---|---|
| Policy register | `specs/platform-policy-register.yaml` |
| Retention | `specs/retention-policy.yaml` |
| Backup exclusion | `android/app-ui/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml` |
| Network TLS | `android/app-ui/src/main/res/xml/network_security_config.xml` |
| Report UI | `android/app-ui/src/main/kotlin/.../ContentReportScreen.kt`, Playground report actions |
| Report policy code | `features/ai-content-report/.../ContentReportPolicy.kt` |
| Manual release gates | `gradle/RELEASE_CHECKLIST.md` |

---

## 8. Explicit non-claims

- This inventory is **not** a signed privacy policy PDF.
- This inventory is **not** proof that a real AAB passed Play review (`platform-policy-register` rules).
- No fabricated qualification benchmarks or telemetry volumes.
- Unverified policy status **fails closed** for publication claims.
