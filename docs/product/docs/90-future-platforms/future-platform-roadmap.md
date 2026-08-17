---
id: "FUTURE-ROADMAP"
title: "iOS／PC／IoT 未來平台架構 Roadmap"
status: "FUTURE_BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "future-platforms"
lastReviewed: "2026-07-31"
---

# iOS／PC／IoT 未來平台架構 Roadmap

## 1. 文件性質
本 roadmap 描述**架構演進順序與相依關係**，不是開發排程、版本刪減或日期承諾。所有現行產品能力仍在設計中完整定義；未來平台以portable core成熟度與平台限制選擇合適adapter。

## 2. 演進軸線
### A. Portable semantic core
先保持canonical identity、capability、request/event/error、state machine、resource、trust與measurement不含Android type。任何Android專屬欄位透過PlatformDescriptor或extension投影。

### B. Shared engine adapters
對具有跨平台C/C++／SDK介面的engine，分離portable adapter logic與platform packaging／memory／process supervisor。EngineBuildId依平台artifact區分。

### C. Platform security mapping
建立Android UID、iOS sandbox、Windows AppContainer/macOS XPC/Linux sandbox的placement capability matrix。不能把某平台隔離名稱直接等同另一平台。

### D. Interface strategy
HTTP canonical profile可共享；native transport按平台設計。iOS以embedded SDK/foreground lifecycle為主，PC可提供長期local service，IoT可提供headless daemon。

### E. Evidence portability
ModelRevision與source assertion可跨平台；compatibility/measurement evidence綁platform/device/driver，不能跨平台復用。Catalog可發布同一model的多platform artifacts。

## 3. 平台選擇原則
- iOS：優先驗證background、sandbox與engine SDK限制。
- PC：優先建立multi-GPU／service isolation與installer/update模型。
- IoT：優先power-loss durability、headless security與per-SoC artifact supply chain。

## 4. 核心價值保持
每個平台都必須保留：低門檻自動架設、統一調用、可視化監控。若平台限制使某能力不可提供，透過capability與UX明示，而不是另建不相容產品語義。
