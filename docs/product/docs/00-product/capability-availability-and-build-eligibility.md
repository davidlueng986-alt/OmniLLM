---
id: "PROD-CAPABILITY-ELIGIBILITY"
title: "能力可用性與建置資格模型"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "product-architecture"
lastReviewed: "2026-07-31"
---

# 能力可用性與建置資格模型

## 1. 目的
OmniLLM 設計完整能力宇宙，不以版本式能力刪減或開發排程迴避設計。但「已設計」不等於「在任意 engine／device／model 組合可用」。本模型把三件事分離：

1. **Design status**：語義、state、failure、security、resource、UX 是否完整；
2. **Build eligibility**：是否已有足夠 formal contract 可交給實作者；
3. **Runtime availability**：特定 qualification cell 是否有證據且當下可用。

## 2. 狀態
- `DESIGN_BASELINE`：完整設計且無互斥 authority；
- `CONTRACT_REQUIRED`：需要先完成或更新 canonical schema／state／scope／error；
- `BUILD_ELIGIBLE`：設計與 formal contract 足以實作，但不代表已通過實機 qualification；
- `QUALIFICATION_REQUIRED`：實作後需特定 engine/device/model/workload evidence；
- Runtime 仍使用 `SUPPORTED／UNSUPPORTED／CONDITIONAL／UNKNOWN／TEMPORARILY_UNAVAILABLE`。

## 3. 暴露規則
UI、HTTP、AIDL 與 recommendation 可以顯示所有已設計能力，但只有 runtime cell 為 `SUPPORTED`／符合 `CONDITIONAL` 時可執行。`UNKNOWN` 不得因產品想完整而被假裝 supported；未 qualification 的引擎也不阻止其他引擎／cell 實作。

## 4. 維護
`specs/capability-availability-matrix.yaml` 保存每項 capability 的 design authority、formal contract、qualification obligation與 fail-closed 行為。新增能力只需增加一列及對應 Feature／Engine mapping，不需要重寫全產品 scope。
