---
id: "FEAT-DASHBOARD"
title: "Dashboard 與可視化監控"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "feature-team"
lastReviewed: "2026-07-31"
---

# Dashboard 與可視化監控

## 0. 核心價值對應
- **低技術門檻自動架設**：把「為何不能跑／為何變慢／該做什麼」轉成可行動的原因與建議。
- **統一調用**：所有引擎以共同 health、request、resource、measurement 與 evidence 語義呈現，不做每引擎孤島面板。
- **可視化監控**：這是主要價值本身；顯示服務、模型、工作、客戶端、資源、效能、熱狀態、降級與恢復。

## 1. 問題導向資訊架構
首頁依序回答：Runtime 是否可用、目前在做什麼、哪些 model/engine 已載入、資源為何被占用、哪些 client 正在等待、性能是否可比較、是否有需要使用者處理的風險或錯誤。

## 2. 視圖
- **Runtime**：STOPPED／STARTING／RECOVERING／READY／DEGRADED／DRAINING／FAULTED 與原因。
- **Models & Engines**：revision、engine build、backend、placement、trust、capability、allocation 與 health。
- **Requests & Jobs**：principal、queue position、phase、deadline、cancel、terminal、reply-loss/recovery 狀態。
- **Resources**：每維 budget、reserved、allocated、safety margin、來源、sample age、confidence 與 blocked allocation。
- **Performance**：TTFT、throughput、latency distribution、queue、thermal；operational 與 benchmark 分開。
- **Clients**：registration/token、scope、quota、last seen、active/queued、revocation progress。

## 3. 數據語義
每個數值標示 MEASURED／ESTIMATED／REPORTED／LAST_SAMPLED／UNKNOWN、timestamp、unit、method version 與適用 envelope。UNKNOWN 不能顯示 0。Percentile 只由 versioned sketch/histogram 計算；不同 profile 不合併。isolated worker self-report 不能把 charge 降到 reservation floor 以下。

## 4. 原因與動作
Reason model 將 raw event 映射為穩定 reason code、受影響 capability/resource/principal 與安全動作，例如降低 context、等待 thermal、卸載、取消 pin、重新配對、重新選檔、下載可信模型。建議不得繞過 trust 或 Governor。

## 5. 權限與隱私
本機 Admin 可看完整但仍 redacted 資料；外部 `metrics.read-summary` 只看 allowlist aggregate。跨 client 不顯示 prompt、output、token、private path 或其他 client 可識別 metadata。敏感 detail 需本機明確操作。

## 6. 驗收情境
1. **資源守恆**：任一 snapshot 中每維 reserved + allocated + safety margin 不超 policy cap；planned eviction 不提前增加 free。
2. **未知值**：無法跨 UID 量測 RSS 時顯示 envelope/UNKNOWN 與 age，不顯示偽精確 0 或即時值。
3. **降級一致**：backend fallback、thermal throttle、trust drain 或 worker loss 在 UI、HTTP/AIDL metadata 與 trace 使用同一 reason code。
4. **隱私隔離**：`metrics.read-summary` client 不能看 prompt、完整模型來源、raw path、其他 principal 活動或 crash dump。
5. **恢復**：Runtime restart 後，Dashboard 從 snapshot/high-watermark 重建 jobs/requests/allocations；不重複或漏掉 terminal。
6. **比較正確性**：profile 不相容的 benchmark run 不自動顯示勝負；差異維度清楚列出。
