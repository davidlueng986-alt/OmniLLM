---
id: "FUTURE-IOS"
title: "iOS／iPadOS 架構方向"
status: "FUTURE_BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "future-platforms"
lastReviewed: "2026-07-31"
---

# iOS／iPadOS 架構方向

## 1. 平台映射
- UI：SwiftUI或其他native UI，讀取Portable Core view model。
- Runtime：App process內control plane；高風險native code的隔離能力受iOS sandbox／extension模型限制，不能照搬Android isolated UID。
- Engine：優先評估Metal/Core ML、LiteRT-LM Swift SDK、llama.cpp XCFramework與其他可重建runtime。
- Storage：Application Support／Caches與Keychain，對應model store、evidence與secret broker。

## 2. Background 限制
iOS BackgroundTasks提供系統排程而非任意常駐daemon；長時間本地推理與server availability需以foreground／user-visible session設計。產品不能承諾Android式常駐FGS。模型下載可用URLSession background transfer，但驗證／install仍由App在合法execution window完成。

## 3. Local API
Loopback HTTP可在App活躍期間提供；跨App native integration需評估App Extension、URL scheme、local network entitlement與平台政策。統一調用的主要跨平台方式可能是SDK/embedded library，而不是永久本機server。

## 4. Security placement
iOS單App sandbox能隔離其他App，但App內第三方native code通常共享權限。對不受信任model parser可使用XPC僅在macOS；iOS需採更嚴格的data-only格式、bounded parser、signed catalog與不執行downloaded code。無法提供需要的隔離時，capability回policy unsupported。

## 5. Resource／thermal
使用OS提供的memory pressure、thermal state、Metal resource與device capability；仍遵守Plan→Reservation→Commit語義，但ResourceVector adapter可能只能保守估計。Measurement profile含device model、OS、Metal/Core ML/runtime版本。
