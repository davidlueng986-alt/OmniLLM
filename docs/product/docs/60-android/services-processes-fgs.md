---
id: "ANDROID-SERVICE"
title: "Android Service、程序與 FGS 設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "android-platform"
lastReviewed: "2026-07-31"
---

# Android Service、程序與 FGS 設計

## 1. Service 分解
| Service | Exported | 角色 |
|---|---:|---|
| `RuntimeForegroundService` | false | 持有 control plane 與使用者可見推理工作；不直接作第三方 binding 入口 |
| `RuntimeBindingService` | true | 只回 `IOmniRuntime` 的受限 facade；實際授權由 observed UID + user-approved `ClientRegistration` + scope/epoch 完成 |
| `AdminBindingService` | false | 本 App UI 的 `IOmniAdmin` |
| `TransferService/Job Adapter` | false | 使用者啟動的下載／匯入；依 OS 版本選 dataSync／UIDT／WorkManager 等合適機制 |
| Isolated Parser/CPU Services | false | 由 runtime explicit bind 並傳窄 API／PFD |

## 2. Exported binding 的認證邊界
App-defined **normal permission 不是安全邊界，也不是安全認證邊界**：任意已安裝 App 可能取得 normal permission，它最多協助 API discovery、intent filtering 與文件化用途。真正的授權流程是：

1. Binder 入口讀取系統提供的 calling UID 與 Android user；
2. 查詢 user-approved `ClientRegistration`、scope、expiry 與 revocation epoch；
3. shared UID 預設作為同一 principal，不信任 caller 自報 package name；
4. 每個 request 再做 owner、quota、capability 與 scope 檢查；
5. 未註冊 caller 只能取得 pairing challenge／最小錯誤，不得取得模型、metrics 或 admin capability。

Managed／同簽章部署可以使用 signature permission 作額外門檻，但仍保留 registration、scope、revocation 與 audit，避免 transport permission 變成隱性產品身分模型。

## 3. FGS type
推理服務若使用 `specialUse`，manifest 同時聲明 base FGS permission、`FOREGROUND_SERVICE_SPECIAL_USE`、`foregroundServiceType="specialUse"` 與 service-level subtype property；用途與 Play Console declaration 一致。模型傳輸若使用 `dataSync`，需獨立 type 與 permission，並遵守當期 timeout/quota；不能只宣告 specialUse 後讓下載混用。

## 4. 啟動與通知
FGS 由使用者可見動作、合法 binding 或平台允許條件啟動。呼叫 `startForegroundService` 後在平台期限內發 notification。Notification 顯示正在載入／推理／下載的真實狀態與 cancel action；cancel 進 canonical command，不直接 kill process 造成 uncertain state。

## 5. Runtime restart
`START_STICKY` 或系統可能重啟不等於固定時限保證。Runtime state machine 記錄 `WAITING_FOR_USER_FOREGROUND`、`RECOVERING`、`DEGRADED` 與 `FAULTED`。Worker 以 supervisor binder death 退出，避免舊 epoch 繼續推理。

## 6. Manifest security
- `RuntimeBindingService` 使用 explicit action、app-defined normal permission（discovery/filtering only）及 canonical AIDL pairing/ACL。
- Admin、FGS、parser、worker 均不 export。
- Intent 不攜帶 token、path 或大型 payload；只帶 opaque ID。
- Runtime facade 不提供 `IOmniAdmin`、secret broker 或任意檔案讀寫入口。

## 7. 使用者停止／系統限制
使用者從系統停止 FGS 或 App 被 restricted 時，Runtime 進 drain/recovery 語義；不能自動違反平台限制重啟。長下載在 quota 耗盡時保存 checkpoint 並等待新合法窗口。
