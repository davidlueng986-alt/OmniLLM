---
id: "GOV-ASSUMPTIONS"
title: "假設與實作驗證義務"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "architecture-governance"
lastReviewed: "2026-07-31"
---

# 假設與實作驗證義務

## 1. 性質
以下不是未決設計，而是建置後才能取得的經驗證據。設計已規定證據缺失時的安全行為：UNKNOWN、worker placement、保守envelope或unsupported。

## 2. 義務
- 每個EngineBuild建立精確upstream/toolchain/artifact lock。
- 每個backend／device／model envelope量測phase cancellation與最大不可搶占時間。
- 驗證CPU/GPU/NPU resource envelope與保守margin。
- Android每次release複核target SDK、FGS、16 KB、DFM、Play policy。
- 對model parser、JSON/PFD、URL/DNS、canonicalization執行fuzz/negative corpus。
- 對reply loss、kill-at-boundary、Session poison、allocation barrier執行fault injection。
- 量測SLO必須附完整profile與percentile，不升格成平台無條件保證。

## 3. Evidence失效
Engine build、OS/driver、model revision、template/tokenizer、workload envelope、policy method或toolchain任一改變時，相關evidence失效或重新匹配；不得以「以前通過」默認沿用。
