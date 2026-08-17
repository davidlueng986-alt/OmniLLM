---
id: "ARCH-EXTENSION"
title: "擴充架構與變更邊界"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "architecture"
lastReviewed: "2026-07-31"
---

# 擴充架構與變更邊界

## 1. 擴充類型
| 類型 | 擴充位置 | 核心可否改動 |
|---|---|---|
| 新使用者功能 | `docs/70-features/` + feature module | 只有需要新 canonical capability 時才改 |
| 新推理引擎 | `docs/80-engines/` + adapter/module | 不修改 Orchestrator／transport 語義 |
| 新 backend／device path | engine capability matrix | 不新增模糊全域 flag |
| 新 transport | Interface Adapter | 重用 canonical request/event/error，明示 delivery 差異 |
| 新 OS | Platform Ports + OS adapter | Portable core 不引入 OS-specific type |
| 新模型格式 | Model Parser/Descriptor + engine mapping | identity schema 版本化，不修改 BlobId |

## 2. Feature Pack 契約
每個 feature 必須描述 core value、user journey、capability dependency、state、data、security、resource、observability、failure/recovery、compatibility 與 extension hooks。Feature 不可直接把 engine-specific parameter 加到所有 request；必要差異放 capability-scoped extension namespace，且進入 LoadKey／profile。

## 3. Engine Pack 契約
每個 engine 必須提供 upstream lock、integration shape、format/backend support、phase cancellation、resource envelope、process placement、Session/KV semantics、event mapping、known limitations 與 qualification evidence schema。

## 4. Canonical type 演進
- 新 optional field 必須有 default／absence semantics。
- 新 enum 值對舊 client 的行為明確；未知值不能被當成安全值。
- breaking change 使用 major version 與 migration plan。
- opaque ID 保持不可解析；client 不應依字串格式推導資料。

## 5. 決策門檻
只有改變 architecture invariant、identity formula、trust boundary、resource ownership、wire delivery guarantee 或 persistence state machine 的變更需要 ADR。引擎內部最佳化或 UI 排版不應污染核心決策紀錄。

## 6. 防止文件膨脹
共通文件不收錄易變 upstream commit、逐裝置測量或單一 feature 畫面細節；這些留在 Engine／Feature Pack 或 evidence。文件以 stable ID 互引，不使用「Doc N §x」作唯一主鍵。
