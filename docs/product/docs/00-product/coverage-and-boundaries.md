---
id: "PROD-BOUNDARIES"
title: "產品能力覆蓋與邊界"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "product-architecture"
lastReviewed: "2026-07-31"
---

# 產品能力覆蓋與邊界

## 1. 完整能力觀
OmniLLM 的設計覆蓋文字生成、embeddings、多模態輸入、structured output、tool calling、模型取得、LAN、AIDL、Dashboard、benchmark、diagnostics、多模型 routing 與多引擎聚合。這些能力不以單一發佈批次切割；是否能在某裝置與某模型上使用，由 capability matrix、trust placement、resource policy 與 engine qualification 決定。

## 2. 不支援的行為類型
下列行為不是 OmniLLM 核心：

- 雲端帳號、雲端推理計費或集中式 prompt 儲存。
- 模型訓練、微調、資料標註平台。
- 跨裝置分散式 token generation。
- 對任意第三方 API 的完全行為相容宣稱。
- 把模型 dry-load 成功當作來源安全證明。
- 把同 UID process 稱為權限沙箱。

## 3. 相容性邊界
「OpenAI-compatible」只適用於明確列出的 HTTP profile；不支援的欄位依 error／ignored policy 處理。AIDL 是 Android-native contract，不假設與 HTTP transport 同樣的 delivery guarantee。引擎能力不應被 lowest-common-denominator 限制；可選能力以 negotiation 揭露。

## 4. 安全邊界
產品可降低不受信任模型與 native parser 的影響，但不能宣稱消除惡意模型造成的所有 DoS、side-channel、錯誤輸出或未知 driver bug。不同 UID／package 的 sandbox 是機密性與完整性邊界；同 UID worker 僅是 crash containment。

## 5. 性能邊界
模型可否運作、可否安全放置、速度如何，是三個不同問題。推薦系統不得把較快等同較安全，也不得把一台裝置的測量外推成所有裝置的保證。
