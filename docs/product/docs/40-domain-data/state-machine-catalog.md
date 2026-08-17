---
id: "DATA-STATES"
title: "狀態機目錄"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "data-architecture"
lastReviewed: "2026-07-31"
---

# 狀態機目錄

## 1. 單一權威與投影規則
`specs/state-machines.yaml` 是 state、event、guard、action、invariant 與 terminal 的機器權威。本文件只提供閱讀摘要；不得新增未出現在 YAML 的狀態名稱。UI 可以使用友善文字，但必須保存 canonical state ID。

## 2. Request：`REQUEST`
`RECEIVED → CLAIMED → PLANNING → QUEUED → RESERVED → COMMITTING → PREPARED → STARTING → STREAMING → TERMINATING`，並在 commit 回覆不確定時進 `RECONCILING`。Terminal 為 `COMPLETED`、`FAILED`、`CANCELLED`、`ABORTED_UNCERTAIN`。Pre-commit cancel 無 Session／KV mutation；post-commit failure 採 rollback-or-poison。

## 3. Commit：`COMMIT`
`INTENT_RECORDED → EXECUTING → RESULT_RECORDED → COMMITTED／ABORTED`。若 reply／worker lost，`EXECUTING → RECONCILING → COMMITTED／ABORTED／UNCERTAIN_QUARANTINED`。同一 `CommitId` 的 canonical input 唯一，未知結果不可盲重播。

## 4. Reservation：`RESERVATION`
`HELD → CONVERTING → COMMITTED` 或 `HELD → RELEASING → RELEASED`，亦可 `EXPIRED`。`COMMITTED` 只表示 reservation 已轉成常駐 `AllocationHandle` 並釋放 remainder；不是 resident resource 已消失。

## 5. Model Installation 與 Loaded Model：`MODEL_INSTALLATION`、`LOADED_MODEL`
`MODEL_INSTALLATION` 與 `LOADED_MODEL` 是兩個獨立 aggregate：Installation 管理 bytes、identity、trust、license 與 delete；LoadedModel 管理 `LoadKey`、native handle、placement、allocation 與 Session references。

- `MODEL_INSTALLATION`：`DISCOVERED → ACQUIRING → QUARANTINED → VERIFYING → COMPATIBILITY_CHECK → READY`，可因 revocation／integrity／delete 進 `DRAINING → REVOKED／CORRUPT／DELETING → DELETED`。
- `LOADED_MODEL`：`PLANNED → RESERVED → LOADING → LOADED → DRAINING → UNLOADING → UNLOADED`；失敗先進 `FAILED_DRAINING`，通過 release barrier 後才進 `FAILED`。

UI 若顯示組合狀態，必須同時保存兩個 canonical state ID；不得把 Installation 的 `READY` 說成 native 已載入。

## 6. Session：`SESSION`
`NEW → ACTIVE → DRAINING／POISONED／ORPHANED → CLOSING → CLOSED`。`POISONED`／`ORPHANED` 不再進 pool；close 需要 native／process resource barrier。

## 7. Job：`JOB`
`QUEUED → RUNNING`，可暫停於 `PAUSED_WAITING_INPUT`／`PAUSED_WAITING_NETWORK`／`PAUSED_WAITING_FOREGROUND`，或 process loss 後進 `RECOVERING`，最後進 `SUCCEEDED`／`FAILED`／`CANCELLED`。每個非 terminal state 都有 cancel／failure 收斂；state、attempt、event、checkpoint 同交易。

## 8. Runtime：`RUNTIME`
`STOPPED → STARTING → RECOVERING → READY／DEGRADED`，再由 `READY／DEGRADED → DRAINING → STOPPED`。`STARTING` 可進 `WAITING_FOR_USER_FOREGROUND`；recovery 失敗可進 `FAULTED`。Runtime 是循環 aggregate，沒有 terminal state。

## 9. Revocation：`REVOCATION`
`ACTIVE → REVOCATION_REQUESTED → FENCING → DRAINING → ROTATING_SECRETS → ENFORCED`。先 durable bump epoch 並拒絕新工作，再清 queue／cancel／drain，最後完成 secret rotation 與 audit；不能在舊能力仍可用時宣稱完成。

## 10. Asset：`ASSET`
`CREATED → UPLOADING → VERIFYING → READY → PINNED`，依 reuse policy 返回 `READY` 或進 `CONSUMED`；任何未 pinned 狀態可因 TTL 進 `EXPIRED`，驗證失敗進 `REJECTED`，owner 可刪除成 `DELETED`。`READY` 只在 materialized bytes 完整驗證後成立。

## 11. Client Registration：`CLIENT_REGISTRATION`
`PENDING → ACTIVE`，可 `SUSPENDED` 並重新核准，或 `REVOCATION_REQUESTED → DRAINING → REVOKED`；challenge／registration 到期必須收斂。Binding 可達不等於 `ACTIVE` 授權，所有呼叫以 observed UID／user、scope 與 epoch 驗證。

## 12. Operation：`OPERATION`
`PREPARED → STARTING → RUNNING`，取消進 `CANCELLING`，回覆／worker loss 進 `RECONCILING`；terminal 為 `COMPLETED／FAILED／CANCELLED／ABORTED_UNCERTAIN`。Cancellation intent durable 後不得重新啟動。

## 13. Engine Module：`ENGINE_MODULE`
`ABSENT → INSTALLING → INSTALLED → VALIDATING → AVAILABLE`；更新／移除先 `DRAINING → RESTART_REQUIRED`，失敗可進 `ROLLING_BACK` 或 `FAILED`。每個 target process 的 artifact／protocol handshake 均通過才可 `AVAILABLE`。

## 14. Allocation 與 Revision Lease：`ALLOCATION`、`REVISION_LEASE`
`ALLOCATION`：`ACTIVE → DRAINING → RELEASING → RELEASED`，release barrier 前不可 credit capacity。`REVISION_LEASE`：`ACTIVE → DRAINING → RELEASED`，expiry 只觸發 drain，不可刪除仍被 request／model／session／job 引用的 revision。

## 15. Command 與 Pairing Challenge：`COMMAND`、`PAIRING_CHALLENGE`
`COMMAND` 以 durable claim／result ledger 收斂 `RECEIVED／CLAIMED／RUNNING／RECONCILING` 到唯一 terminal。`PAIRING_CHALLENGE` 由 `PENDING` 經 local approval 到 `APPROVED → CONSUMED`，或 `REJECTED／EXPIRED`；consume 與 registration／token issuance 原子化。

## 16. LAN Service：`LAN_SERVICE`
`DISABLED → STARTING → ADVERTISING → PAIRING_AVAILABLE → ACTIVE`；rotation／revocation 進 `ROTATING／REVOKING`，停用經 `STOPPING → DISABLED`，錯誤進 `ERROR`。任何 rotation／revocation 都先 bump connection epoch，再 drain 舊 client／queue／Session。

## 17. AI Content Report：`CONTENT_REPORT`
`DRAFT → REVIEWING → SUBMITTING／QUEUED_OFFLINE → SUBMITTED`，可進 `FAILED_RETRYABLE／FAILED_FINAL` 或 `DISCARDED`。使用者在 App 內選擇類別與可選 excerpt；未明示的 prompt／output 不進 report。
