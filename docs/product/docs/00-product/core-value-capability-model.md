---
id: "PROD-CAPABILITY-MODEL"
title: "核心價值與能力模型"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "product-architecture"
lastReviewed: "2026-07-31"
---

# 核心價值與能力模型

## 1. 目的
把三項核心價值轉成可設計、可組合、可觀測的產品能力。能力模型描述產品「能做什麼」，不以開發階段或版本刪減能力；某能力在特定裝置是否可用，必須由 capability negotiation、trust placement 與實測證據決定。

## 2. 能力層級
| 層級 | 能力群 | 主要輸出 |
|---|---|---|
| 體驗層 | Onboarding、ModelHub、Local Chat、Developer Server、Dashboard、Diagnostics | 使用者可完成的工作與可理解狀態 |
| 平台層 | Engine Aggregation、Model Platform、Orchestration、Session、Resource Governance、Interface Platform、Observability | 穩定的跨引擎語義 |
| 保證層 | Trust、Isolation、Durability、Idempotency、Compatibility、Failure Recovery | 防止錯誤、安全或狀態語義漂移 |
| 平台適配層 | Android services、Binder、native packaging、device drivers；未來 iOS／PC／IoT adapters | 把 portable core 放入特定 OS |

## 3. 核心能力目錄
### 3.1 自動架設
- `DEVICE_DISCOVERY`：CPU feature、RAM、GPU/NPU、OS、driver 與可用 storage 的版本化指紋。
- `MODEL_ACQUISITION`：簽章 catalog、釘選下載與本機匯入。
- `MODEL_IDENTITY`：Blob、ArtifactPackage、ModelRevision、Installation、Alias 分層。
- `COMPATIBILITY_EVALUATION`：靜態條件、probe 與實測 evidence；不提升來源信任。
- `RECOMMENDATION`：依使用者目標、裝置、模型與風險偏好產生可解釋候選。
- `AUTOMATED_CONFIGURATION`：context、threads、batch、backend 與降級選項。
- `SAFE_INSTALLATION`：quarantine、bounded parser、verification、atomic promotion、reconciliation。

### 3.2 統一調用
- `TEXT_GENERATION`、`EMBEDDING`、`VISION_INPUT`、`AUDIO_INPUT`、`STRUCTURED_OUTPUT`、`TOOL_CALLING`。
- `ENGINE_LIFECYCLE`、`MODEL_LIFECYCLE`、`SESSION_LIFECYCLE`、`REQUEST_LIFECYCLE`、`JOB_LIFECYCLE`、`JOB_RECOVERY`。
- `CAPABILITY_NEGOTIATION`：明示 supported／unsupported／conditional／unknown。
- `STREAMING`、`CANCELLATION`、`DEADLINE`、`IDEMPOTENCY`、`RECONNECT`。
- `HTTP_INTERFACE`、`AIDL_INTERFACE`、`ADMIN_INTERFACE`、`LOCAL_UI_INTERFACE`、`LAN_INTERFACE`。
- `MULTI_MODEL_ROUTING` 與 `FALLBACK_POLICY`：由 caller 明示允許邊界，不靜默換 revision。

### 3.3 可視化監控
- `SERVICE_HEALTH`、`ENGINE_HEALTH`、`MODEL_HEALTH`、`REQUEST_TRACE`、`JOB_PROGRESS`。
- `RESOURCE_ACCOUNTING`：reservation、allocation、workspace、KV、GPU/NPU、FD、thread 與 disk temp。
- `PERFORMANCE_MEASUREMENT`：TTFT、throughput、latency、queue、error、thermal 與 energy hints。
- `DIAGNOSTIC_REASONING`：把 raw signal 轉為可行動的原因與建議。
- `EVIDENCE_LABELING`：MEASURED／ESTIMATED／REPORTED／LAST_SAMPLED／UNKNOWN。

## 4. 能力狀態
每項 capability 必須使用下列狀態之一：

- `SUPPORTED`：在指定 engine build、backend、device fingerprint、model envelope 與 operation profile 下有正向證據。
- `UNSUPPORTED`：已知不可用，不應嘗試。
- `CONDITIONAL`：需滿足可機讀條件，如特定格式、driver、trust class 或 config。
- `UNKNOWN`：缺乏證據；不能當作 supported。
- `TEMPORARILY_UNAVAILABLE`：原本可用，但因 thermal、resource、revocation 或 health 狀態暫停。

能力結果必須附 `evidenceId`、適用 envelope、有效期限與拒絕原因。全域布林值不足以描述不同 model／device／backend 組合。

## 5. 功能與能力的關係
Feature Design 只編排能力，不自行重新定義其語義。例如 Local Chat 使用 generation、session、streaming、cancel 與 diagnostics；Developer Server 使用同一 canonical request/event/error，只換 transport；Dashboard 讀取同一 health 與 measurement model。

## 6. 新能力加入規則
新增能力時必須回答：

1. 它對應哪項核心價值或哪個跨切面保證？
2. canonical input、output、state、error 與 cancellation 是什麼？
3. 哪些 engine 可實作，哪些只能宣告 unsupported？
4. 它需要哪些 resource vector 維度與 trust placement？
5. 如何被 UI、HTTP、AIDL 與 diagnostics 投影？
6. 如何在舊 client、舊 engine 或舊資料中安全降級？

沒有回答上述問題的功能，不得以零散 flag 加入共同合約。
