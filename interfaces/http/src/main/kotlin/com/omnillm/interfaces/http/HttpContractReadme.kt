package com.omnillm.interfaces.http

/**
 * ## HTTP contract integration
 *
 * | Concern | Authority |
 * |---|---|
 * | Wire paths / schemas | `specs/openapi/omnillm.openapi.yaml` |
 * | Packaged copy | classpath `openapi/omnillm.openapi.yaml` |
 * | Canonical claim types | `specs/canonical-types.yaml` (`CommandRequest`, RequestId) |
 * | Semantics parity | ADR-011 — HTTP / AIDL / Admin share canonical meaning |
 * | Auth / tokens | SEC-AUTH-NET, SEC-PROFILE, `specs/access-control-catalog.yaml` |
 * | Scopes | [OpenApiScopes] ← OpenAPI `x-omnillm-required-scope` |
 *
 * ### Implementation map
 * | Piece | Type |
 * |---|---|
 * | Loopback server | [com.omnillm.interfaces.http.gateway.LoopbackHttpGateway] (Ktor CIO) |
 * | Routes | [installOmniHttpRoutes] |
 * | Handler port | [OmniHttpHandlerPort] (wired in `:android:runtime-service`) |
 * | SSE | [com.omnillm.interfaces.http.sse.SseFraming] |
 * | Auth | [com.omnillm.interfaces.http.auth.TokenAuthenticator] |
 *
 * ### Rules
 * - Client-generated `request_id` / `command_id` / `idempotency_key` (ADR-004/005).
 * - Claim-or-return; on reply loss **query** (`getRequest` / `getCommand`), never blind replay.
 * - Token auth even on loopback; `/health` is minimal unauthenticated liveness only.
 * - SSE: pre-stream HTTP errors; post-stream terminal events; **stateless Session default**.
 * - No DB writes from this module (ADR-010); no engine selection / Session mutation (INV-001).
 * - Unknown operation / envelope ⇒ fail closed (INV-018).
 *
 * ### Note on AdminSnapshot / capabilities
 * OpenAPI HTTP surface has no dedicated `/capabilities` or `/admin/snapshot` paths.
 * Capabilities ride on [ModelInfoDto.capabilities] via `GET /v1/models`.
 * Admin snapshot / admin event stream remain AIDL (`IOmniAdmin`) per `specs/aidl/omnillm-aidl.yaml`.
 * HTTP admin-ish projection: settings, clients, metrics, jobs, tokens, diagnostics.
 *
 * ### Transport delivery (INV-013)
 * Documented in `com.omnillm.core.errors.TransportDeliveryGuarantee` and attached to
 * OmniError details via [OmniErrorHttp.withDefaultHttpDelivery] / [ErrorMapping.withTransportDelivery].
 * HTTP_SSE socket write ≠ application ACK; reply loss → query durable request/command only.
 */
object HttpContractNotes
