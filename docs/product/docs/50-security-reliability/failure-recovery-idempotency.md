---
id: "REL-RECOVERY"
title: "故障、復原與冪等設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "reliability"
lastReviewed: "2026-07-31"
---

# 故障、復原與冪等設計

## 1. 故障分類
- **Pre-accept transport failure**：服務未必收到；client可使用同ID重送。
- **Accepted reply loss**：服務可能已claim；client必須query。
- **Pre-commit failure**：無domain mutation，釋放reservation。
- **Post-commit failure**：rollback或poison/destroy並對帳。
- **Worker death**：native state消失或不可信，fence epoch並reconcile。
- **Runtime death**：durable ledgers重建；worker supervisor death退出。
- **Client disconnect**：transport-specific delivery policy。
- **Storage failure**：transaction未完成、disk full、fsync/rename gap由reconciler處理。

## 2. Identity namespaces
- `RequestId`：推理／embedding。
- `CommitId`：跨process domain mutation。
- `OperationId`：execution與stream。
- `CommandId`：admin mutation。
- `JobId/AttemptId`：長工作。
- `SubscriptionId`：observer。

不同namespace不可共用全域unique idempotency key；唯一鍵包含principal、operation kind與key。

## 3. Durable ledgers
`inference_requests/attempts/events/terminals`、`idempotent_commands`、`jobs/attempts/events`、`commit_records`。每個ledger保存canonical request hash、state、result/error、resource version、epoch與retention。

## 4. Uncertain commit
Worker可能apply後reply loss。Runtime先記intent，再發command；reconnect後query worker/journal/observable state。能證明applied則記result；能證明未apply可重送；無法證明則標UNCERTAIN，隔離相關Session/resource並人工/自動cleanup，禁止重複mutation。

## 5. Cancellation
取消是request，非立即事實。記錄`CANCEL_REQUESTED`、`CANCEL_ACKNOWLEDGED`、`EXECUTION_STOPPED`、`WORKER_TERMINATED`。Terminal只在輸出fenced、Session處理與resource accounting達到明確狀態後寫入。

## 6. Observer recovery
Observer有cursor、window、ACK與snapshot fallback。Callback death自動清理subscription；重新訂閱從last ACK或snapshot high-watermark繼續。

## 7. Recovery UX
使用者看見「正在恢復／等待使用者重新授權／無法確認是否完成」等精確狀態。對不確定結果不顯示「未執行」並自動重做。
