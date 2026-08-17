---
id: "UX-IA"
title: "資訊架構與導航"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "product-design"
lastReviewed: "2026-07-31"
---

# 資訊架構與導航

## 1. 導航樹
```text
Home
├── Quick Start
├── Active Model / Request
└── Blocking Issues
ModelHub
├── Suggested
├── Installed
├── Downloads & Imports
└── Model Detail
Playground
├── Chat
├── Embeddings
├── Multimodal
└── Structured / Tools
Server & Clients
├── Local Endpoint
├── LAN
├── AIDL Clients
└── Tokens & Permissions
Dashboard
├── Overview
├── Models & Engines
├── Requests & Queue
├── Resources & Thermal
└── Measurements
Settings
├── Automation Policy
├── Storage
├── Privacy
├── Advanced Engine Controls
└── Diagnostics Export
```

## 2. 入口規則
- 初次啟動進入意圖分流，不要求使用者先理解 server、model format 或 backend。
- 沒有可用模型時，所有推理入口導向 Model Acquisition，不顯示空白 Chat。
- 有 active fault 時，Home 與受影響功能顯示同一 canonical reason；不得各自編造不同訊息。
- LAN、risky placement、raw engine extensions 與 destructive data reset 都在明確的進階區。

## 3. Deep Link 與外部入口
外部 App 的 AIDL binding、notification action、下載完成、diagnostic share 與 LAN pairing 都必須落到既有 destination，並帶入 opaque resource ID；不以 file path 或 secret 作 deep link parameter。

## 4. 診斷導航
任一錯誤頁至少提供：發生階段、受影響資源、是否已自動恢復、建議動作、詳細資訊、相關 request/job ID。使用者可由錯誤直接跳到 Model、Client、Storage 或 Dashboard 的對應頁。
