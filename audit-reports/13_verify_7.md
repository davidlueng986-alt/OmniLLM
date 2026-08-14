# Adversarial verify — Claim #7

**Claim:** HTTP and AIDL share canonical request/error semantics  
**Auditor:** independent (fail-closed)  
**Date:** 2026-08-12  
**Scope:** NEW docs package + monorepo `omnillm-android` only (no prior-audit trust)

```yaml
claim_id: 7
claim: "HTTP and AIDL share canonical request/error semantics"
real: true
```

## real

**true** — with concrete paths/quotes below. Encoding, HTTP status projection, and **delivery** guarantees differ by design (INV-013); those are not separate request/error *semantic* catalogs.

## reason

Docs and code both define one shared error catalog and one shared request/command identity model; HTTP and AIDL are **projections** of that model (ADR-011 / INV-013). Cross-transport parity is enforced by unit tests that assert identical wire **code strings** (and retryable) for domain `OmniError` on HTTP DTO, AIDL parcelable, and Admin `CommandResult`. Request claim keys (`requestId`/`commandId` + `idempotencyKey` + canonical digest where required) and durable query-after-reply-loss paths exist on both transports and feed the same orchestrator / request registry control plane.

## evidence

### Docs / specs (authority)

| Item | Path | Quote / fact |
|---|---|---|
| ADR-011 | `…OmniLLM_Product_Documents\governance\adr\ADR-011.md` | “HTTP／AIDL／Admin 共用 canonical semantics.” Context: “同一 request/event/error；transport 只保留 delivery/encoding 差異。” |
| INV-013 | `…docs\20-architecture\architecture-invariants.md` | “公開 HTTP／AIDL／UI 都投影同一 canonical capability、request、event 與 error；transport guarantee 可不同且必須明示。” |
| CORE-INTERFACE | `…docs\30-core-platform\interface-platform.md` | “HTTP、AIDL、Admin 與 UI 共享 canonical：… `OmniError` …”; “Caller 先建立 `requestId` 與 `idempotencyKey`… HTTP／AIDL 都能 `queryRequest(requestId)`。” |
| Error catalog | docs + repo `specs/error-catalog.yaml` | Shared codes (`INVALID_REQUEST`, …) with `httpStatus` / `retryable` / `requiredClientAction` (HTTP projects status; semantic code is shared). |
| AIDL projection rule | repo `specs/aidl/omnillm-aidl.yaml` L4–5 | `projectionRule: generated .aidl files are projections; canonical state, scope, error and request semantics remain in the shared catalogs` |
| OpenAPI OmniError | repo `specs/openapi/omnillm.openapi.yaml` ~L1375+ | Schema `OmniError` requires `code`, `message`, `retryable`; `code` enum matches catalog codes. |
| AGENTS.md | repo `AGENTS.md` | “Same canonical request/event/error; transport delivery guarantees differ and must be explicit (ADR-011 / INV-013).” |

### Error semantics (implementation)

| Layer | Path | Evidence |
|---|---|---|
| Generated catalog | `core/errors/.../generated/OmniErrorCode.kt` | Single enum of 25 codes with `code: String`, `httpStatus`, `retryable`, `requiredClientAction` from `specs/error-catalog.yaml`. |
| Boundary mapping | `core/errors/.../ErrorMapping.kt` | `fromCode` / `httpStatus` / `withTransportDelivery` — “Transport **delivery** is not an error code”; cites INV-013 / ADR-011. |
| Delivery ≠ semantic code | `core/errors/.../TransportDeliveryGuarantee.kt` | “Canonical request / event / **error codes** are shared across HTTP, AIDL, and Admin. Delivery differs by transport…” Labels: `HTTP_SSE`, `HTTP_JSON`, `AIDL_STREAM`, `AIDL_UNARY`, `ADMIN_BINDER`. |
| HTTP projection | `interfaces/http/.../OmniErrorHttp.kt` | “HTTP projection of catalog [OmniError] (ADR-011 / INV-013).” `toDto` copies `error.code.code`, `retryable`, details. |
| HTTP wire DTO | `interfaces/http/.../WireDtos.kt` | `OmniErrorDto(code, message, retryable, details)`. |
| AIDL parcelable | `interfaces/aidl/.../OmniError.aidl` | `String code; String message; boolean retryable; OmniErrorDetail[] details;` |
| AIDL mapper | `android/runtime-service/.../binder/AdminAidlMapper.kt` | `toAidlError`: `err.code = error.code.code`; `err.retryable = error.retryable`. Comment: “ADR-011 transport projection only.” |
| AIDL binder helper | `android/runtime-service/.../binder/BinderErrors.kt` | Maps `OmniErrorCode` → AIDL `OmniError`; optional `TransportDeliveryGuarantee` in details only. |
| Parity test | `android/runtime-service/.../transport/TransportParityTest.kt` | `errorCode_httpAndAidlAndAdmin_shareCanonicalCatalogCode`: for sample codes, `http.code == aidl.code == admin.error.code.code`; also delivery-detail projection on both transports. |
| Full catalog pin | `core/errors/.../ErrorCatalogProjectionTest.kt` | Pins all **25** codes’ httpStatus/retryable/action against generated types + `ErrorMapping`. |

### Request semantics (implementation)

