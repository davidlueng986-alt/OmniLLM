---
id: "GOV-DECISIONS"
title: "已採用的核心決策索引"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "architecture-governance"
lastReviewed: "2026-07-31"
---

# 已採用的核心決策索引

| ID | 決策 | 狀態 |
|---|---|---|
| [ADR-001](adr/ADR-001.md) | 使用能力宇宙與 runtime negotiation | `BASELINE` |
| [ADR-002](adr/ADR-002.md) | Plan → Reservation → Commit → Execute | `BASELINE` |
| [ADR-003](adr/ADR-003.md) | Reservation 與 AllocationHandle 分離 | `BASELINE` |
| [ADR-004](adr/ADR-004.md) | Client-generated RequestId 與 IdempotencyKey | `BASELINE` |
| [ADR-005](adr/ADR-005.md) | CommitId 與 durable/queryable commit | `BASELINE` |
| [ADR-006](adr/ADR-006.md) | SSE stateless-by-default；AIDL application ACK | `BASELINE` |
| [ADR-007](adr/ADR-007.md) | 不受信任加速執行使用 different package/UID | `BASELINE` |
| [ADR-008](adr/ADR-008.md) | Blob／ArtifactPackage／ModelRevision／Installation 分層 | `BASELINE` |
| [ADR-009](adr/ADR-009.md) | Trust、compatibility、performance、license、placement 分離 | `BASELINE` |
| [ADR-010](adr/ADR-010.md) | Runtime control plane 為 single writer | `BASELINE` |
| [ADR-011](adr/ADR-011.md) | HTTP／AIDL／Admin 共用 canonical semantics | `BASELINE` |
| [ADR-012](adr/ADR-012.md) | Portable Core 支援未來 iOS／PC／IoT | `BASELINE` |

每個 ADR 保存 Context、Alternatives、Consequences、Security／Resource／Compatibility、Validation 與 Reopen triggers。此索引不得取代 individual record。
