---
id: "ARCH-INVARIANTS"
title: "架構不變式"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "architecture"
lastReviewed: "2026-07-31"
---

# 架構不變式

## 1. 核心不變式
| ID | 不變式 |
|---|---|
| `INV-001` | UI process 不載入 native engine、不直接寫 DB／model store。 |
| `INV-002` | 所有高成本或 domain mutation 操作必須先完成可重現 Plan 與多維 Reservation。 |
| `INV-003` | Plan 不修改 Session/KV、model lifecycle、persistent state 或 resource allocation。 |
| `INV-004` | Commit 具 `commitId`，綁 request、owner、plan、lease、epoch；可重試與查詢。 |
| `INV-005` | Request terminal 只釋放 temporary reservation；常駐 model／KV 由 AllocationHandle 計費直到實際回收。 |
| `INV-006` | SSE socket write 不建立 client-delivered checkpoint；AIDL 只由 application ACK 建立。 |
| `INV-007` | 任何可能部分 mutation 或 epoch 不可信的 Session 都不得回池。 |
| `INV-008` | 來源證據決定 privileged placement；dry-load／benchmark 不提升來源信任。 |
| `INV-009` | 不受信任 native accelerated execution 不與 privileged app data／secrets 共用可寫 UID。 |
| `INV-010` | Privileged load 每次驗證所開啟 FD 的 content identity、signature chain 與 revocation state。 |
| `INV-011` | AIDL principal 以 calling UID／Android user 與受驗證 binding 建立，不信任 caller 自報 package。 |
| `INV-012` | client 在送出前建立 requestId／idempotencyKey；服務 claim-or-return-existing。 |
| `INV-013` | 公開 HTTP／AIDL／UI 都投影同一 canonical capability、request、event 與 error；transport guarantee 可不同且必須明示。 |
| `INV-014` | Blob、ArtifactPackage、ModelRevision、Installation、Alias 是不同身分，不互相代替。 |
| `INV-015` | 所有 digest 在 canonical object 中使用 lower-case hex；所有集合具 total order。 |
| `INV-016` | 每個 measurement result 可回到完整 profile、run、method version 與 raw／sketch evidence。 |
| `INV-017` | token、trust、ACL、risk mode 撤銷會 bump epoch 並 fence active／queued／pooled state。 |
| `INV-018` | 未知 capability、未知 cancellation 或未知 memory envelope 不能默認為安全／支援。 |
| `INV-019` | 任何 fallback 必須由 caller policy 允許並回報實際 revision／engine／backend。 |
| `INV-020` | 同一資料欄位只有一個權威來源；其他文件只解釋或投影。 |

## 2. 資源守恆
在任一資源維度 `d`：

```text
cap[d] >= reserved[d] + allocated[d] + safety_margin[d]
```

Eviction 只有在觀測到 allocation 已釋放並完成 barrier 後才回補容量；planned eviction 不得預先折抵。

## 3. 交易邊界
- Pre-commit failure：domain state 不變，reservation 可釋放。
- Post-commit／pre-execute failure：依 engine rollback capability 復原；否則 target Session／LoadedModel 進不可重用狀態並對帳 allocation。
- Reply loss：query commit／operation，禁止盲目重放非冪等 mutation。
- Worker death：不要求已消失的物理 bytes 仍存在；要求沒有可重用部分狀態、孤兒 handle 或低估資源。

## 4. 文件不變式
所有 normative Markdown 有唯一 `id`、owner、status、authority；所有權威路徑列在 `governance/authority-map.md`。歷史 audit 與 archive 不具有現行規範效力。
