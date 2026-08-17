# 第一次執行

目標：在裝置或模擬器上完成「打開 App → 取得模型 → 第一次成功對話」。過程不需要知道引擎名稱。

產品對應文件：[FEAT-AUTOSETUP](../product/docs/70-features/auto-setup.md)、[FEAT-PLAYGROUND](../product/docs/70-features/local-playground.md)、[FEAT-MODELHUB](../product/docs/70-features/modelhub-acquisition.md)。

## 1. 安裝 APK

```powershell
adb devices
adb install -r android\app-ui\build\outputs\apk\debug\app-ui-debug.apk
```

Debug 應用程式 ID 帶 `.debug` 後綴：`com.omnillm.debug`。

可選（未受信任加速／companion 路徑）：

```powershell
adb install -r android\companion-sandbox\build\outputs\apk\debug\companion-sandbox-debug.apk
```

主 App 與 companion 必須由**同一簽名**簽署，才能通過 companion 的 signature permission。本機兩個都用 debug 簽名即可。

## 2. 打開 App

啟動後會進入 Onboarding（自動架設）：

1. 系統建立裝置執行指紋（CPU、RAM、加速器、儲存）。
2. 依你的用途（聊天／embeddings／多模態）與偏好排序候選。
3. 你選擇 catalog 下載，或用系統檔案選擇器匯入本機模型。
4. Acquisition Job 走隔離區 → 驗證 → 原子安裝。半安裝狀態不能被 load。

不知道選什麼時：選本機聊天 + 較小的 GGUF（例如量化後數百 MB 的模型），後端選已知可用的 CPU。未知 GPU／NPU 不應優先於已知 CPU。

## 3. 匯入本機 GGUF（開發者常用）

1. 把模型放到裝置可讀位置（Downloads 或透過 `adb push`）。
2. ModelHub → 匯入本機檔案。
3. 等待 Job 進入 READY。失敗時看 Dashboard／診斷，不要手動改資料庫。

```powershell
adb push my-model.Q8_0.gguf /sdcard/Download/
```

**不要**把 `.gguf` 放進 git。`e2e-artifacts/models/` 已被忽略。

## 4. Playground

模型 READY 之後：

1. 打開 Playground。
2. 送出一句短 prompt。
3. 應看到串流 delta，最後是 terminal 事件（完成或結構化錯誤）。

若被拒絕：

| 常見原因 | 產品語義 |
|---|---|
| 資源不足 | Plan 失敗，沒有 load，也沒有半配置 |
| 引擎未綁定／未資格 | fail-closed 或 dev 模式下 `CONDITIONAL`（帶 `development_ship_mode`） |
| 格式無法辨識 | `MODEL_FORMAT_INVALID` 一類錯誤，不會假裝成功 |

Release 組建不會因為「開發方便」而放行未資格引擎。

## 5. Developer Server（可選）

本機 HTTP 閘道預設只綁 loopback。開啟 Developer Server 後，可用 OpenAPI 描述的路徑從同一裝置上的 client 呼叫。語意見 `specs/openapi/omnillm.openapi.yaml`。

LAN 模式**預設關閉**。不要為了圖方便在未理解配對與 TLS 的情況下打開。

## 6. 這次成功代表什麼

一次 Playground 成功表示：UI → Admin／Binder → 控制面 → 引擎 adapter 的主路徑是通的。

它**不**表示：

- 該引擎在該裝置上已 `QUALIFIED`
- 所有 Feature Pack 的 UI 都已等深
- Play 上架檢查已通過

那些項目見 [`BUILD_STATUS.md`](../../BUILD_STATUS.md) 與 [`SHIP_BACKLOG.md`](../../SHIP_BACKLOG.md)。
