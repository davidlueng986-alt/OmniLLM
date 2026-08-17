---
id: "FUTURE-IOT"
title: "IoT／Edge Appliance 架構方向"
status: "FUTURE_BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "future-platforms"
lastReviewed: "2026-07-31"
---

# IoT／Edge Appliance 架構方向

## 1. 目標設備
涵蓋Linux SBC、工業edge gateway、NPU appliance與無完整UI裝置。產品核心價值轉為自動辨識硬體、遠端可控但本地推理、統一API與低成本診斷。

## 2. Headless 架構
Control Plane以system/user service運行，UI可由local web console或管理client提供。Device onboarding、model installation與token pairing仍遵守同一identity/trust/job semantics。

## 3. 資源與可靠性
IoT裝置可能RAM/儲存小、無swap、散熱有限；ResourcePolicy更保守，支援read-only rootfs、A/B update、watchdog與power-loss-safe journal。模型install使用content-addressed store與fsync/atomic promotion。

## 4. 安全
不同Linux發行版的sandbox能力差異大；以capability報告seccomp、namespace、device ACL、TPM/secure element與filesystem boundary。無法隔離不受信任native code時，只接受signed catalog model/engine或data-only parser path。

## 5. 網路
LAN／site network通常是主要入口，但必須TLS、device identity、pairing/provisioning、scope與rotation。Cloud management若未來加入，作獨立connector，不改本地inference核心或預設上傳資料。

## 6. NPU artifact
Per-SoC AOT model視為code-like artifact，簽章、target fingerprint與runtime version全部進identity／placement；不能在不同NPU/driver間盲目重用。
