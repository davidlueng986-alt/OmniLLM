package com.omnillm.interfaces.aidl

/**
 * AIDL contract integration (scaffold).
 *
 * Semantic IDL: specs/aidl/omnillm-aidl.yaml
 * Projection: interfaces/aidl/src/main/aidl/ai/omnillm/api/
 * Kotlin markers: ai.omnillm.api package Marker types (not a second wire).
 * Principal: calling UID / Android user + verified binding (INV-011), never self-reported package.
 *
 * Transport constraints (from YAML):
 * - Binder transaction budget: 524288 bytes
 * - Large payload: AssetHandle/PFD or bounded page — never unbounded parcel
 * - Stream delivery: application ACK with streamEpoch + half-open sequence ranges
 *
 * Semantics (ADR-011): same canonical request/event/error as HTTP; delivery guarantees differ.
 * Non-stream ops still use caller-generated RequestId via queryRequest / cancelRequest.
 *
 * Delivery (INV-013): see `com.omnillm.core.errors.TransportDeliveryGuarantee`
 * (`AIDL_STREAM` / `AIDL_UNARY` / `ADMIN_BINDER`). Socket/oneway success ≠ app ACK.
 */
object AidlContractNotes
