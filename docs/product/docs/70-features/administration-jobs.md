---
id: "FEAT-ADMIN"
title: "管理、Command、Job 與長工作"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "feature-team"
lastReviewed: "2026-07-31"
---

# 管理、Command、Job 與長工作

## 0. 核心價值對應
- **低技術門檻自動架設**：模型取得、模組安裝、設定、token 與診斷等長工作以可暫停、可恢復的統一流程呈現。
- **統一調用**：所有 mutation 使用 Command／Job identity、state、event、idempotency 與 query，不以 void 或模組私有 callback 實作。
- **可視化監控**：progress、phase、attempt、checkpoint、blocked reason、取消能力與 recovery state 可在 UI／API 同步觀測。

## 1. 操作分類
- **Command**：短且可在一個 durable mutation boundary 完成，例如 alias、setting、client scope、pin。
- **Job**：下載、匯入、verify/install、benchmark、module install、catalog refresh、diagnostic export、large delete/reconcile。
- **Request**：推理／embedding；不與 Job 共用 idempotency namespace。

## 2. Command
Caller 先產生 `CommandId`／idempotency key。服務以 principal + method + key claim canonical hash，與 mutation 同交易保存 `CommandResult`。Reply loss 後 query／重送回原結果；同 key 不同 payload 回 conflict。每個 result 有 resourceVersion，避免盲目 last-write-wins。

## 3. Job 與 Attempt
Job 保存 canonical spec、owner、state、current attempt、events、checkpoint、result/error與 retention。每次 retry 產生唯一 `AttemptId/attemptNo`；job state、attempt、event、checkpoint在同一 DB transaction更新。不可持久化的 PFD／URI 權限不能被承諾跨重啟。

## 4. Progress 與取消
Progress 分開 network bytes、materialized bytes、verified bytes、parsed items、current phase、sample count與 estimated remaining。未知總量不顯示百分比。取消性依 phase capability；不可立即中斷時顯示 cancel requested／safe stopping，必要時 worker kill並reconcile。

## 5. Observer、Cursor 與 Snapshot
Observer 取得 `SubscriptionId`、window、ACK與 cursor；callback death自動清理。事件 retention gap回 `CURSOR_GONE`，client以 `AdminSnapshot(snapshotVersion, highWatermark, models, jobs, clients, health, settings)` 重建，再從 high-watermark訂閱。分頁用 snapshot-bound keyset與唯一 tie-breaker。

## 6. 權限
第三方 Runtime client不取得 Admin binder。HTTP/AIDL admin operations依 access-control catalog授權；`models.manage`、`clients.manage`、`settings.read`、`settings.write`、`jobs.manage`、`diagnostics.export` 等分離。敏感結果欄位依 scope allowlist。

## 7. 驗收情境
1. **Command reply loss**：mutation 已提交但回覆遺失時，重送同 key 回相同 `CommandResult` 且只執行一次。
2. **Job crash recovery**：在每個 state/event/checkpoint write boundary kill，重啟後 current state可由event log一致重建，不重複 attempt。
3. **Paused cancellation**：`PAUSED_WAITING_INPUT`／`PAUSED_WAITING_NETWORK`／`PAUSED_WAITING_FOREGROUND` 狀態可取消或因 deadline 失敗，不會永久卡住。
4. **Cursor gap**：事件被回收後，公開 snapshot + high-watermark 可重建與未丟事件 client 等價狀態。
5. **慢 observer**：window/ACK限制記憶體與 Binder queue；observer death、rotation、重訂閱不洩漏 subscription。
6. **權限分離**：infer-only client不能執行 delete、setting、token、diagnostic或取得 Admin binder。
