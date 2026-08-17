---
id: "GOV-STATUS"
title: "設計狀態總表"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "architecture-governance"
lastReviewed: "2026-07-31"
---

# 設計狀態總表

## 1. 核心設計
產品、體驗、架構、核心平台、領域資料、安全可靠性與Android平台文件均為`BASELINE`：核心語義、責任、狀態、失敗、資源、信任與延伸邊界已定義。

## 2. 引擎整合
共同引擎標準與五份 Engine Pack 的**文件設計狀態**均為 `BASELINE`；五個引擎的 `qualificationStatus` 均為 `UNQUALIFIED`。精確 commit、build artifact、device／driver、phase cancellation 與 resource envelope 必須在實作期產生 evidence。設計完成不等於 runtime supported，未有 qualification record 的 cell 一律暴露為 `UNKNOWN`。

## 3. 未來平台
iOS、PC、IoT為`FUTURE_BASELINE`：portable core與平台映射已定義，但不是開發日期或產品刪減表。

## 4. 無未決互斥方案
現行repository沒有同一能力在不同文件被同時定義為包含與排除，沒有以未知 capability 當supported，沒有「先執行再估算」循環，也沒有把same-UID worker宣稱為安全沙箱。
