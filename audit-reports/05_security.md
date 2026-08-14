# 05 — SECURITY & RELIABILITY Audit

| Field | Value |
|-------|--------|
| **Artifact** | `05_security.md` |
| **Auditor role** | Independent security/reliability auditor |
| **Audit date (host)** | 2026-08-12 |
| **Docs package (authority)** | `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents` |
| **Specs precedence** | `specs/security-profile.yaml`, `specs/access-control-catalog.yaml`, `specs/security-control-catalog.yaml` over prose when conflict |
| **Monorepo (under audit)** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android` |
| **Out dir** | `...\omnillm-android\audit-reports` |
| **Method** | `list_dir` / `read_file` / `grep` on real paths only; fail-closed without path evidence |
| **Status vocabulary** | `PASS` \| `PARTIAL` \| `MISSING` \| `N_A` \| `BLOCKED_HUMAN` |
| **Layer model** | **L1** module/types exist · **L2** wired to control plane · **L3** product journey software-complete |

> **Hard rules observed:** no invented device PASS / Play upload / OEM matrix results; no engine QUALIFIED claim without qualification evidence; claims without path evidence are invalid.

---

## 0) Authority sources read

| ID / path | Role | Read |
|---|---|---|
| `docs/50-security-reliability/README.md` | Nav | Yes |
| `docs/50-security-reliability/auth-network-secrets.md` (`SEC-AUTH-NET`) | Principal, token, LAN, Secret Broker | Yes |
| `docs/50-security-reliability/external-sandbox-companion.md` (`SEC-EXTERNAL-SANDBOX`) | Different UID companion | Yes |
| `docs/50-security-reliability/failure-recovery-idempotency.md` (`REL-RECOVERY`) | Ledgers, uncertain commit | Yes |
| `docs/50-security-reliability/input-abuse-protection.md` (`SEC-INPUT`) | JSON/URL/PFD/output abuse | Yes |
| `docs/50-security-reliability/privacy-telemetry.md` (`SEC-PRIVACY`) | Redaction, telemetry, backup | Yes |
| `docs/50-security-reliability/security-profile.md` (`SEC-PROFILE`) | Crypto profile | Yes |
| `docs/50-security-reliability/trust-runtime-placement.md` (`SEC-PLACEMENT`) | Placement classes | Yes |
| `docs/50-security-reliability/threat-model.md` (`SEC-THREAT`) | Threats/controls | Yes |
| `docs/50-security-reliability/model-supply-chain.md` (`SEC-SUPPLY`) | Catalog root / reverify | Yes |
| `specs/security-profile.yaml` | Machine profile OMNILLM-SECURITY-PROFILE-2 | Yes |
| `specs/access-control-catalog.yaml` | Principals / scopes / profiles | Yes |
| `specs/security-control-catalog.yaml` | SEC-001…SEC-014 | Yes |

---

## 1) Executive summary

| Area | Overall | L1 | L2 | L3 (software journey) | Headline |
|---|---|---|---|---|---|
| Principal model | **PARTIAL** | PASS | PARTIAL | PARTIAL | Observed UID/user + catalog scopes; AIDL third-party approval + durable registration incomplete; BIND_RUNTIME is signature-only |
| Secret Broker | **PASS** (L2 control-plane) | PASS | PASS | PARTIAL | Vault + Keystore-wrapped master + SQLite verifiers on plane; worker/companion denied by design |
| Companion different UID | **PARTIAL** | PASS | PARTIAL | PARTIAL | Separate `com.omnillm.companion` APK + ticket/HMAC + placement gate; full device product journey / Play multi-package not claimed |
| Placement | **PASS** (policy) / **PARTIAL** (product load path) | PASS | PARTIAL | PARTIAL | Pure `TrustPlacementPolicy` + host gate; fail closed without companion for untrusted accel |
| Recovery / idempotency | **PARTIAL** | PASS | PARTIAL | PARTIAL | Durable commit/request/command ledgers + UNCERTAIN; recovery UX / all namespaces uneven |
| Input abuse | **PARTIAL** | PASS | PARTIAL | PARTIAL | JSON budgets + URL/DNS policy + materialize bounds; output-abuse limits not wired into android stream path |
| Privacy / redaction | **PARTIAL** | PASS | PARTIAL | PARTIAL | Redactor + diagnostic allowlist + backup off; outbound telemetry path not product-complete; FLAG_SECURE not evidenced |
| Supply chain / privileged load | **PARTIAL** | PASS | PARTIAL | PARTIAL | Bootstrap/reverify APIs exist; production embedded root + signature verify wiring incomplete |
| Rate limit / quota (SEC-AUTH-NET §6) | **MISSING** | MISSING | MISSING | MISSING | No principal rate-limit / concurrent-quota enforcer found |

**No overall PASS** for SECURITY & RELIABILITY as a product journey: control-plane crypto/ACL/placement policy is strong (many L1/L2 PASS cells), but third-party AIDL approval, rate limits, durable AIDL registrations, supply-chain production roots, and full companion-accelerated journeys remain incomplete.

---

## 2) Principal model

**Docs demand (`SEC-AUTH-NET` §1, `access-control-catalog.yaml`):**  
Principals: `LOCAL_UI`, `ANDROID_APP`, `HTTP_LOOPBACK`, `HTTP_LOCAL_ADMIN`, `HTTP_LAN`, `ISOLATED_WORKER`.  
AIDL principal = **observed calling UID + Android user/profile + ACTIVE ClientRegistration**. Caller package is never principal. Admin binder never exported. Loopback admin tokens never accepted on LAN.

### 2.1 Catalog & enforcer — **PASS** (L1 + unit L2)

| Evidence | Path |
|---|---|
| Generated principal kinds / scopes / profiles / invariants | `core/canonical/.../AccessControlCatalog.kt` — `PrincipalKind`, `AccessScope`, `AccessProfile`, `INVARIANTS` include “caller-supplied package name is never a principal”, “admin binder is never returned by the exported runtime service”, “loopback admin bearer tokens are never accepted by the LAN listener” |
| Enforcer: scope + transport + owner partition + epoch fence | `runtime/policy/.../acl/AccessControlEnforcer.kt` — `authorize()`, `fromAuthenticatedToken()`, `localUiPrincipal()`, LAN loopback-only rejection |
| Tests | `runtime/policy/.../acl/AccessControlEnforcerTest` (compiled test results under `runtime/policy/build/test-results/`) |

**Quote (enforcer contract):**

```17:27:C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\runtime\policy\src\main\kotlin\com\omnillm\runtime\policy\acl\AccessControlEnforcer.kt
/**
 * Principal / ACL enforcement (SEC-AUTH-NET, access-control-catalog.yaml).
 *
 * - Caller-supplied package name is never a principal
 * - Each operation declares exactly one required scope from the catalog
 * - Scope checks on every operation
 * - Revocation epoch fence on every operation (SEC-006)
 * - Transport / profile constraints fail closed
 * - content-reports.review-submit only via LOCAL_UI / non-exported admin
```

### 2.2 Binder observation — **PASS** (L2 observation path)

| Evidence | Path |
|---|---|
| `Binder.getCallingUid()` / userId / package candidates display-only | `android/runtime-service/.../binder/PrincipalObservation.kt` |
| PrincipalId from uid+user, not package | `ClientRegistrationStore.principalIdOf` → `"aidl:uid=…:user=…"` |
| Negative tests (package not principal) | `android/runtime-service/.../security/AidlCallerNegativeSecurityTest.kt` |

**Quote:**

```10:17:C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\runtime-service\src\main\kotlin\com\omnillm\android\runtimeservice\binder\PrincipalObservation.kt
/**
 * Binder principal observation (ANDROID-BINDER §3 / INV-011).
 *
 * Authorization uses **system-provided** calling UID and Android user.
 * Never trust caller self-reported package names as identity.
 *
 * Same-app UI on the non-exported Admin binder is principal [PrincipalKind.LOCAL_UI]
 * with profile LOCAL_ADMIN — not an exported AIDL "same UID" shortcut.
```

### 2.3 Admin vs exported surfaces — **PASS** (L2 topology)

| Evidence | Path |
|---|---|
| `RuntimeBindingService` exported + `permission=com.omnillm.permission.BIND_RUNTIME` (signature) | `android/runtime-service/src/main/AndroidManifest.xml` L71–83 |
| `AdminBindingService` **exported=false** | same file L85–93 |
| Facade docs: no Admin/secret/file APIs | `OmniRuntimeFacade.kt` comment “Does **not** expose Admin, secret broker…” |

### 2.4 AIDL registration durability & third-party approval — **PARTIAL**

| Gap | Evidence |
|---|---|
| `ClientRegistrationStore` is **in-process only**; comment: “Durable DB projection is TODO” | `ClientRegistrationStore.kt` L9–16 |
| Same-app auto-consumes registration; third-party stays PENDING “until Admin approval (**TODO**)” | `OmniBindingFacade.kt` L57–85 |
| Exported bind permission raised to **signature** — third-party apps with different signing keys cannot bind; comment admits third-party pairing PENDING | `AndroidManifest.xml` L40–47 (`BIND_RUNTIME` protectionLevel=signature) |

**Status:** L1 types + observation + same-app path **PASS**; third-party AIDL principal journey **PARTIAL** (approval UI/path + durable CLIENT_REGISTRATION rows not evidenced as complete); signature-level bind is a hardening that currently **blocks** the catalog’s `ANDROID_APP` third-party surface unless same-signer.

### 2.5 HTTP / LAN principals — **PARTIAL** → toward **PASS** for loopback stack

| Evidence | Path |
|---|---|
| Bearer auth result types | `interfaces/http/.../auth/HttpPrincipal.kt` |
| TokenService: HMAC verifier only, LOOPBACK_ONLY / LAN_ONLY constraints | `runtime/policy/.../security/TokenService.kt` |
| Pairing challenge service + LAN TLS identity | `PairingChallengeService.kt`, `LanTlsIdentity.kt` |
| Plane wiring | `ControlPlaneSecurityFactory` → `RuntimeControlPlane.attach` → `GatewayLifecycle` injects `securityStack` |
| LAN approve challenge host | `ControlPlaneLanHost.approveChallenge` |

Not re-audited as full LAN E2E device journey here → L3 stays **PARTIAL**.

**Principal model verdict: PARTIAL**

---

## 3) Secret Broker

**Docs demand (`SEC-AUTH-NET` §5, `SEC-PROFILE` §5, `security-profile.yaml`):**  
Purpose-scoped keys; workers/companion no alias list / no token plaintext; AES-256-GCM records; HMAC-SHA-256 verifiers; constant-time compare; Android Keystore non-exportable.

### 3.1 Interface & vault broker — **PASS** (L1)

| Symbol | Path |
|---|---|
| `SecretBroker` interface (mint/verify/encrypt/pairing proof) | `runtime/policy/.../security/SecretBroker.kt` |
| `VaultSecretBroker` + purposes bootstrap | same file `VaultSecretBroker` |
| `SecurityProfile` pinned to `OMNILLM-SECURITY-PROFILE-2` | `core/ports/.../security/SecurityProfile.kt` — PROFILE_ID, bit sizes, LAN transcript fields |
| Constant-time compare | `CryptoPrimitives.constantTimeEquals` used in `TokenService`, pairing proof, LAN SPKI |
| AES-256-GCM / 96-bit nonce / 128-bit tag | `CryptoPrimitives` + profile constants |

### 3.2 Production control-plane wiring — **PASS** (L2)

| Evidence | Path |
|---|---|
| Keystore-wrapped master + encrypted blob vault + SQLite stores | `android/runtime-service/.../security/ControlPlaneSecurityFactory.kt` |
| Master key alias / blob file | `AndroidKeystoreMasterKey.kt` — `omnillm.secret_broker.master_wrap`, `omnillm_secret_broker_master.bin` |
| Plane attach creates stack | `RuntimeControlPlane.kt` — `ControlPlaneSecurityFactory.createSecurityStack` |
| Token/pairing/content-report consume broker | `TokenService`, `PairingChallengeService`, `DurableContentReportStore` (`RECORD_TYPE_CONTENT_REPORT`) |
| Durable secret ledger tests | `SqlDelightSecretLedgerStoreTest`, `DurableSecretBrokerTest` |

**Quote:**

```10:17:C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\runtime-service\src\main\kotlin\com\omnillm\android\runtimeservice\security\ControlPlaneSecurityFactory.kt
/**
 * Builds the production control-plane security stack (ADR-010 / SEC-AUTH-NET).
 *
 * - Secret Broker keys: Android Keystore-wrapped master + AES-GCM encrypted
 *   operational key blobs in SQLite ([EncryptedBlobSecretKeyVault])
 * - Access tokens: SQLite HMAC-SHA-256 verifiers only (no plaintext)
 * - Pairing challenges: SQLite + encrypted secret ciphertext
 * - Revocation epochs: SQLite durable fence
```

### 3.3 Residual L3 / process isolation claims

| Item | Status | Note |
|---|---|---|
| Companion cannot access broker | **PASS** (design+manifest) | Companion package has no DB/Keystore path; no INTERNET; separate UID |
| Default `InMemorySecretBroker` in some constructors | **N_A** for production attach | Defaults remain for unit tests; production path uses factory (documented in `LoopbackTokenService` / `FeaturePackHost` hermetic fallback) |
| Worker listing Keystore aliases | **PASS** by architecture | Broker lives only in `:runtime` control plane per class docs |

**Secret Broker verdict: PASS** at L1+L2 control plane; L3 “all product surfaces never fall back to in-memory” is **PARTIAL** only where tests/hermetic hosts intentionally default.

---

## 4) Companion different UID

**Docs demand (`SEC-EXTERNAL-SANDBOX`):** different package/UID, no sharedUserId, no INTERNET/secrets, signature bind + ticket handshake, RO PFD only, supervisor death fence, no same-UID acceleration fallback.

### 4.1 Packaging & permissions — **PASS** (L1)

| Evidence | Path |
|---|---|
| `applicationId = "com.omnillm.companion"` | `android/companion-sandbox/build.gradle.kts` L37 |
| Main app `applicationId = "com.omnillm"` | `android/app-ui/build.gradle.kts` L35 |
| No sharedUserId (documented + default) | `PACKAGING.md`, manifest comment |
| INTERNET / ACCESS_NETWORK_STATE removed | `companion-sandbox/.../AndroidManifest.xml` L29–35 |
| `allowBackup=false` | same manifest L39–41 |
| Signature permission `BIND_SANDBOX` | manifest L19–27, service L52–60 |
| Main runtime holds `uses-permission BIND_SANDBOX` | `runtime-service` manifest L38 |

### 4.2 Ticket / command gate / FD policy — **PASS** (L1 + unit tests)

| Evidence | Path |
|---|---|
| `SandboxExecutionTicket` + HMAC-SHA-256 MAC, constant-time verify | `SandboxExecutionTicket.kt` / `SandboxTicketMac` |
| Command allowlist: handshake/load/start/cancel/query/close + RO PFD only | `CompanionCommandGate.kt` |
| FD registry / no path or directory FD | `CompanionFdRegistry.kt`, `CompanionSandboxModule` comments |
| Supervisor fence | `CompanionSupervisorFence.kt` + tests |
| Unit tests | `CompanionTicketValidatorTest`, `CompanionCommandGateTest`, `CompanionIsolationPolicyTest`, `CompanionSupervisorFenceTest` |

### 4.3 Host control-plane gate — **PARTIAL** (L2 present; L3 device journey not claimed)

| Evidence | Path |
|---|---|
| Live availability (installed, differentUid, sameSigner, protocol) | `CompanionAvailability.kt` |
| `CompanionPlacementGate` — never same-UID fallback | `CompanionPlacementGate.kt` |
| Host client bind/handshake | `CompanionHostClient.kt` |
| Ticket factory forces `EXTERNAL_UID_ACCELERATED` | `HostSandboxTicketFactory.kt` |

**Not evidenced in this audit:** Play multi-package upload matrix, OEM device companion bind PASS, or full untrusted-GPU product journey on hardware → **no device PASS** recorded (`BLOCKED_HUMAN` for field qualification).

**Companion verdict: PARTIAL** (L1 solid; L2 host wiring present; L3 product/device journey incomplete / not claimed)

---

## 5) Trust / runtime placement

**Docs demand (`SEC-PLACEMENT`):** five independent dimensions; classes `PRIVILEGED_TRUSTED` … `TRUST_PLACEMENT_REQUIRED`; untrusted accel → companion or fail closed.

### 5.1 Policy pure function — **PASS** (L1 + unit)

| Evidence | Path |
|---|---|
| `TrustPlacementPolicy.resolve` / `gateProposed` / `allowedInSameUidWorker` | `engines/api/.../TrustPlacementPolicy.kt` |
| Labels | `engines/api/.../LoadContracts.kt` — `PlacementClassLabels` |
| Exhaustive unit tests (incl. no silent same-UID for untrusted accel) | `engines/api/.../TrustPlacementPolicyTest.kt` |

### 5.2 Host integration — **PARTIAL**

| Evidence | Path |
|---|---|
| Gate uses live companion availability | `CompanionPlacementGate.resolve` |
| Isolated parser process for untrusted CPU path | `android/parser-isolated/.../AndroidManifest.xml` — `isolatedProcess=true`, `process=":parser"`, exported=false |
| Privileged load reverify port | `runtime/model-manager/.../DefaultPrivilegedLoadReverify.kt` + tests |
| Production supply-chain hooks default | `NoopSupplyChainHooks` fails closed (`verifySignatures` always false; no embedded root) — `SupplyChainHooks.kt` |

**Placement verdict: PASS** for policy correctness; **PARTIAL** for end-to-end privileged-load + supply-chain evidence binding.

---

## 6) Recovery / idempotency

**Docs demand (`REL-RECOVERY`):** failure classes; identity namespaces; durable ledgers; uncertain commit; cancellation stages; observer recovery; recovery UX.

### 6.1 Identity & ledgers — **PASS** (L1) / **PARTIAL** (L2 durability coverage)

| Evidence | Path |
|---|---|
| Schema: `commit_records` states incl. `UNCERTAIN_QUARANTINED`; `idempotent_commands`; unique `(principal_id, operation_kind, idempotency_key)` | `specs/database/omnillm-schema.sql` |
| `CommitLedger.recordIntent` before worker; no blind re-EXECUTE | `runtime/request-registry/.../CommitLedger.kt` |
| Request registry / command ledger | `RequestRegistry.kt`, `CommandLedger.kt` + tests (`RequestRegistryTest`, `SseDisconnectClaimSemanticsTest`) |
| Core FSM fixtures RR-001… | `core/state/.../CommitReconcileFixtureTest.kt` |
| Session poison → `ABORTED_UNCERTAIN` | `runtime/session/.../PoisonedSessionNotReusedNegativeTest.kt` |
| Tool proposal idempotency / UNCERTAIN | `features/tools/.../ToolNonExecutionAndIdempotencyTest.kt`, `SessionUncertaintyAndPrivacyTest.kt` |
| Job durable manager | `runtime/job-manager` + SQLDelight `Jobs.sq` (referenced by plane) |

### 6.2 Gaps

| Gap | Status | Evidence |
|---|---|---|
| AIDL ClientRegistration not durable across process death | PARTIAL | `ClientRegistrationStore` in-memory TODO |
| Recovery UX copy (“无法确认是否完成” etc.) product-complete | PARTIAL | Not fully traced to all UI surfaces in this audit; core states exist |
| Observer cursor/ACK/snapshot recovery | PARTIAL | Stream session registry exists (`StreamSessionFacade`); full REL-RECOVERY §6 corpus not fully mapped here |

**Recovery verdict: PARTIAL** (core commit/request/command/tool ledgers strong; not all namespaces durable/product-complete)

---

## 7) Input / download / resource abuse

**Docs demand (`SEC-INPUT`, SEC-003/004/005):** JSON budgets, headers, HTTPS/DNS deny, range rules, PFD/SAF bounds, model package limits, output abuse, fuzz obligation.

### 7.1 Present & tested — **PASS** / **PARTIAL**

| Control | Status | Evidence |
|---|---|---|
| JSON compressed/decompressed/depth/node/string budgets | **PASS** L1; **PARTIAL** L2 | `JsonParseLimits.kt`, `JsonTreeBudget.kt`; HTTP admission `interfaces/http/.../JsonBodyAdmission.kt`; tests `JsonTreeBudgetTest`, `HttpSecurityNegativeIntegrationTest` |
| Header / ignored-params projection | **PASS** L1 | `HeaderLimits`, `JsonTreeBudget.projectIgnoredParams` |
| Download URL HTTPS-only, no userinfo, IDNA, host allow/deny | **PASS** L1; **PARTIAL** L2 | `DownloadUrlPolicy.kt`; used by `JobManager` download admission |
| Resolved IP private/loopback/link-local/metadata deny | **PASS** L1 | `ResolvedAddressPolicy.kt` + `DownloadUrlAndDnsPolicyTest` |
| Quarantine / materialize bounds | **PASS** L1 | `data/model-store/.../MaterializeBounds.kt`, `StreamMaterializer.kt`, `QuarantineRules.kt` |
| Range download policy types | **PARTIAL** | Class files / sources under `runtime/policy/.../download/RangeDownloadPolicy` (policy module); depth of L2 wire not fully re-proven here |
| Output abuse limits | **PARTIAL** | Defined in `JsonParseLimits.kt` as `OutputAbuseLimits`; `PolicyModule.defaultOutputAbuseLimits()`; **no android/ runtime stream enforcement grep hits** for `OutputAbuseLimits` usage |
| PFD fstat path on device | **PARTIAL** | Helpers/tests note real PFD needed (`PfdMaterializeHelpersTest`); companion RO PFD registration present |

### 7.2 Rate limit / quota (cross-ref SEC-AUTH-NET §6)

| Control | Status | Evidence |
|---|---|---|
| Principal request rate / concurrent ops / token quota enforcer | **MISSING** | Grep for `RateLimit` / principal quota enforcer across `*.kt` returned no production enforcer (only incidental “quota” comments on quarantine/FGS) |

**Input abuse verdict: PARTIAL** (strong JSON/URL/DNS policy core; output-abuse + rate-limit incomplete)

---

## 8) Privacy / redaction / telemetry

**Docs demand (`SEC-PRIVACY`, SEC-010/012/014):** default local; redaction allowlist; diagnostics export consent; no default outbound telemetry; backup exclude; multi-user in principal; AI report ≠ telemetry.

### 8.1 Redaction & diagnostics — **PASS** (L1/L2 feature pack)

| Evidence | Path |
|---|---|
| `Redactor` allowlist-first, prompt/token/path scrubbing | `runtime/observability/.../Redaction.kt` |
| Diagnostic bundle builder redaction allowlist | `features/diagnostics/.../export/DiagnosticBundleBuilder.kt` |
| Tests: no secret prompt in payload | `DiagnosticBundleBuilderTest`, `DiagnosticsServiceTest` |
| Tools trace redaction / report ≠ telemetry | `features/tools/.../ToolsTraceRedaction.kt`, `SessionUncertaintyAndPrivacyTest` |
| Dashboard redacted traces | `DashboardService` / `redactedTrace` |

### 8.2 Backup / cleartext / network defaults — **PASS** (manifest)

| Evidence | Path |
|---|---|
| Main app `allowBackup=false`, data extraction rules, `usesCleartextTraffic=false`, network security config | `android/app-ui/.../AndroidManifest.xml` L33–39 |
| Companion backup off | companion manifest |
| Backup rules inventory | `android/app-ui/src/main/res/xml/backup_rules.xml` |

### 8.3 Telemetry & secure display — **PARTIAL** / **MISSING** pieces

| Item | Status | Evidence |
|---|---|---|
| Setting key `privacy.telemetryMode` | **PARTIAL** | Present in `specs/configuration-catalog.yaml` + admin settings tests; outbound aggregated telemetry pipeline not evidenced as production-enabled product path (default OFF design matches docs) |
| FLAG_SECURE on sensitive screens | **MISSING** (this audit) | Grep `FLAG_SECURE` / `setSecure` under `android/**/*.kt|xml` — **no hits** |
| Multi-user in principal | **PASS** L2 observation | `userId = uid / 100_000` in `PrincipalObservation` |
| AI content report separate stream + encryption | **PASS** L1/L2 | `DurableContentReportStore` + broker `REPORT_QUEUE_ENCRYPTION`; UI string “not telemetry” |

**Privacy verdict: PARTIAL**

---

## 9) Security control catalog (SEC-001…SEC-014) mapping

| ID | Threat (spec) | Implementation evidence | Status |
|---|---|---|---|
| **SEC-001** | untrusted model/native RCE | Companion different UID + isolated parser + RO FD | **PARTIAL** (L1/L2 code; residual driver risk acknowledged; device isolation not PASS-claimed) |
| **SEC-002** | model byte substitution | Privileged load reverify + content digests; supply-chain hooks | **PARTIAL** (`DefaultPrivilegedLoadReverify`; production embedded root/signature often noop) |
| **SEC-003** | SSRF/DNS rebinding | `DownloadUrlPolicy` + `ResolvedAddressPolicy` | **PASS** (policy L1 + tests; job admission L2 partial) |
| **SEC-004** | JSON/resource bomb | `JsonTreeBudget` / `JsonBodyAdmission` | **PASS** L1/HTTP L2; full all-transports L3 partial |
| **SEC-005** | PFD/SAF TOCTOU | Materialize bounds + companion RO PFD | **PARTIAL** |
| **SEC-006** | revocation bypass | `RevocationEpochManager` + ACL fence + token epoch | **PASS** L1/L2 (SQLite epoch store on plane) |
| **SEC-007** | Binder principal spoofing | `PrincipalObservation` + registration UID match | **PASS** observation; third-party approval **PARTIAL** |
| **SEC-008** | LAN MITM/replay/brute | TLS identity, pairing challenge max 5 / TTL 300, HMAC proof | **PARTIAL**→strong L1/L2 services; L3 device LAN journey not claimed |
| **SEC-009** | same-UID logical-owner bypass | Placement policy never same-UID for untrusted accel | **PASS** (policy + host gate) |
| **SEC-010** | sensitive diagnostics | Redactor + diagnostic allowlist | **PASS** feature core |
| **SEC-011** | crypto profile divergence | `SecurityProfile` + `security-profile.yaml` pin | **PASS** |
| **SEC-012** | AI report exfil | Separate feature + encryption + consent scopes | **PARTIAL** (durable path present; full UI consent journey depth out of scope of exhaustive UI walk) |
| **SEC-013** | stale Play policy | platform-policy-register / DATA_SAFETY inventory notes | **PARTIAL** (docs/inventory present; no Play upload result) |
| **SEC-014** | retention ambiguity | retention-policy specs + typed stores | **PARTIAL** (schema/retention specs exist; not fully re-audited for every class) |

---

## 10) Layer rollup (L1 / L2 / L3)

| Component | L1 exists | L2 wired to control plane | L3 product journey software-complete |
|---|---|---|---|
| Access catalog + ACL enforcer | **PASS** | **PASS** (policy manager / HTTP / admin paths) | **PARTIAL** |
| Secret Broker + tokens + pairing | **PASS** | **PASS** (`ControlPlaneSecurityFactory` on attach) | **PARTIAL** |
| AIDL observed principal | **PASS** | **PASS** (same-app auto register) | **PARTIAL** (3P approval TODO; durable store TODO; signature bind) |
| Companion sandbox APK | **PASS** | **PARTIAL** (host client + placement gate) | **PARTIAL** / device **BLOCKED_HUMAN** |
| Isolated parser | **PASS** | **PARTIAL** (manifest + service module) | **PARTIAL** |
| Placement policy | **PASS** | **PARTIAL** | **PARTIAL** |
| Commit/request idempotency | **PASS** | **PARTIAL**–**PASS** (durable SQL ledgers) | **PARTIAL** |
| JSON/URL input abuse | **PASS** | **PARTIAL** | **PARTIAL** |
| Output abuse / rate limit | **PARTIAL** / **MISSING** | **MISSING** | **MISSING** |
| Redaction / diagnostics | **PASS** | **PASS** | **PARTIAL** |
| Supply chain root/sign | **PASS** (API) | **PARTIAL** (noop default risk) | **PARTIAL** |

---

## 11) Search log (empty / negative findings are intentional)

Where status is MISSING or PARTIAL with “no hits”, the following greps/reads were used:

| Query / read | Result |
|---|---|
| `RateLimit` / principal quota enforcer (`*.kt`) | No production enforcer (only incidental comments) |
| `FLAG_SECURE` / `setSecure` under `android/` | No matches |
| `OutputAbuseLimits` under `android/` | No matches (limits defined in policy only) |
| `ClientRegistration` durable SQL | Store explicitly in-memory TODO |
| `approve` path for AIDL third-party | `OmniBindingFacade` TODO comment; LAN has `approveChallenge` but AIDL 3P pending |
| Device companion bind PASS / Play multi-package | Not invented; packaging docs only |

---

## 12) Findings register (actionable)

| ID | Severity | Status | Finding | Evidence |
|---|---|---|---|---|
| SEC-F-01 | High | PARTIAL | Exported AIDL third-party pairing approval incomplete; registrations not durable | `OmniBindingFacade.kt` TODO; `ClientRegistrationStore` TODO |
| SEC-F-02 | High | Design tension | `BIND_RUNTIME` is `signature` — catalog `ANDROID_APP` third parties cannot bind unless same-signer | `runtime-service` manifest L49–53 + comments |
| SEC-F-03 | Medium | MISSING | Principal rate limit / concurrent quota (SEC-AUTH-NET §6) not implemented | Grep empty |
| SEC-F-04 | Medium | PARTIAL | `OutputAbuseLimits` defined but not enforced on android stream path | `JsonParseLimits.kt` vs no android usage |
| SEC-F-05 | Medium | PARTIAL | Supply chain production: `NoopSupplyChainHooks` fails closed until embedded root + verify wired | `SupplyChainHooks.kt` |
| SEC-F-06 | Medium | PARTIAL | Companion L3/device journey and Play multi-package distribution not proven | `PACKAGING.md`; no device PASS |
| SEC-F-07 | Low | MISSING | FLAG_SECURE for sensitive screens not found | Grep empty |
| SEC-F-08 | Low | PARTIAL | AIDL challenge store in-memory ConcurrentHashMap (lost on process death) | `OmniBindingFacade` challenges map |

---

## 13) What is solid (do not re-litigate without new evidence)

1. **Security profile pin** (`OMNILLM-SECURITY-PROFILE-2`) matches docs package `specs/security-profile.yaml` constants in code.  
2. **Secret Broker production stack** on control plane (Keystore-wrap + SQLite verifiers).  
3. **ACL catalog + enforcer** with epoch fence and transport invariants.  
4. **Observed Binder UID principal** (not self-reported package).  
5. **Admin binder non-exported**; runtime binding does not return admin.  
6. **Companion different applicationId/UID**, no INTERNET, signature bind, HMAC tickets, RO PFD-only command surface.  
7. **Placement fail-closed** without companion for untrusted acceleration.  
8. **JSON bomb + download URL/DNS deny** policy cores with tests.  
9. **Durable commit/request/tool uncertainty** semantics.  
10. **Redaction/diagnostics allowlist** architecture.

---

## 14) Verdict

| Scope | Status |
|---|---|
| **SECURITY & RELIABILITY (this audit)** | **PARTIAL** |
| Control-plane crypto + ACL + placement policy software | Strong **PASS** cells (not a product-wide PASS) |
| Product journeys requiring 3P AIDL, rate limits, full companion accel, production catalog roots | **PARTIAL** / **MISSING** |
| Device OEM / Play upload security evidence | **BLOCKED_HUMAN** / not present — not claimed |

---

## 15) Intermediate artifact note

Written only under:  
`C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\audit-reports\05_security.md`

Authority: **新版本** docs package `docs/50-security-reliability/*` + `specs/security-profile.yaml` + `specs/access-control-catalog.yaml` + `specs/security-control-catalog.yaml`.  
No claim of engine QUALIFIED, device PASS, or Play upload success.
