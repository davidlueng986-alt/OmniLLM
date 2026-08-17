---
id: "CORE-MODEL"
title: "模型平台設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "core-platform"
lastReviewed: "2026-07-31"
---

# 模型平台設計

## 1. 兩個獨立 aggregate
模型「已安裝」與「目前被某引擎載入」不是同一狀態機。

### 1.1 `MODEL_INSTALLATION`
`DISCOVERED → ACQUIRING → QUARANTINED → VERIFYING → COMPATIBILITY_CHECK → READY`，並可因撤銷、完整性或刪除進 `DRAINING → REVOKED／CORRUPT／DELETING → DELETED`。

它的 owner 是 Model Platform／single writer，保存 bytes、identity、license、trust、installation 與 request revision lease。`READY` 只表示該 installation 可進入 load planning；不是 native model 已載入。

### 1.2 `LOADED_MODEL`
`PLANNED → RESERVED → LOADING → LOADED → DRAINING → UNLOADING → UNLOADED`；失敗路徑在 release barrier 前保持計費。

它的 owner 是 Runtime／Engine Platform，鍵為 `LoadKey`，保存 native handle、engine build、backend、placement、allocation 與 active Session references。LoadedModel 必須引用一個仍有效的 `READY` installation／revision lease；installation drain 會觸發 loaded-model drain，但兩者不合併成一列狀態。

## 2. 取得通道
- **Signed Catalog**：APK 內嵌完整初始 root metadata；逐版 rotation、expiry、rollback protection 與 revocation。
- **Pinned Download**：HTTPS、明確 host/port、revision、逐檔 size/digest、redirect/DNS policy。
- **Local Import**：SAF URI／PFD 立即複製到 runtime-owned quarantine，或保存可恢復 URI grant；無來源真實性保證。

模型不依賴 Play asset channel；App distribution 與 model distribution 分離。

## 3. Identity pipeline
1. 每檔 raw bytes → `BlobId`；
2. 以 `(role, blobId, size, canonicalShardIndex)` 全序建立 `ArtifactPackageId`；
3. 受信任 typed parser 產生架構、tokenizer/template digest、版本化 `QuantizationDescriptor` 與 format metadata；
4. 將可執行語義組成 `ModelRevisionId`；
5. 安裝位置與狀態以 `InstallationId` 表達；Alias 不參與內容身分。

## 4. 安裝交易
- 下載部分採 half-open range，驗證 `Content-Range`、validator、非重疊與總長；
- `206`／`200` 切換且 validator 不匹配時丟棄 partial；
- 所有檔案先 materialize；拒絕 link、special file、path traversal、過多檔案與稀疏濫用；
- parser 在 isolated UID 執行，輸出 bounded typed descriptor；
- 驗證、license 與 placement policy 通過後，fsync file／directory、atomic promotion、單一 DB transaction；
- Crash reconciler 以 content identity 收斂，不以暫存檔名猜測。

## 5. Trust 與 compatibility
Source Assertion、Revision Assertion、Installation Attestation 以 typed schema 與關聯約束保存。Effective trust 是 active assertions 的版本化函式；installation 不能掛到其他 revision 的 assertion。Dry-load、smoke test 或 benchmark 只新增 compatibility evidence。

## 6. Privileged load
每次 privileged load 都對實際開啟的 read-only FD 重新驗證 content identity、manifest signature、revocation、template/tokenizer epoch 與 installation state。只讀 content-addressed store 可降低成本，但不能只相信舊 DB flag。

## 7. Template override
Override 採 quarantine → digest → atomic promotion → DB transaction → bump template epoch。受影響 Session、compatibility evidence 與 LoadKey 失效；舊 LoadedModel 進 DRAINING。

## 8. License
保存 canonical license text bytes、locale、source、digest 與 version。接受／撤回採 append-only event，scope 綁條款與來源；相同 model bytes 但不同條款不可共用接受紀錄。

## 9. 刪除與 pin
接受 request 時建立 `RevisionLease`。Delete 先讓 installation 進 DRAINING，停止新 load；既有 LoadedModel／Session 依政策 drain，待 request、Session、LoadedModel 與 Job 引用歸零後才刪除。Pin 只禁止自動 eviction，不可越過 resource cap。

## 10. 投影規則
UI 可以顯示「已安裝／已載入」組合狀態，但必須保留兩個 canonical state ID。例如 `READY + UNLOADED` 顯示「已安裝，未載入」；`READY + LOADED` 顯示「已載入」；`DRAINING + DRAINING` 顯示「正在停止使用並準備移除」。不得由單一 friendly state 反推不存在的 canonical transition。