| Layer | Path | Evidence |
|---|---|---|
| Command claim (HTTP) | `interfaces/http/.../ClaimDtos.kt` | `CommandRequestDto`: `command_id`, `idempotency_key`, `canonical_input_digest`, optional `expected_version`. “Client generates commandId/requestId and idempotencyKey **before** send; on reply loss query…” |
| Command claim (AIDL) | `interfaces/aidl/.../OmniCommandRequest.aidl` | Same fields: `commandId`, `idempotencyKey`, `hasExpectedVersion`/`expectedVersion`, `canonicalInputDigest`. |
| Chat request (AIDL) | `interfaces/aidl/.../OmniChatRequest.aidl` | First-class `requestId`, `idempotencyKey`, model, messages, stream, deadline, tools schema digests. |
| Shared shaping | `android/runtime-service/.../featurehost/FeatureRequestMapper.kt` | “Shared request → routing-candidate shaping for all transport facades”; builds `OrchestrationRequest` with `requestId`, `idempotencyKey`, `canonicalRequestDigest`. |
| AIDL path | `android/runtime-service/.../binder/OmniRuntimeFacade.kt` | Requires client UUID `requestId` + `idempotencyKey`; uses `FeatureRequestMapper` + `RequestRegistry` / orchestrator; `queryRequest` / `cancelRequest`. |
| HTTP path | `android/runtime-service/.../http/ControlPlaneHttpHandler.kt` | Uses catalog `OmniError.*`; async path builds `OrchestrationRequest` + `FeatureRequestMapper`; `queryRequest` / cancel via same registry; errors via `OmniErrorDto` / `OmniErrorHttp`. |
| Contract note | `docs/architecture/contract-integration.md` | Single writer control plane; claim key `(principalId, operationKind, idempotencyKey)`; same hash conflict → `IDEMPOTENCY_CONFLICT`; reply loss → query only. Transport delivery table maps HTTP vs AIDL to shared `TransportDeliveryGuarantee`. |

## counter_evidence

These **do not** invent a second error/request catalog, but they bound how absolute “same semantics” is on every surface:

1. **Delivery deliberately differs** — INV-006/013: SSE socket write ≠ client-delivered checkpoint; AIDL stream advances only on application ACK (`TransportDeliveryGuarantee`, `TransportParityTest.deliveryGuarantees_areExplicitAndDifferByTransport`). Claim is about *request/error* semantics, not delivery equality.

2. **Encoding differs** — HTTP JSON snake_case + optional HTTP status from catalog; AIDL parcelables + detail array with typed slots. Specs call this projection/encoding, not domain redefinition.

3. **OpenAI-compat HTTP softens INV-012 on sync chat** — `ControlPlaneHttpHandler` L310–311: “Sync OpenAI profile: optional client IDs; generate when omitted.” AIDL `OmniRuntimeFacade` **requires** client-generated UUID `requestId` and `idempotencyKey` (INVALID_REQUEST otherwise). Canonical claim model still exists on both; production path comments prefer durable `/omni/v1/requests`.

4. **FeatureRequestMapper ARC-12 note** — comment still says “HTTP path conversion is tracked as ARC-12 follow-up,” while async/native paths already call `FeatureRequestMapper`; sync OpenAI exploratory path still has hand-shaped digests/`requestId` generation. Residual **partial** unification of *shaping helpers*, not dual error catalogs.

5. **TransportParityTest samples 10 codes** for cross-transport identity (not all 25). Full 25-code HTTP/status projection is covered by `ErrorCatalogProjectionTest`; residual: no exhaustive 25-way AIDL round-trip matrix in that one test.

6. **AIDL does not carry `httpStatus` on the parcel** — status is an HTTP projection of the same catalog code (`OmniErrorCode.httpStatus` / `OmniErrorHttp.statusOf`). Semantic code string remains shared.

## residual

| Residual | Severity | Note |
|---|---|---|
| OpenAI sync chat auto-`requestId` / synthetic idempotency | Medium (surface policy) | Weakens strict INV-012 on that profile only; AIDL + `/omni/v1/*` keep client-generated claim shape. |
| ARC-12 incomplete comment vs partial HTTP use of `FeatureRequestMapper` | Low | Docs-in-code drift; domain still `OrchestrationRequest` + catalog errors. |
| Parity test sample vs full 25 codes | Low | Expand `TransportParityTest` to `OmniErrorCode.entries` for stronger regression. |
| No device multi-client HTTP↔AIDL identity golden run in this verify | Out of scope | Software contract evidence sufficient for claim; not claiming device PASS. |

## search log (empty-finding guard)

Grep/read performed under monorepo and docs package:

- Docs: `ADR-011`, `INV-013`, `CORE-INTERFACE`, `error-catalog.yaml`, `omnillm-aidl.yaml`, `omnillm.openapi.yaml`
- Code: `OmniErrorHttp`, `AdminAidlMapper`, `BinderErrors`, `TransportDeliveryGuarantee`, `TransportParityTest`, `ErrorCatalogProjectionTest`, `FeatureRequestMapper`, `ControlPlaneHttpHandler`, `OmniRuntimeFacade`, AIDL `OmniError`/`OmniChatRequest`/`OmniCommandRequest`, HTTP `WireDtos`/`ClaimDtos`, `AGENTS.md`, `contract-integration.md`

No contradictory private HTTP-only or AIDL-only error **catalog** found.

## status summary (claim level)

| Aspect | Status | Notes |
|---|---|---|
| Shared error code catalog | **PASS** | `error-catalog.yaml` → `OmniErrorCode`; HTTP + AIDL project `code`/`retryable` |
| Shared request/command claim semantics | **PASS** (with OpenAI soft-id residual) | Same identity fields + registry claim-or-return; AIDL strict; OpenAI sync may generate IDs |
| Transport delivery equality | **N_A** (not claimed) | Docs require difference; explicit labels |
| L3 product multi-transport golden on device | **MISSING** (not required for this claim) | No device matrix invented |

**Claim #7 verdict: `real: true`.**
