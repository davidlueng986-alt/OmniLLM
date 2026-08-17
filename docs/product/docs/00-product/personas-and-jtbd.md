---
id: "PROD-PERSONAS"
title: "使用者角色與待完成工作"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "product-design"
lastReviewed: "2026-07-31"
---

# 使用者角色與待完成工作

## 1. 本機使用者
**主要工作**：在不理解底層引擎的情況下，選擇或匯入適合裝置的模型，安全完成推理，知道失敗原因並能恢復。

成功條件：

- 首次流程只要求使用者回答可理解的偏好，例如速度、品質、儲存與資料風險。
- 產品能解釋「為何推薦這個模型／後端」及「若選另一個會發生什麼」。
- 所有高風險模式都用不可逆資料風險語言，不用模糊的「高效能」掩蓋信任差異。
- 失敗頁提供下一步，而非只有 stack trace 或 engine error code。

## 2. 應用開發者
**主要工作**：以一致 API 接入本地模型，不必為每個 engine 寫不同整合；能查詢能力、取消、監控與診斷。

成功條件：

- client 可在送出前得知 capability、transport cap 與可接受參數。
- requestId／idempotencyKey 由 client 先建立，reply loss 後仍可查詢。
- unsupported 參數有一致錯誤或明示 ignored policy，不因裝置設定而改變 public semantics。
- token 權限、撤銷生效時間、rate limit 與 Session ownership 清楚。

## 3. 研究者與效能工程師
**主要工作**：比較模型、量化、引擎、backend 與裝置，在可重現條件下理解性能差異。

成功條件：

- measurement profile 包含 engine build、model revision、tokenizer/template、device/driver、ctx、threads、batch、session count、fixture 與 method version。
- run history 不被 rollup 覆蓋；percentile 由 histogram／sketch 或 raw sample 產生。
- UI 清楚指出哪些結果可比較，哪些因 profile 不同只能個別閱讀。

## 4. 平台維護者
**主要工作**：新增引擎、backend、模型格式或新 OS adapter，而不破壞共同合約、安全邊界與既有 client。

成功條件：

- 引擎整合包能獨立描述 upstream、build、capability、phase cancellation、resource envelope、security placement 與已知限制。
- 核心文件不保存容易過期的「當日最新」資訊，只要求精確 pin、digest 與 observed timestamp。
- 改變 architecture invariant、canonical identity 或 wire semantics 必須有 ADR 與 migration impact。

## 5. 安全／合規審閱者
**主要工作**：確認產品的宣稱不超過機制，模型供應鏈、權限、網路暴露與診斷資料有清楚邊界。

成功條件：

- threat → requirement → control → UX claim → verification 可追溯。
- 來源真實性、相容性、license 與 runtime placement 是分離維度。
- 不受信任 native code 不與 privileged secrets／catalog／model store 共用可寫 UID 邊界。
