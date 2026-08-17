---
id: "FUTURE-PORTABLE"
title: "跨平台 Portable Core 設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "future-platforms"
lastReviewed: "2026-07-31"
---

# 跨平台 Portable Core 設計

## 1. 目的
OmniLLM 的長期產品價值不是綁定 Android，而是把多引擎聚合、自動架設、統一調用與可視化監控抽象成可移植核心。Android 是第一個 platform adapter；iOS、PC 與 IoT 使用相同 domain semantics，不複製另一套產品規格。

## 2. Portable Core 包含
- Canonical identity、capability、request、event、error、job與state machine。
- Model platform、Engine Registry、Orchestrator、Scheduler、Session policy、ResourceVector與measurement model。
- Trust/evidence、source assertion、placement classes、idempotency與reconciliation。
- Feature-level semantics：auto setup、ModelHub、Developer Server、Dashboard。

## 3. Platform Ports
```text
ProcessSupervisorPort
SecureSecretPort
FileStorePort
DatabasePort
ClockPort
NetworkTransferPort
DeviceDiscoveryPort
ResourceObservationPort
BackgroundExecutionPort
UserAuthorizationPort
EngineModulePort
```

Port輸出使用portable types；Android ParcelFileDescriptor、iOS NSFileHandle、Windows HANDLE只存在adapter。

## 4. 不可直接移植的假設
- Android UID／isolatedProcess不等於iOS sandbox或Windows AppContainer。
- FGS／Binder／SAF不進核心。
- GPU memory、thermal與background execution在每個OS有不同可觀測性。
- 同一engine在不同平台的integration shape可不同；EngineBuildId含platform artifact。

## 5. 跨平台一致性
同一ModelRevisionId在相同canonical manifest下跨平台一致；InstallationId、EngineBuildId、DeviceFingerprint與MeasurementProfile是平台相關。HTTP profile可共享，native SDK transport各自投影。
