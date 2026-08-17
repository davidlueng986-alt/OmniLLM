---
id: "ANDROID-BASELINE"
title: "Android 平台基線"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "android-platform"
lastReviewed: "2026-07-31"
---

# Android 平台基線

## 1. 目的
本文件定義 Android adapter 必須遵守的平台約束。Portable Core 不依賴 Android class；Service、Binder、SAF、FGS、AAB／DFM 與 native packaging 由本層轉譯。

## 2. 版本與政策基線
- `minSdk` 是裝置覆蓋與依賴能力的產品／工程決策，不在架構中假裝永久不變。
- **Google Play 提交基線**依 `specs/platform-policy-register.yaml`。截至 2026-07-31，2026-08-31 起新 App 與更新需 target Android 16／API 36（適用的裝置類型例外依官方政策）。因此任何 Play build 都必須有 compile／target API 36 lock、Android 16 behavior-change matrix 與測試結果；不能只寫「release 時再決定」。
- 非 Play 發佈仍需明示 target SDK 與相容矩陣；side-load 不得放寬安全、exported component 或 model trust。
- OEM／OS 差異以 `DeviceExecutionFingerprint`、quirk 與 evidence 表示，不以品牌名稱硬編碼成功。

## 3. 程序基線
- UI main process 不載 native、不寫 DB；
- Runtime control plane 持有 FGS、Gateway、single writer 與 trusted engine；
- Exported Runtime Binding 與 non-exported Admin／FGS 分離；
- `isolatedProcess` 用於受限 parser／CPU inference，提供獨立 UID 與無自身權限邊界，但不宣稱消除 DoS、side-channel 或 OEM driver 差異；
- 不受信任 accelerated execution 使用 different-package UID companion。

## 4. Background／前景設計
Android 不保證任意 background 時刻可啟動或長期維持 FGS。使用者可見的長工作從合法入口啟動；平台要求前景互動、quota 或 timeout 時，Job 進 `PAUSED_WAITING_FOREGROUND` 或轉用合適 API。推理 `specialUse` 與網路傳輸的 UIDT／dataSync 決策分離。

## 5. Native 與大型資料
所有 `.so`、prebuilt 與 transitive dependency 納入 16 KB page-size／ABI 驗證。大型 model／asset 透過 PFD／handle，不進 Bundle／Binder 大物件。Runtime 不依賴跨 isolated UID 直接讀 `/proc` 作唯一記憶體控制。

## 6. Play AI 內容回報
OmniLLM 的本機生成介面使用 `FEAT-AI-CONTENT-REPORT` 提供 App 內回報／旗標；Play profile 不能只提供離開 App 的聯絡連結。Report 與 telemetry 分離，預設不外送完整 prompt/output。

## 7. 每個建置／發佈 profile 的必要 lock
Target／compile SDK、minSdk、AGP／Gradle／NDK／JDK、manifest components／permissions／FGS types、native dependency digest、16 KB result、Data Safety、AI report endpoint、device/driver matrix。這是實作期 artifact，不在 pre-build 文件偽造通過證據。
