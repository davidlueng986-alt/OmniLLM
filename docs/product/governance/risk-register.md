---
id: "GOV-RISKS"
title: "殘餘風險登錄"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "architecture-governance"
lastReviewed: "2026-07-31"
---

# 殘餘風險登錄

| ID | 殘餘風險 | 設計處理 |
|---|---|---|
| R-001 | 惡意native可超出memory envelope造成系統壓力 | sandbox、conservative floor、quota、pressure fail-safe與kill；不宣稱硬RSS上限 |
| R-002 | OEM driver/kernel缺陷可跨process影響系統 | qualification、worker isolation、fallback、device deny/quirk與明示殘餘風險 |
| R-003 | 模型輸出可能有害或錯誤 | 不把 trust 等同內容品質；提供 App 內回報／旗標、清楚警示與診斷，但不保證輸出正確 |
| R-004 | iOS等平台無Android式isolated UID能力 | platform placement matrix；無法提供邊界時拒絕該組合 |
| R-005 | Catalog root compromise | threshold signatures、root rotation、revocation、fresh verification；仍需incident recovery |
| R-006 | 使用者主動分享diagnostic後資料離開控制 | export preview、redaction、加密選項與清楚警示 |
| R-007 | Background execution受OS政策限制 | Job pause/wait foreground，避免固定恢復時間承諾 |
