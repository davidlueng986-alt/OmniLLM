---
id: "GOV-CHANGE"
title: "設計變更協定"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "architecture-governance"
lastReviewed: "2026-07-31"
---

# 設計變更協定

## 1. 變更分類
- Core semantic：需 ADR、impact map、repository validation 與獨立 FSM audit；
- Feature-local：更新 Feature 文件與受影響 capability／state／scope；
- Engine-local：更新 Engine Pack、EngineBuildId 與 evidence invalidation；
- Platform-local：更新 adapter／policy register／platform matrix，不改 portable semantics；
- Editorial：不改意思，可直接 review。

## 2. 變更檢查
每個變更回答是否改 identity、state、owner、trust、resource、delivery、persistence、error、scope、privacy 或 backward compatibility；是否使既有 acceptance／quality／security scenario 失效；是否需要重跑外部 FSM audit。

## 3. 重新開啟 FSM 的事件
Canonical type major version、Engine contract、persistence schema、state machine、trust／download／pairing／reporting protocol、target platform security boundary、SSE／AIDL delivery、ResourceVector ownership、scope／error catalog或任何 closure verification 變更／失敗。

## 4. Package boundary
產品包不內含歷史 finding closure。外部 audit package 必須記錄所稽核產品 ZIP／manifest digest；產品設計不引用 audit 檔作 authority。
