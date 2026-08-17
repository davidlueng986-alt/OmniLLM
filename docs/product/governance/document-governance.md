---
id: "GOV-DOC"
title: "文件治理規則"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "architecture-governance"
lastReviewed: "2026-07-31"
---

# 文件治理規則

## 1. 文件類型
- `NORMATIVE`：現行設計語義與不變式；
- `GUIDANCE`：解釋策略，不得改寫 normative contract；
- `EVIDENCE`：稽核、測量或實作驗證，不具有產品規範權威；
- `ARCHIVE`：歷史版本，只供追溯。

`FUTURE_BASELINE` 是 status，不是 authority；用於 iOS／PC／IoT 架構方向，不包含日期或開發排程。

## 2. Metadata
`specs/document-metadata-schema.yaml` 是欄位權威。每份現行文件有唯一 `id`、`title`、`status`、`stage`、`authority`、`owner`、`lastReviewed`。Status 可為 `DRAFT／REVIEW／BASELINE／VALIDATION_REQUIRED／FUTURE_BASELINE／RETIRED`。

`VALIDATION_REQUIRED` 表示設計語義已決定但需要實作期 evidence；不能用它掩蓋互斥方案。Engine 文件另以 `qualificationStatus` 表示 runtime cell 是否有證據。

## 3. 單一權威
同一概念只有一個 authority path，列於 `governance/authority-map.md`／`specs/authority-registry.yaml`。其他文件引用 stable document ID 與 canonical token。Audit／historical closure 位於獨立附件包，只能提供 evidence，不能覆寫產品規範。

## 4. 變更規則
改變 architecture invariant、identity、trust boundary、resource ownership、delivery guarantee、state machine、scope 或 error 需 ADR。Feature／Engine 內部細節若不影響核心，只由相應 owner review。

## 5. 完整性檢查
Repository validator 驗證 metadata、unique ID、YAML／SQL、relative link、authority／feature／capability／state／error／scope reference、OpenAPI／IDL schema、index／manifest 與 generated compendium drift。FSM 深度稽核是獨立 evidence package；產品包只保存可重跑的維護 validator，不包含歷史稽核 finding。

## 6. 禁止事項
- 不以開發排程或版本 scope 代替完整產品能力設計；
- 不在 BASELINE 文件保留 placeholder／互斥未決方案；
- 不寫「當日最新」upstream 或未釘選版本；
- 不讓 generated artifact 與手寫文件同時成為 authority；
- 不把缺少實作 evidence 說成 pre-build 設計缺陷，也不把未執行 evidence 說成已驗證。
