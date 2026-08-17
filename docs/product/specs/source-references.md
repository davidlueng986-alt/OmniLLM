---
id: "SPEC-SOURCES"
title: "外部權威來源登錄"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "GUIDANCE"
owner: "architecture-governance"
lastReviewed: "2026-07-31"
---

# 外部權威來源登錄

`specs/source-register.yaml` 是 source ID、URL、查核日期、claim、受影響 authority 與 recheck trigger 的機器權威。本文件只作閱讀入口。

主要類別：Android Service／Binder／FGS／UIDT／16 KB／target API、Google Play FGS declaration／AI-generated content policy、RFC 8785、SQLite composite FK，以及五個引擎的官方 upstream repository。

## 維護規則
- URL 只識別來源，不等同可重建 engine/toolchain 版本；
- 每個 Engine Pack 的實作期 lock 另保存 commit／tag、patch、build options 與 artifact digest；
- 政策 claim 在 recheck trigger 發生時重新查核並更新 `retrievedAt`；
- 無法從來源支持的宣稱不得補寫到 normative 文件。
