---
id: "PROD-MODES"
title: "產品模式與使用邊界"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "product-architecture"
lastReviewed: "2026-07-31"
---

# 產品模式與使用邊界

## 1. 模式不是不同產品
OmniLLM 以同一核心平台支援多種使用模式。模式只改變入口、授權與資訊密度，不改變 canonical request、capability、resource、Session、trust 與 error semantics。

## 2. Local User Mode
預設入口。使用者直接在 App 內完成裝置偵測、模型取得、安裝、Chat、Dashboard 與診斷。API token、LAN、engine detail 與 raw metrics 放在進階區，不干擾一般流程。

## 3. Developer Server Mode
允許 loopback HTTP client 或第三方 App 透過 AIDL 調用。使用者建立具名 principal 與最小 scope token；每個 client 可獨立撤銷、限流與檢視活動。Server Mode 不能繞過同一 Orchestrator、公平性與 Governor。

## 4. LAN Mode
預設關閉。使用者明確啟用後，系統建立 TLS endpoint、短期配對流程、connection epoch 與 scoped client token。LAN Mode 不使用 Host／CORS 作為認證替代，也不把長期 secret 放入 QR code。

## 5. Research Mode
提供較完整的 measurement profile、backend 選擇、engine detail、raw diagnostics 與 benchmark；仍受相同 trust placement、resource admission 與 data minimization 約束。Research Mode 不代表可以靜默忽略安全限制。

## 6. Risky Performance Mode
只適用於使用者已理解風險且明確允許的模型／engine 組合。因同 UID worker 不能構成安全邊界，不受信任 accelerated inference 必須使用不同 package／UID 的 companion sandbox；若沒有此機制，該路徑只能對可驗證簽章工件開放。

## 7. 模式切換不變式
- 切換模式不改變 model identity 或 request semantics。
- 關閉高風險模式只能影響未來 placement，不能宣稱已發生的資料外洩可逆；應輪替 token、kill risky worker 並重新驗證 privileged artifacts。
- 撤銷 token、ACL 或模式授權會 bump revocation epoch，拒絕新請求、清除 queue、取消 active stream 並處理 owner Session。
