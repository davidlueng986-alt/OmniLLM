---
id: "NAV-CORE"
title: "核心平台設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "GUIDANCE"
owner: "architecture-governance"
lastReviewed: "2026-07-31"
---

# 核心平台設計

本目錄保存 OmniLLM 建置前產品與架構設計的現行文件。章節號只作閱讀導航；跨文件引用以文件 ID 與規格 ID 為準。

| 文件 ID | 名稱 | 狀態 | 權威 | Owner |
|---|---|---|---|---|
| `CORE-CAPABILITY` | [Capability 與相容性設計](capability-compatibility.md) | `BASELINE` | `NORMATIVE` | `core-platform` |
| `CORE-ENGINE` | [統一引擎平台設計](engine-platform.md) | `BASELINE` | `NORMATIVE` | `core-platform` |
| `CORE-CONTRACT-ARTIFACTS` | [Formal Contract Artifacts 與實作邊界](formal-contract-artifacts.md) | `BASELINE` | `NORMATIVE` | `core-platform` |
| `CORE-INTERFACE` | [統一介面平台設計](interface-platform.md) | `BASELINE` | `NORMATIVE` | `core-platform` |
| `CORE-MODEL` | [模型平台設計](model-platform.md) | `BASELINE` | `NORMATIVE` | `core-platform` |
| `CORE-OBSERVABILITY` | [可觀測性與診斷平台設計](observability-diagnostics.md) | `BASELINE` | `NORMATIVE` | `core-platform` |
| `CORE-ORCHESTRATOR` | [Orchestrator 與排程設計](orchestrator-scheduler.md) | `BASELINE` | `NORMATIVE` | `core-platform` |
| `CORE-RESOURCE` | [多維資源治理設計](resource-governance.md) | `BASELINE` | `NORMATIVE` | `core-platform` |
| `CORE-SESSION` | [Session、Context 與 KV 設計](session-context-kv.md) | `BASELINE` | `NORMATIVE` | `core-platform` |

機器可讀單一來源位於 [`../../specs/`](../../specs)；治理規則位於 [`../../governance/`](../../governance)。
