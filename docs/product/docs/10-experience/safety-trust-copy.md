---
id: "UX-SAFETY-COPY"
title: "安全、信任與風險文案"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "security-ux"
lastReviewed: "2026-07-31"
---

# 安全、信任與風險文案

## 1. 文案維度
每個模型卡與執行畫面分開呈現：

1. **來源**：簽章 catalog、釘選下載、使用者匯入、未知。
2. **相容性**：未檢查、靜態相容、probe 成功、實際執行成功、已過期。
3. **執行位置**：privileged runtime、same-UID worker、isolated CPU worker、different-package accelerated sandbox。
4. **授權**：需接受、已接受、已撤回、條款更新。

不得合成「安全分數」或用 green badge 同時代表上述四件事。

## 2. 標準文案
- **權限隔離 CPU**：「此模型在獨立系統帳號中執行，無法直接讀取 OmniLLM 的私有資料。仍可能造成該次推理崩潰、資源壓力或不正確輸出。」
- **同 UID worker**：「此模式只隔離崩潰，不是資料安全沙箱。該程式碼與 OmniLLM 共用應用帳號，可能接觸應用可存取的資料與能力。」
- **不同 package／UID 加速沙箱**：「加速推理在獨立安裝單元與系統帳號中執行，只能透過受限介面取得模型與請求資料。仍存在 driver、資源耗盡與側通道風險。」
- **相容性成功**：「此組合曾在目前裝置與指定條件下成功執行；這不是模型來源或安全證明。」

## 3. 不可逆風險
關閉 risky mode 後不得顯示「已回復安全」。應顯示：「已停止未來使用此模式；若先前模型含惡意程式，已發生的資料存取無法撤回。OmniLLM 已終止 worker、輪替 client token 並重新驗證可信模型；若偵測到完整性異常，請執行安全重置。」

## 4. 使用者確認
Risk acknowledgement 必須綁 model revision、artifact、principal、Android user、warning version、nonce、issued/expires time，且一次性消耗。確認頁的同意選項不得預先勾選。
