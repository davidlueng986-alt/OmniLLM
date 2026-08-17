---
id: "DATA-DOMAIN"
title: "領域模型與聚合邊界"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "data-architecture"
lastReviewed: "2026-07-31"
---

# 領域模型與聚合邊界

## 1. 聚合與責任
| Aggregate | Root／身分 | 核心責任 | 禁止事項 |
|---|---|---|---|
| Model Artifact | BlobId／ArtifactPackageId／ModelRevisionId | bytes、語義manifest、來源與license關聯 | 不以filename／alias作內容身分 |
| Installation | InstallationId | 本機取得、quarantine、verification、READY、reference、delete | 不借用其他revision assertion |
| LoadedModel | LoadedModelId／EngineBuildId／LoadKey | native load lifecycle與resident allocation | 不在無Reservation下load |
| Session | SessionId／epoch／owner | KV、prefix、continuation與pool狀態 | 不跨owner/revision/loadKey重用 |
| Request | RequestId | claim、planning、commit、stream、terminal與query | 不以transport callback作唯一狀態 |
| Commit | CommitId | 跨process mutation intent/result/reconcile | 不盲重播unknown mutation |
| Command | CommandId | 短admin mutation與resourceVersion | 不使用void並宣稱reply-loss安全 |
| Job／Attempt | JobId／AttemptId | 長工作、progress、checkpoint與recovery | 不把ephemeral FD當durable checkpoint |
| Reservation／Allocation | ReservationId／AllocationId | 多維resource守恆、resident ownership與release barrier | 不提前折抵planned eviction |
| Asset | AssetId | bounded upload/PFD materialization、verification、TTL、owner、request pin | 不暴露path或讓未READY bytes進推理 |
| ClientRegistration | ClientRegistrationId／principal epoch | user-approved AIDL/HTTP/LAN caller、scope、expiry與revocation | 不信任caller自報package作principal |
| Measurement | ProfileId／RunId | 可重現profile、run與metric evidence | 不跨profile合併 |
| Trust／License | assertion/event IDs | authenticity、revocation、terms與acceptance event | 不以compatibility提升trust |

## 2. 聚合關係
```text
Blob ─┬─> ArtifactPackage ─> ModelRevision ─> Installation ─> LoadedModel ─> Session
      └─ shared by multiple packages                 │             │
                                                     └─ RevisionLease/Allocation

Principal/ClientRegistration ─> Request/Command/Job/Asset owner
Request ─> Commit ─> PreparedOperation ─> Session disposition
Job ─> Attempt ─> Event/Checkpoint
MeasurementRun ─> MeasurementProfile ─> engine/model/device/workload/method
```

## 3. Asset aggregate
Asset先由owner建立handle，再經HTTP bounded upload或AIDL PFD materialize到quarantine；size/digest/type驗證成功才READY。接受request時以同一transaction檢查owner、TTL、reuse policy並建立pin。Single-use只可claim一次；request完成後CONSUMED／刪除。Asset bytes不等於Model Artifact，只有model import Job完成完整identity/install pipeline後才成為model store內容。

## 4. ClientRegistration aggregate
Registration把系統觀測principal evidence（AIDL UID/user、HTTP/LAN client identity）、使用者核准scope、expiry、revocation epoch與audit綁在一起。Transport可達不等於授權；每次operation仍驗scope、owner、quota、capability與epoch。Shared UID預設是一個principal，除非managed platform提供更強可證明身分。

## 5. Ownership 與 reference
每個Request、Command、Job、Asset、Session與Registration有principal owner；ModelRevision可被多owner引用但admin mutation另授權。Reference/lease是domain record，不以Kotlin object reference代表。Delete、revoke、unload、TTL與process death都透過reference snapshot及state machine收斂。

## 6. Domain events
穩定event包括：ModelInstalled、ModelTrustChanged、LoadedModelStateChanged、SessionPoisoned、RequestStateChanged、RequestTerminal、CommitReconciled、JobProgressed、AssetReady/Expired、ClientRegistered/Revoked、AllocationChanged、WorkerLost。Event能驅動UI snapshot與diagnostics，但current state及durable ledger仍是權威。
