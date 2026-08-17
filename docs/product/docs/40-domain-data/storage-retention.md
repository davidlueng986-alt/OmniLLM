---
id: "DATA-STORAGE"
title: "儲存布局、保留與刪除"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "data-architecture"
lastReviewed: "2026-07-31"
---

# 儲存布局、保留與刪除

`specs/retention-policy.yaml` 是資料類別、保留窗口、刪除 trigger、legal/security floor 與 pin 例外的機器權威。

## 1. 儲存布局
```text
files/
  model-store/blobs/<sha256>
  model-store/packages/<artifactPackageId>/manifest
  installations/<installationId>/links-or-metadata
  quarantine/<jobId>/<attemptId>/
  license-text/<digest>
  diagnostics/<bundleId>
noBackupFiles/
  trust/
  journal/
databases/
  omnillm.db
```

實際 path 是 platform adapter 細節；domain 只依 content ID／InstallationId，不把 path暴露給 client。

## 2. Quarantine
Quarantine 具 per-job與全域配額、file count、individual/total size、deadline與cleanup policy。只允許 runtime 建立的一般檔；拒絕 symlink、hardlink、device、socket、directory traversal與未定義 sparse behavior。

## 3. Retention
- Request metadata／terminal：由 privacy policy設定保留期；prompt/output預設不持久化，除非使用者明確開啟 history。
- Operational events：短期，rollup 後刪除。`security-audit-events` 預設本機 90 天或使用者明示匯出；license event 依 installation／法律記錄需求 append-only 保存；trust root／active revocation state 依安全功能生命週期保存。不得用「長期」取代可機讀 retention class。
- Measurement run：保留完整 profile與結果；raw sample可依政策壓縮，但不能使既有 percentile不可驗證。
- Diagnostic bundle：使用者建立、明示內容與到期時間，可隨時刪除。

## 4. Delete semantics
刪除先取得 domain lock與 reference snapshot；有 active reference時進 DRAINING。真正刪除使用 content refcount，防止一個 revision刪掉另一個 package共用 Blob。刪除完成後事件與 tombstone保留最小時間，以防 cursor／reply loss誤判。

## 5. Disk pressure
依可回收級別：temporary download → expired diagnostics → cache → unpinned unused model。License text、trust root、active job checkpoint與被 reference的 model不能被隨機清理。任何自動清理產生 reason與recoverability event。
