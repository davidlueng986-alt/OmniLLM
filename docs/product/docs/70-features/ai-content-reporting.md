---
id: "FEAT-AI-CONTENT-REPORT"
title: "AI 生成內容回報與旗標"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "trust-safety"
lastReviewed: "2026-07-31"
---

# AI 生成內容回報與旗標

## 0. 核心價值對應
- **低技術門檻自動架設**：使用者不需離開 App 即可對不當、危險或誤導輸出採取行動。
- **統一調用**：所有本機推理 UI 使用同一 `ContentReport` identity、category、state、privacy 與 receipt 語義。
- **可視化監控**：使用者可看見 report 是否待送、已送、失敗或已刪除；不把內容回報混入一般 telemetry。

## 1. 入口
每個由 OmniLLM 本機 UI 顯示的生成結果，在內容附近提供清楚標示的「回報內容」動作；不能藏在離開 App 才能到達的網頁。錯誤訊息、診斷與歷史頁也可開啟同一 report flow。

## 2. Report payload
Required：`reportId`、category、createdAt、appBuild、modelRevisionId、engineBuildId、backend、local policy version、output digest、user locale。Optional：使用者主動勾選的輸出 excerpt、prompt excerpt、自由文字與診斷摘要。

預設不包含完整 prompt／output、token、私有路徑、client secret、其他 principal 資料或未經使用者確認的附件。UI 在提交前逐項顯示「會送出什麼」。

## 3. Category
至少涵蓋：仇恨／騷擾、性內容、兒少安全、暴力／自傷、非法活動、欺騙／冒充、隱私／個資、危險建議、其他。Category catalog 可演進，但未知 category 不能被 server 當作無害。

## 4. 狀態與離線
使用 `CONTENT_REPORT` 狀態機：`DRAFT／REVIEWING → QUEUED_OFFLINE／SUBMITTING`；在送出期間取消會進 `CANCELLING`，回覆遺失或 server outcome 不明會進 `RECONCILING`，只有查到 receipt 才進 `SUBMITTED`。 Online confirm 會在同一 transition freeze 使用者核准 payload 並啟動 `submitReport`；不得只改狀態而未發送。網路不可用時，使用者可選擇加密排隊或保留草稿；不得在未確認下自動外送。重送以 `reportId`／idempotency key 保證一次接收。使用者可在 server 接受前取消；本機可刪除 queued payload，若取消與 server 接受競態則以查詢結果誠實收斂。

## 5. Developer endpoint 與回應
Play 分發 profile 必須設定可用的 developer reporting endpoint、privacy disclosure、retention 與處理聯絡方式。Server 回 `receiptId`、acceptedAt 與 status URL；本機提供 create／query／cancel／discard，任何 reply loss 以相同 reportId 查詢，不建立第二份 report。`discard` 僅適用尚未被 server 接受的 draft／queued／failed report；`SUBMITTING`／`CANCELLING`／`RECONCILING` 必須先查明 outcome，`SUBMITTED` 只能依 retention/data-deletion policy 移除本機 payload或receipt副本，不能把已被 developer 接受的 report 偽裝成未送出。Endpoint 不得把 report payload用於廣告、模型訓練或一般 telemetry，除非另有獨立明確同意。

## 6. 安全與隱私
- Transport 使用 Security Profile 的 TLS；endpoint allowlist 與 certificate lifecycle 受管控。
- Payload 有 hard byte／field count／excerpt cap；自由文字與附件經輸入驗證。
- Report queue 加密、owner-bound、具 TTL；log 只保存 digest／status，不記 raw content。
- Abuse：rate limit、duplicate grouping、malicious attachment rejection；不能因使用者大量回報而阻斷本機推理。

## 7. 可觀測性
顯示 queued／submitting／submitted／failed／discarded，並提供明確 retry/delete。營運 metrics 只計數與 category aggregate；內容本身不進一般 dashboard。

## 8. 驗收情境
1. 任何 OmniLLM UI 生成結果可在 App 內開啟回報，不需跳出 App。
2. 預設 payload 不含 prompt／完整 output；只有使用者明示選取 excerpt 才加入。
3. 離線排隊、process death、重送與 reply loss 收斂到單一 receipt，不重複建立 report。
4. 使用者在送出前可取消；queued report 可刪除，且刪除後 payload 不再被送出。
5. Report endpoint 不可用時，本機推理與 history 仍可使用；UI 誠實顯示未送達。
6. TalkBack、鍵盤、large text 與非色彩提示均可完成 category、review、confirm 與 cancel。
