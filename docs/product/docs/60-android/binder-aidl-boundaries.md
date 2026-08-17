---
id: "ANDROID-BINDER"
title: "Binder／AIDL 邊界設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "android-platform"
lastReviewed: "2026-07-31"
---

# Binder／AIDL 邊界設計

## 1. Buffer 與傳輸上限
Binder transaction buffer是process內所有進行中transaction共用的有限buffer，現行文件描述為約1 MiB。因此：

- request、event batch與page採保守上限，SDK先估算parcel size；
- list使用keyset page；
- model、image、audio、diagnostic使用PFD/handle；
- event stream使用bounded batch與credit；
- Service不能保證在transaction抵達前的oversize failure可轉成自訂413。

## 2. Runtime AIDL 形狀
```text
IOmniRuntime
  getCapabilities(query)
  startInference(request, callback) -> IStreamSession
  startEmbedding(request, callback) -> IOperationSession
  queryRequest(requestId)
  getModels(pageQuery)

IStreamSession
  grantCredit(window)
  ack(epoch, lastDataSeq)
  cancel(reason)
  query()
  closeObserver()
```

所有Parcelable有schemaVersion、range、nullable/default與unknown-version policy。行為介面不嵌入Parcelable。

## 3. Principal
Service在transaction入口讀取calling UID與Android user。若需package display，從PackageManager取得候選並顯示「shared identity」；不接受request body自報package作授權。Long-lived stream綁建立時principal與revocation epoch。

## 4. ACK／Credit
- seq採half-open `[from,to)`。
- credit有owner、stream epoch、單調增量與hard cap。
- ACK只可前進、不能超過已送data；重複ACK idempotent。
- Terminal可在最後data ACK後另發，或允許final ACK覆蓋terminal batch的lastDataSeq。
- Callback death依policy cancel/detach，並釋放subscription。

## 5. Oneway 語義
`oneway`只表示caller不等待同步回覆，不代表client已消費event。Backpressure只能由credit/ACK或pull protocol推導，不能以binder call return或本地queue深度宣稱delivery。

## 6. FD ownership
Sender在call後可關閉自己的duplicate；receiver取得獨立FD並負責close。每個handle含owner、TTL、size/digest與single-use policy。Process death由broker清理unclaimed handle。
