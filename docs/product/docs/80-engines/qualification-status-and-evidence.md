---
id: "ENGINE-QUALIFICATION-STATUS"
title: "引擎設計狀態與 Qualification Evidence"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "engine-architecture"
lastReviewed: "2026-07-31"
---

# 引擎設計狀態與 Qualification Evidence

## 1. 雙狀態
每個 Engine Pack 同時有：
- **document status**：整合語義是否完整；
- **qualificationStatus**：某 `EngineBuildId × backend × device/driver × model/workload × phase` 是否有實作證據。

Pre-build 階段五個 Engine Pack 可以是 `BASELINE + UNQUALIFIED`。這不代表核心設計失敗，也不允許 runtime 宣稱 supported。

## 2. 發布／Registry 規則
只有 `QUALIFIED_WITH_ENVELOPE` cell 可進 supported registry；未 lock upstream/artifact 的 cell 為 UNKNOWN。不同 backend 或 device 不繼承；evidence 過期／driver／engine build／model envelope 改變後回 UNKNOWN。

## 3. 不偽造 evidence
本產品文件不內含 build log、AAB、device run 或 benchmark result。這些是實作期 evidence package，需綁 product manifest digest、EngineBuildId 與 qualification schema。
