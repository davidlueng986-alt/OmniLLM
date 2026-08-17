---
id: "CORE-SESSION"
title: "Session、Context 與 KV 設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "core-platform"
lastReviewed: "2026-07-31"
---

# Session、Context 與 KV 設計

## 1. Session 身分與 owner
SessionDescriptor 至少包含：`sessionId`、`sessionEpoch`、`ownerKey`、`modelRevisionId`、`loadKey`、`templateEpoch`、`tokenizerDigest`、`contextConfig`、`committedFingerprint`、`state` 與 `allocationHandleId`。

`ownerKey` 包含 principal、Android user、revision 與 load key。自動候選、prefix hint 與重用不得跨 owner；明示 sharing 需獨立 feature 與 ACL。

## 2. SourceSessionRef
```text
None
Existing(sessionId, sessionEpoch, ownerKey)
```

不使用 magic `sourceEpoch=0/-1`。第一個請求以 `None` 進入 model-level planInference，消除 API 循環。

## 3. Prefix decision
Engine 內部先套 chat template、tokenize、建立完整 token fingerprint，再決定：

- `NONE`
- `EXACT_SAME_SESSION`
- `EXACT_CROSS_SESSION`
- `TRUNCATE`
- `FORK`

Plan 只描述決策與 resource delta；commit 才執行 mutation。Committed token count 單獨不足以證明 exact prefix。

## 4. Commit checkpoint
分開記錄：

- `PROMPT_COMMITTED`
- `ASSISTANT_PRODUCED`
- `ASSISTANT_ACKNOWLEDGED`
- `TERMINAL_COMMITTED`

AIDL 的 application ACK 可推進 `ASSISTANT_ACKNOWLEDGED`；SSE write completion 不可。Transport disconnect 時，只有 engine 支援 rollback 且 checkpoint 可證明才回復；否則 Session 進 POISONED 或關閉。

## 5. Session 狀態
`NEW → ACTIVE → DRAINING → CLOSED`，旁支 `POISONED`、`ORPHANED`。POISONED 不可回池；ORPHANED 只供 reconciler 識別，不能自動續用。

## 6. Allocation ownership
Session KV、persistent grammar state 與 engine session workspace 由 Session AllocationHandle 計費。Request terminal 只釋放 temporary workspace；Session 關閉並觀測 native release barrier 後才釋放 allocation。

## 7. Pool policy
- Pool partition：owner + revision + load key + template/tokenizer epoch。
- Candidate 必須 health、state、context、prefix capability 與 revocation epoch 匹配。
- LRU／TTL 只在未 pin、無 active request、可安全 close 時執行。
- Memory pressure 下先 drain idle Session，再考慮 model eviction。

## 8. Concurrent sessions
控制面 command 可序列化，但 decode 資料面可依 engine scheduler 並行。Engine Pack 必須說明 KV mutation critical section、batch scheduler、fairness 與 cancel granularity。共同合約不宣稱 parallelSessions 不可能，也不預設一定可行。
