---
id: "FEAT-MODELHUB"
title: "ModelHub、模型取得與管理"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "feature-team"
lastReviewed: "2026-07-31"
---

# ModelHub、模型取得與管理

## 0. 核心價值對應
- **低技術門檻自動架設**：把來源、格式、license、相容性、下載、匯入、安裝與清理整合成可理解的一條模型生命週期。
- **統一調用**：模型卡以 canonical identity/capability 描述，不以某個引擎的 filename 或私有參數作產品身分。
- **可視化監控**：下載驗證、安裝、載入、pin、引用、trust、evidence 與磁碟使用均有明確狀態與原因。

## 1. Model 卡
每張卡分開顯示 `ModelRevisionId`、`ArtifactPackageId`、大小、`QuantizationDescriptor`、來源 assertions、license、支援 engine/backend cells、trust class、compatibility evidence、performance evidence、installed/loaded/pinned/reference 狀態及風險旗標。不得用「可運行」一詞混合信任、相容性與性能。

## 2. 所需能力
`MODEL_ACQUISITION`、`MODEL_IDENTITY`、`COMPATIBILITY_EVALUATION`、`SAFE_INSTALLATION`、`MODEL_LIFECYCLE`、`JOB_LIFECYCLE`、`JOB_RECOVERY`、`RESOURCE_ACCOUNTING`。

## 3. 動作與身分
Discover、Download、Import、Verify、Install、Load、Unload、Pin、Delete、Benchmark、View License、View Evidence、Change Alias 全部使用 Command／Job handle。Alias 只是可變指標；接受 request 時固定 revision 並建立 `RevisionLease`。

## 4. 下載
`DownloadSpec` 包含 signed/typed manifest、mirrors、validator、size/digest、owner 與目的。Resume 使用 half-open ranges；ETag／Last-Modified 與 `If-Range` 綁 attempt。`206` 必須驗證 `Content-Range`；server 改回 `200` 時清除不相容 partial 再重建。Redirect／DNS 每跳重新驗證。Progress 分開 network bytes、materialized bytes、verified bytes。

## 5. 匯入
SAF input 先 materialize 到 runtime-owned quarantine；限制 file count、individual/total size、stream time、special files 與 path。UI 明示「來源未驗證」；parser、dry-load、smoke generation 只建立 compatibility evidence，不提升 trust。Provider revoke、不可 seek、pipe 或 process death 都有唯一狀態。

## 6. 驗證與安裝
Identity → source/trust → license → bounded parser → compatibility → fsync files/parent → atomic promotion → DB READY transaction。每次 privileged load 對實際 FD fresh verify；READY flag 不是永久信任憑證。

## 7. Load、Unload、Pin、Delete
Load 先 plan/reserve。Unload/Delete 進 DRAINING，停止新 reference，等待／取消 request、Session、LoadedModel、Job，再通過 native/file barrier。Pin 禁止自動 eviction，但不突破 resource cap。共用 Blob 依 refcount 刪除，不能因刪一個 revision 破壞另一 package。

## 8. 撤銷與條款更新
Catalog revocation、integrity failure、license terms 變更或 effective trust 降級觸發高優先事件。系統拒新、drain、必要時 kill worker 並顯示替代方案；歷史 measurement 保留但標示當時與目前狀態。License acceptance 綁 canonical terms bytes、source scope 與 user。

## 9. 驗收情境
1. **同 bytes 不同條款**：兩個來源的條款不同時，接受紀錄不能共用；離線仍可顯示當時文本。
2. **Resume validator 變更**：ETag 變動或 `206→200` 時不拼接舊片段；最終只在全檔 hash 正確後進 VERIFYING／READY。
3. **匯入來源不可信**：成功 parse／smoke 不改 trust class，placement 仍依 untrusted policy。
4. **刪除競態**：queued request／LoadedModel／Session 持有 RevisionLease 時，Delete 只能 DRAINING；引用歸零後才刪除且不破壞共用 Blob。
5. **安裝 crash**：任一文件／DB 邊界 crash 後，reconciler 不會把 partial package 當 READY。
6. **撤銷**：active model trust 降級時，在 policy deadline 內拒新並完成 drain/kill；UI 不宣稱過去資料外洩可逆。
