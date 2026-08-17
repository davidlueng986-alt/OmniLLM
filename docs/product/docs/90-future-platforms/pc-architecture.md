---
id: "FUTURE-PC"
title: "PC（Windows／macOS／Linux）架構方向"
status: "FUTURE_BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "future-platforms"
lastReviewed: "2026-07-31"
---

# PC（Windows／macOS／Linux）架構方向

## 1. 產品形態
PC可提供桌面App、background user service與local developer server。Portable Core保持一致，平台adapter分別處理Windows Service/AppContainer、macOS app/XPC、Linux user service/sandbox。

## 2. Engine 與加速器
支援CPU、CUDA、ROCm、Vulkan、Metal、OpenVINO、DirectML/Windows ML等由engine/backend qualification決定。PC不因資源較多而放棄ResourceVector；GPU VRAM、shared memory與多使用者公平性更重要。

## 3. Process isolation
- Windows：評估AppContainer／restricted token／job object與broker。
- macOS：App Sandbox＋XPC service；不受信任parser／engine放低權限XPC。
- Linux：user namespace、seccomp、Landlock／bubblewrap等可插拔sandbox。

Platform capability差異以PlacementClass映射，不假設所有PC都能安全執行downloaded native model code。

## 4. Service 與 API
PC可長時間提供loopback/LAN server，但仍需token、TLS、pairing、rate limit與revocation。多OSclient共享HTTP canonical profile；native SDK可使用named pipe／XPC／Unix socket adapter。

## 5. 多模型與多GPU
PC adapter可加入GPU device selection、VRAM budget與multi-model routing，但核心仍要求caller fallback policy、Session綁revision與allocation守恆。跨GPU migration不是默認能力。

## 6. Windows ML
Windows ML等平台runtime可作一個engine adapter；其model support、EP、packaging與OS requirements由Engine Pack定義，不成為核心硬依賴。
