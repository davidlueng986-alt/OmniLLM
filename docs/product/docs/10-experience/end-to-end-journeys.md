---
id: "UX-JOURNEYS"
title: "端到端使用者旅程"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "product-design"
lastReviewed: "2026-07-31"
---

# 端到端使用者旅程

## 1. 第一次本機推理
1. 使用者選擇「在這台裝置使用」。
2. 系統建立 DeviceExecutionFingerprint，顯示可理解的裝置摘要與信心。
3. 使用者從建議模型、catalog 或本機檔案選擇來源。
4. 系統顯示來源、license、預估儲存、可用 backend、風險與替代方案。
5. 下載／匯入進 quarantine；可取消、可恢復、可處理 provider death。
6. parser 以權限隔離讀取 bounded metadata；內容驗證後原子 promotion。
7. Recommendation 產生可解釋 plan；Governor 先 reserve，Engine 再 load。
8. Playground 以 client-generated requestId 發出請求；串流、取消與 terminal 可觀測。
9. Dashboard 顯示 model、engine、backend、TTFT、TPS、resource source 與 thermal。

## 2. 開發者連接本機服務
1. 使用者進入 Server & Clients，建立具名 client principal。
2. 產品顯示一次性 token 原文、scope、expiry、loopback endpoint 與 SDK／curl 範例。
3. client 先呼叫 health/capabilities，再依可用能力送出 request。
4. requestId/idempotencyKey 由 client 生成；reply loss 後以 query 取得唯一狀態。
5. 使用者可看見 client queue、active request、用量與撤銷入口。
6. 撤銷會在明確 deadline 內關閉 active stream、移除 queue 並清理 owner Session。

## 3. 模型不相容
1. 靜態檢查或 probe 回傳 `UNSUPPORTED`／`CONDITIONAL`。
2. UI 顯示具體不相容維度：格式、engine build、driver、memory、trust placement 或 capability。
3. 系統提供明示替代：換 engine、CPU fallback、較小 context、另一模型或不同量化。
4. 未經 caller 允許，不跨 revision fallback。
5. 所有選擇與實際執行 revision 寫入 request trace。

## 4. 推理中斷與恢復
- AIDL：以 application ACK 建立 delivered checkpoint，重連後可從有效 cursor 恢復；terminal batch 允許 final ACK。
- SSE：socket write 不代表 client 已看見；若沒有應用層 resume/ACK，斷線後 Session 不自動回池或延續。
- worker death：任何可能部分 mutation 的 Session 進 POISONED；runtime 依 durable operation record reconciliation。
- runtime death：worker 監控 supervisor binder，舊 epoch 停止新操作並退出；新 runtime 以 journal／DB 重建可證明狀態。

## 5. LAN 配對
1. 使用者明確啟用 LAN，查看網路名稱、TLS key 指紋與 connection epoch。
2. 另一裝置以短期高熵配對碼／QR 啟動 channel-bound pairing；QR 不含長期 bearer secret。
3. server 驗證 attempt、TTL、rate limit 與 TLS binding，交換 scoped token。
4. client 顯示 server identity；憑證或 network epoch 改變時重新確認。
5. 撤銷 token 後，既有 connection 在 policy deadline 內終止。


## 8. 生成內容回報旅程
`輸出內容 → 回報內容 → 選擇類別 → 檢視將送出的最小資料 → 確認 → 提交／離線排隊 → receipt／重試／刪除`。流程留在 App 內；完整 prompt/output 預設不被選取。
