---
id: "ARCH-SEQUENCE-FLOWS"
title: "關鍵序列流程"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "architecture"
lastReviewed: "2026-07-31"
---

# 關鍵序列流程

## 1. 首次模型載入
```text
Client → Runtime: LoadRequest(requestId, revision, config)
Runtime → EngineRegistry: planLoad(revision, config, device, placement)
EngineRegistry → Runtime: LoadPlan(resourceEnvelope, phaseCapabilities, planId)
Runtime → Governor: reserve(ResourceVector, owner, deadline)
Governor → Runtime: Reservation
Runtime → Placement Worker: commitLoad(planId, reservation, commitId)
Worker → Runtime: LoadedModelHandle + AllocationVector
Runtime → Governor: convert reservation → AllocationHandle
Runtime → DB: persist loaded state / operation result
```

Engine 在 load 前提供保守 envelope；不能先 load 再回報估計。

## 2. 第一個 Session／推理
```text
Client → Runtime: InferRequest(sourceSession=None)
Runtime → LoadedModel: planInference(request, SourceSessionRef.None)
LoadedModel → Runtime: Plan(newSessionDelta + prompt/workspace delta)
Runtime → Governor: reserve
Runtime → LoadedModel/Worker: commitInference(commitId)
Worker → Runtime: PreparedOperation(targetSessionHandle)
Runtime → Worker: start(operationId)
Worker → Runtime: Event batches
Runtime → Client: HTTP/AIDL projection
Runtime: terminal + allocation/accounting update
```

第一個請求不依賴既有 EngineSession，消除 createSession/analyzePrepare 循環。

## 3. Prefix reuse
Engine 在內部完成 template/tokenization 與 token sequence fingerprint。Plan 回傳 `NONE／EXACT_SAME_SESSION／EXACT_CROSS_SESSION／TRUNCATE／FORK`，且每項由獨立 capability 控制。Commit 驗證 source session epoch、owner、fingerprint 與 one-shot plan；無法 rollback 的 post-commit failure 會 poison target。

## 4. Reply loss
Client-generated requestId/idempotencyKey 先被 runtime claim。每個跨 process commit 有 commitId 與 durable intent/result：

```text
送出 commit → worker 完成 → reply 遺失
client/runtime 重連 → queryRequest/queryCommit
→ existing result 或 uncertain outcome reconciliation
```

禁止用新 ID 盲重試。

## 5. AIDL streaming
Service 回傳 `IStreamSession`，client 以 credit 開窗。Batch seq 採 half-open `[from,to)`；terminal 可獨立一批，或 client 可對 terminal batch 的 lastDataSeq 執行 final ACK。Observer death 觸發 policy-defined cancel／detach，並由 subscription handle 清理。

## 6. SSE streaming
HTTP stream 前的錯誤用正常 HTTP response；第一個 SSE event 送出後，錯誤以 terminal event 表達。SSE 斷線不能證明 delivery，Session 預設不自動重用；若未來提供 app-level ACK／resume，必須建立獨立 feature contract。

## 7. 模型安裝
URL/SAF → typed acquisition job → quarantine → size/digest/range verification → isolated parser → source/trust evaluation → license → fsync files/dirs → atomic promotion → DB transaction → READY。任何 crash 由 reconciler 以 content identity 與 state journal 收斂，不以 filename 猜測。
