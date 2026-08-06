# Contract integration scaffold (HTTP / AIDL / SQL)

Implementation note — product authority remains under product docs `CORE-INTERFACE`, `DATA-OWNERSHIP`, and repo `specs/`.

## Single writer (ADR-010)

**Only the runtime control plane** (`:android:runtime-service` + `:runtime:*`) writes:

- Domain DB (`:data:persistence`)
- Model store (`:data:model-store`)
- Tokens / trust / request-command-job-commit ledgers

UI (`:android:app-ui`), HTTP/AIDL transport modules, workers, isolated parser, and companion **must not** open a second domain writer (INV-001).

Documented in code: `com.omnillm.data.persistence.SingleWriterPolicy`.

## OpenAPI → HTTP

| Item | Location |
|---|---|
| Authority YAML | `specs/openapi/omnillm.openapi.yaml` |
| Packaged resource | `interfaces/http/src/main/resources/openapi/omnillm.openapi.yaml` |
| Path / operationId inventory | `OpenApiPaths` |
| Scope map | `OpenApiScopes` (`x-omnillm-required-scope`) |
| Loopback gateway | `LoopbackHttpGateway` (Ktor CIO, `127.0.0.1:11434`) |
| Routes | `installOmniHttpRoutes` / `omniHttpRoutes` |
| Handler port | `OmniHttpHandlerPort` |
| Control-plane handler | `android/runtime-service/.../http/ControlPlaneHttpHandler` |
| Token service | `LoopbackTokenService` → `:runtime:policy` `TokenService` / `SecretBroker` (HMAC verifier, SEC-PROFILE) |
| Secret Broker | `runtime/policy/.../security/SecretBroker.kt` (token mint, pairing AES-GCM, one-time plaintext) |
| Pairing challenges | `runtime/policy/.../security/PairingChallengeService.kt` (TTL / attempts / channel binding) |
| Principal / ACL | `runtime/policy/.../acl/AccessControlEnforcer.kt` (scope + revocation epoch fence) |
| Security stack factory | `PolicyModule.createSecurityStack()` |
| Gateway lifecycle | `GatewayLifecycle` (started from `RuntimeForegroundService`) |
| SSE framing | `SseFraming` (pre-stream HTTP err; post-stream terminal; STATELESS session) |
| Claim DTOs | `CommandRequestDto`, `AsyncInferenceRequestClaimDto`, wire DTOs in `WireDtos.kt` |

## AIDL YAML → Binder projection

| Item | Location |
|---|---|
| Authority YAML | `specs/aidl/omnillm-aidl.yaml` |
| `.aidl` projections | `interfaces/aidl/src/main/aidl/ai/omnillm/api/` |
| Kotlin parcelable markers | `ai.omnillm.api.*Marker` |
| Regenerator | `tools/codegen/extract_aidl.py` |
| Runtime facade | `android/runtime-service/.../binder/OmniRuntimeFacade` |
| Admin facade | `android/runtime-service/.../binder/OmniAdminFacade` |
| Stream ACK/credit | `StreamCreditWindow`, `StreamDeliveryEngine`, `StreamSessionFacade` |

Module `:interfaces:aidl` is an Android library with `buildFeatures.aidl = true`.

## Transport delivery guarantees (INV-013)

| Item | Location |
|---|---|
| Canonical catalog (errors module) | `core/errors/.../TransportDeliveryGuarantee` |
| Error annotation helper | `ErrorMapping.withTransportDelivery` |
| HTTP projection | `OmniErrorHttp.withDefaultHttpDelivery` / SSE uses `HTTP_SSE` |
| AIDL projection | `BinderErrors.omniError(..., transport=…)` |
| Runtime-service mirror | `TransportDeliveryGuarantees` |
| Parity tests | `TransportParityTest`, `StreamDeliveryAckCreditTest`, `LaunchCriticalHttpSurfaceTest` |

HTTP SSE socket write ≠ application ACK. AIDL stream advances only via application ACK + half-open seq ranges. Reply loss → query durable request/command only.

## SQL schema → persistence

| Item | Location |
|---|---|
| Authority SQL | `specs/database/omnillm-schema.sql` |
| Migration policy | `specs/database/migration-policy.yaml` |
| Packaged resources | `data/persistence/src/main/resources/db/` |
| SQLDelight subset | `data/persistence/src/main/sqldelight/...` (`schema_*`, claim ledgers) |

SQLDelight projects a control-plane query subset. Full DDL (triggers, indexes, FKs) remains in the authority SQL applied by the control plane bootstrap.

## Conformance placeholders

- HTTP claim shape: `interfaces/http/.../ClaimShapeConformanceTest`
- AIDL claim shape: `interfaces/aidl/.../AidlClaimShapeConformanceTest`
- Ledger claim-or-return: `data/persistence/.../ClaimOrReturnConformanceTest`
- Request Registry + Command ledger: `runtime/request-registry/.../RequestRegistryTest`

## Request Registry + Command ledger (ADR-004/005)

| Item | Location |
|---|---|
| Claim DAO ports | `data/persistence/.../ClaimLedgerDao.kt` |
| SQLDelight durable store | `data/persistence/.../SqlDelightClaimLedgerStore.kt` + `ControlPlaneDatabase` |
| In-memory store (tests only) | `data/persistence/.../InMemoryClaimLedgerStore.kt` |
| Request Registry | `runtime/request-registry/.../RequestRegistry.kt` |
| Command Ledger | `runtime/request-registry/.../CommandLedger.kt` |
| Claim outcome | `ClaimOutcome` — `New` / `Existing` / `Conflict` |
| Production wiring | `RuntimeControlPlane.attach` → `ControlPlaneDatabase` + `RequestRegistryModule.createWithCommits` |

Claim key for both ledgers: `(principalId, operationKind, idempotencyKey)`.
Same key + different canonical hash ⇒ `IDEMPOTENCY_CONFLICT`. Reply loss ⇒
`queryRequest` / `queryCommand` only (no blind replay).

Fixtures catalog: `specs/command-conformance-fixtures.yaml`.
