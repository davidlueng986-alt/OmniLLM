---
id: "UX-STATE-CATALOG"
title: "UX 狀態、證據與錯誤投影"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "product-design"
lastReviewed: "2026-07-31"
---

# UX 狀態、證據與錯誤投影

## 1. 單一狀態來源
`specs/state-machines.yaml` 定義 canonical state；`specs/ux-projection-catalog.yaml` 定義 label key、嚴重度、允許動作與輔助說明。本文件不得建立第二套 lifecycle。UI 可以顯示「服務可用」「正在回復」等本地化文字，但 payload、diagnostic、analytics 與 action guard 必須保留 canonical ID。

## 2. Runtime
`STOPPED／STARTING／WAITING_FOR_USER_FOREGROUND／RECOVERING／READY／DEGRADED／DRAINING／FAULTED`。`READY` 只表示最低安全控制面可接受符合政策的工作；不等於所有引擎或 capability 可用。`DEGRADED` 必須列 affected capabilities、reason、evidence freshness 與恢復動作。

## 3. 模型的兩條生命週期
Installation 與 Loaded Model 分開顯示：

- Installation：`DISCOVERED／ACQUIRING／QUARANTINED／VERIFYING／COMPATIBILITY_CHECK／READY／DRAINING／REVOKED／CORRUPT／REJECTED／DELETING／DELETED`。
- Loaded Model：`PLANNED／RESERVED／LOADING／LOADED／DRAINING／UNLOADING／UNLOADED／FAILED_DRAINING／FAILED`。

來源信任、license、compatibility 與 placement 是獨立欄位；不能用一個「可用／不可用」狀態掩蓋原因。

## 4. Request 與 Operation
Request：`RECEIVED／CLAIMED／PLANNING／QUEUED／RESERVED／COMMITTING／RECONCILING／PREPARED／STARTING／STREAMING／TERMINATING`，terminal 為 `COMPLETED／FAILED／CANCELLED／ABORTED_UNCERTAIN`。

Engine Operation：`PREPARED／STARTING／RUNNING／CANCELLING／RECONCILING`，terminal 為 `COMPLETED／FAILED／CANCELLED／ABORTED_UNCERTAIN`。UI 同時顯示 request phase 與目前 operation，不能以單一 spinner 混合排隊、載入、commit 與 generation。

## 5. Job、Asset、Client 與 Module
Job、Asset、ClientRegistration、PairingChallenge、EngineModule、Reservation、Allocation 與 RevisionLease 均直接投影對應 machine。任何 transition action 只在目前 state 與 access-control rule 允許時出現；例如 PINNED Asset 不顯示立即刪除、舊 epoch Client 不顯示「重試即可」。

## 6. Capability 與 evidence
Capability state 使用 `SUPPORTED／UNSUPPORTED／CONDITIONAL／UNKNOWN／TEMPORARILY_UNAVAILABLE`。`UNKNOWN` 不能翻成不支援；`CONDITIONAL` 必須列條件。Metric／compatibility／recommendation 同時顯示 `MEASURED／ESTIMATED／REPORTED／LAST_SAMPLED／UNKNOWN` 與觀測時間。

## 7. 錯誤與建議動作
`specs/error-catalog.yaml` 是 code、transport mapping、retryability 與 required client action 的權威；UX projection 只提供 message key、action key 與安全說明。Transport failure 必須區分「可能未送達」「已接受但回覆遺失」「stream 中斷」；後兩者先 query，不得直接重放非冪等工作。

## 8. 文案不變式
- 不把 internal exception、private path、token 或 raw prompt 當主要文案。
- 不把 unknown 寫成「裝置不支援」；不把 dry-load 寫成「已驗證安全」。
- fallback、sandbox、degraded mode 與 residual risk 必須揭露實際 model revision、engine/backend、placement 與原因。
- `ABORTED_UNCERTAIN` 不顯示為一般失敗；需說明狀態不可證明、Session 已隔離及下一個安全動作。


## 8. AI 內容回報投影
`DRAFT／REVIEWING／QUEUED_OFFLINE／SUBMITTING／SUBMITTED／FAILED_RETRYABLE／FAILED_FINAL／DISCARDED` 直接投影 `CONTENT_REPORT`。UI 必須顯示將送出的欄位、網路狀態、retry/delete，以及「回報資料不等於一般 telemetry」。
