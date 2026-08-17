---
id: "ANDROID-DIST"
title: "Android 發佈、Play 與政策設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "android-platform"
lastReviewed: "2026-07-31"
---

# Android 發佈、Play 與政策設計

## 1. App 與模型分發分離
AAB／APK交付App程式碼、UI、engine code module與資源；模型與per-device model artifact由signed catalog、pinned download或SAF取得。這避免把第三方推理服務與特定Play asset條款耦合。

## 2. 發佈形態
- Google Play AAB：符合當期 target SDK、FGS declaration、16 KB、Data Safety 與 AI-generated content policy。對 OmniLLM 本機生成 UI，`FEAT-AI-CONTENT-REPORT` 提供 App 內 report／flag flow，不能只連到外部網頁。
- Standalone APK：相同security/trust model，不因side-load放寬model verification或exported component。
- Optional engine modules：需artifact digest、license、ABI、module lifecycle與multi-process handshake。

## 3. Foreground service政策
每次release重新檢查specialUse subtype用途與Play Console declaration；download transfer的dataSync/UIDT/WorkManager選擇依當期規則。若政策不允許自動啟動，功能轉成使用者觸發或暫停，而不是隱藏違規。

## 4. Data Safety／隱私
聲明與實際telemetry、LAN、diagnostic export、model download一致。Prompt/output預設不外送；若未來新增cloud connector，必須是新feature與獨立privacy review。

## 5. Supply chain
Release保存source、dependency、native artifact、toolchain、SBOM、license與signing provenance。上游引擎以精確commit/tag＋digest鎖定，不在架構文件寫「最新」。

## 6. Rollback
App rollback不得載入與舊schema/engine不相容的state。Migration需要min-readable/min-writable version；無安全rollback時，UI在更新前明示。Model content本身以revision identity與engine compatibility重新判定。


## 7. AI-generated content policy profile
Play 發佈 profile 必須啟用 App 內 AI 內容回報，設定 developer reporting endpoint、privacy disclosure、離線 queue、retention 與處理聯絡方式。回報功能失效是 distribution gate 失敗，但不應靜默把輸出送往其他服務。
