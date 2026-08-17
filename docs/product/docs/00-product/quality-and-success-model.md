---
id: "PROD-QUALITY"
title: "品質與成功模型"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "product-architecture"
lastReviewed: "2026-07-31"
---

# 品質與成功模型

## 1. 品質不是單一分數
OmniLLM 的成功由「可完成性、正確性、安全、可恢復性、性能、可理解性、可擴充性」共同構成。任何一項都不能以平均分數掩蓋阻斷問題。

## 2. 核心品質屬性
| 屬性 | 設計問題 | 可觀測結果 |
|---|---|---|
| 可完成性 | 使用者是否能從零完成模型取得與推理？ | journey completion、阻斷原因、恢復成功率 |
| 語義正確性 | 不同 transport／engine 是否保持同一 request/event/error？ | contract conformance、capability disclosure |
| 安全 | 宣稱是否符合 UID、permission、signature 與 network control？ | placement decision、revocation、negative tests |
| 資源可預期 | 高成本操作是否先規劃與准入？ | reservation/allocation 守恆、OOM/LMK avoidance |
| 可恢復性 | reply loss、process death、斷線後是否有唯一可證明狀態？ | query/reconcile、poison/drain、journal replay |
| 性能 | 是否在明確 profile 下量測並能解釋？ | TTFT、TPS、p95、thermal、profile identity |
| 可理解性 | UI 是否讓人知道原因、風險與下一步？ | state catalog、diagnostic action、copy review |
| 可擴充性 | 新引擎／平台是否局部加入？ | core churn、adapter isolation、migration surface |

## 3. 設計目標與平台保證的區分
文件可定義量測目標，但不得把 Android 重啟時間、OEM driver 行為、native cancellation latency 或 parser memory safety 寫成無條件保證。每個 SLO 必須包含環境、workload、percentile、測量方法與失效條件。

## 4. 可信度標籤
所有推薦、預估與監控資料使用：

- `MEASURED`：有完整 measurement profile 與 run。
- `ESTIMATED`：由 model/device envelope 推導，附信心與保守誤差。
- `REPORTED`：由 engine／driver／worker 回報，必須揭露來源，不能當作 control-plane 實測。
- `LAST_SAMPLED`：樣本有時間戳與 age，不能呈現為即時值。
- `UNKNOWN`：不能用零或正常色彩假裝可用。

## 5. 設計完成條件
一項功能被視為設計完整，必須有：使用者目標、canonical capability、state、data ownership、failure/cancel/recovery、security/privacy、resource impact、observability、compatibility、extension point 與 acceptance scenarios。這是文件完整性條件，不是開發排程。
