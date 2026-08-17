---
id: "CORE-ORCHESTRATOR"
title: "Orchestrator 與排程設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "core-platform"
lastReviewed: "2026-07-31"
---

# Orchestrator 與排程設計

## 1. Request pipeline
`RECEIVED → CLAIMED → PLANNING → QUEUED → RESERVED → COMMITTING → PREPARED → STARTING → STREAMING → TERMINATING → COMPLETED/FAILED/CANCELLED`；commit結果不確定時由`COMMITTING → RECONCILING → PREPARED/FAILED/ABORTED_UNCERTAIN`。完整事件、guard與action以`specs/state-machines.yaml#REQUEST`為權威。

Request Registry 在任何高成本工作前以 `(principal, operationKind, idempotencyKey)` claim canonical request hash；相同 key 不同 payload 回 conflict。

## 2. Candidate planning
Orchestrator 依序過濾：

1. requested capability；
2. model revision／alias resolution；
3. caller fallback policy；
4. trust placement；
5. engine/backend/device capability；
6. Session ownership/fingerprint；
7. resource envelope；
8. policy、health、thermal 與 revocation epoch。

每個候選保留 rejection reason，供 UI／diagnostics 解釋。不得先隨機挑 engine，再把不支援參數靜默丟掉。

## 3. 排程規則
使用 **per-principal FIFO + global deficit round-robin**：

- 同 principal 內保持 FIFO；
- global scheduler 以 cost unit、公平權重與 deficit 選擇下一個可准入工作；
- load/probe/benchmark/generation 使用不同 cost class，但共享明確 policy；
- tie-break 使用 enqueue sequence；
- earliest-start 是估計值，附 policyVersion 與 confidence，不是 SLA。

## 4. 多模型與 routing
多模型不是布林捷徑。Routing request 可指定：

- exact revision；
- alias；
- capability/query policy；
- fallback policy：`NONE／SAME_REVISION_ONLY／ALLOW_LIST`；
- quality/latency/resource preference。

跨 revision fallback 只有 caller 明示 allowlist 才可發生，並在 response/trace 揭露實際 revision。

## 5. Cancellation 與 deadline
OperationContext 包含 request deadline、phase deadline、cancel token、principal、epoch。每個 phase 依 qualification 選 cooperative cancel、bounded non-preemptible、worker kill 或 unsupported。Deadline 到期後不假設 native call立即返回；控制面可 fence output、kill worker、poison Session 並 reconcile。

## 6. Idempotency 與 recovery
- Request、Commit、PreparedOperation、Admin Command、Job 都有不同 namespace 的 ID。
- Reply loss 後先 query，不盲重試。
- Runtime restart 後從 durable state 重建 queue；只有狀態可證明可續作的 job／operation 才恢復。
- `ABORTED_UNCERTAIN` 不自動跨 revision／operation 重做。

## 7. Backpressure
AIDL 使用 credit／ACK；HTTP SSE 以 bounded outbound queue、socket cancellation 與 stateless-by-default Session policy。Local queue 滿、client 未 ACK、Binder failure、network backpressure 是不同 reason。

## 8. Admin 與 UI 公平性
UI trial inference 與外部 chat 使用同一 scheduler、Governor 與 principal model；Admin 入口不能繞過公平性。Maintenance job 可有 policy priority，但不得搶占已承諾不可中斷的 native phase而造成帳本失真。
