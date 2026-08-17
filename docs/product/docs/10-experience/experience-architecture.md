---
id: "UX-ARCH"
title: "使用者體驗架構"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "product-design"
lastReviewed: "2026-07-31"
---

# 使用者體驗架構

## 1. 體驗模型
OmniLLM 的資訊架構以使用者意圖而非底層 module 分組：取得模型、開始推理、連接開發工具、理解系統、修復問題。Engine、backend、driver、KV 與 process 只在能幫助使用者決策時顯示。

## 2. 五個主要工作區
1. **Home**：目前服務狀態、可立即執行的主要動作、阻斷與恢復提示。
2. **ModelHub**：探索、匯入、下載、安裝、信任、相容性、載入、刪除與 benchmark。
3. **Playground**：Chat、embeddings、multimodal、structured output 等推理體驗；能力不可用時明示原因。
4. **Server & Clients**：loopback／LAN endpoint、AIDL client、token、scope、connection 與撤銷。
5. **Dashboard & Diagnostics**：service、engine、request、resource、thermal、measurement、events 與匯出。

## 3. 兩層資訊密度
- **Standard**：以任務、建議、風險與下一步為主。
- **Expert**：顯示 engine build、backend、driver fingerprint、load key、profile、raw error、queue policy 與 resource vector。

兩層讀取同一狀態模型；Expert 不是另一套 hidden semantics。

## 4. 狀態呈現原則
- 每個非穩定畫面都要有 loading、empty、partial、degraded、blocked、error、recovering 與 stale-data 狀態。
- 任何自動降級都同時顯示原選擇、實際選擇、原因與如何恢復。
- 任一數值附資料來源與時間；未知不顯示為零。
- 高風險選項不得以預設勾選、模糊正向色彩或僅強調速度。

## 5. 共同互動契約
所有長工作回傳 Job／Operation handle，畫面 rotation 或 process death 後可重新訂閱。取消、重試與恢復必須是狀態機合法動作，不以畫面是否存在決定工作生命週期。
