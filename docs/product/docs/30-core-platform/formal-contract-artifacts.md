---
id: "CORE-CONTRACT-ARTIFACTS"
title: "Formal Contract Artifacts 與實作邊界"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "core-platform"
lastReviewed: "2026-07-31"
---

# Formal Contract Artifacts 與實作邊界

## 1. 目的
建置前設計不能只靠 prose 讓 HTTP、AIDL、Kotlin、C++ 與 persistence team 自行補欄位；也不能把尚未編譯的設計說成已產生 build evidence。

## 2. 現行 authority
- Canonical type／enum：`specs/canonical-types.yaml`；
- HTTP wire design：`specs/openapi/omnillm.openapi.yaml`；
- Android AIDL semantic IDL：`specs/aidl/omnillm-aidl.yaml`；
- Persistence design：`specs/database/omnillm-schema.sql`；
- Migration design：`specs/database/migration-policy.yaml`；
- State machine：`specs/state-machines.yaml`；
- Scope／error：對應 catalogs；
- Encoding fixture：`specs/golden-vectors/canonical-encoding.yaml`。

## 3. Pre-build 與 build evidence
上述檔案可接受 schema／SQL parser 驗證，屬設計 authority。實作期仍需產出可編譯 `.aidl`／Parcelable、generated Kotlin／C++、Room schema export／migration、OpenAPI client/server conformance 與跨語言 golden-vector result。產品包不偽造這些結果。

## 4. Drift rule
手寫正文不能新增 catalog 不存在的 type／enum／scope／error／state。Generated code 只能從 authority 產生；若 generator 與 source 不一致，source authority 優先且 build gate 失敗。
