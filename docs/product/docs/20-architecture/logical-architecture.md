---
id: "ARCH-LOGICAL"
title: "邏輯架構與責任分解"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "architecture"
lastReviewed: "2026-07-31"
---

# 邏輯架構與責任分解

## 1. 邏輯元件
### 1.1 Experience Layer
Compose UI、onboarding、ModelHub、Playground、Server & Clients、Dashboard。只透過 Admin Facade 與 canonical view model 取得狀態。

### 1.2 Interface Layer
HTTP Gateway、AIDL Runtime Binding、Admin API、authentication、rate limit、transport preflight、serialization 與 streaming adapters。它不直接選 engine 或改 Session。

### 1.3 Control Plane
- **Request Registry**：claim idempotency、狀態、attempt、terminal 與 query。
- **Orchestrator**：capability filtering、candidate plan、routing、scheduling、fallback policy。
- **Resource Governor**：ResourceVector reservation、allocation、pressure、eviction barrier。
- **Session Manager**：owner/fingerprint、pool、commit checkpoint、poison/drain。
- **Model Manager**：acquisition、identity、trust、installation、load lifecycle。
- **Job Manager**：download/import/benchmark/delete 等可恢復工作。
- **Policy Manager**：settings、ACL、revocation epoch、risk acknowledgment。

### 1.4 Engine Abstraction
Engine Registry、Engine Adapter、Phase Capability、Plan／Commit／Operation protocol、event normalization。Adapter 負責翻譯各上游 runtime，但不得重新定義 canonical semantics。

### 1.5 Execution Plane
Trusted in-process engine、same-UID crash worker、isolated parser、isolated CPU inference、different-package accelerated sandbox。每個 placement 有不同可用能力與故障處理。

### 1.6 Data & Evidence Layer
Room／DataStore／content-addressed model store／journal／measurement store。只有 control plane 寫入；worker 以窄 IPC／pipe 回報事件或取得 FD。

## 2. 依賴方向
```text
Experience → Interface/Admin View
Interface → Canonical Types + Control Plane
Control Plane → Engine Abstraction + Data + Platform Ports
Engine Adapter → Engine Contract + Platform Ports
Platform Adapter → OS APIs
```

禁止：UI → Engine Adapter、Engine → Room、HTTP → native pointer、worker → secrets/catalog writable path。

## 3. 橫切服務
Canonical clock、ID generation、structured logging、redaction、feature/capability catalog、error mapping、device fingerprint 與 policy version 是共享服務，但必須保持 deterministic 並可在 process restart 後重建。

## 4. 可替換邊界
- HTTP server implementation 可替換，只要保持 interface semantics。
- Room 可替換為其他 database，只要保持 data ownership／durability invariant。
- Android UI 可在 iOS／PC 重建，portable core 不依賴 Compose。
- 每個 engine adapter 可獨立加入、停用或隔離，不改 Orchestrator contract。
