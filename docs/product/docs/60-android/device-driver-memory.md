---
id: "ANDROID-DEVICE"
title: "裝置、Driver 與記憶體觀測"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "android-platform"
lastReviewed: "2026-07-31"
---

# 裝置、Driver 與記憶體觀測

## 1. Device fingerprint
包含SoC、CPU feature、ABI、OS build、kernel/runtime、RAM class、page size、GPU/NPU vendor/driver、OpenCL/Vulkan/NNAPI/LiteRT相關版本。Fingerprint schema版本化；OTA或driver變更使相關probe/evidence失效。

## 2. Driver parsing與quirk
Vendor version採專用parser轉normalized tuple，保留raw string與parser version。無法解析時只匹配exact fingerprint；不以字串lexicographic range判斷。SoC family mapping有mappingVersion。

## 3. Memory charge
同UID process採明確PSS/USS/RSS policy；shared model mmap避免重複計費。isolated/different UID worker由自身回報sample，但Governor charge floor不低於pre-admission envelope。Runtime不依賴跨UID`/proc`或`getProcessMemoryInfo`作可攜保證。

## 4. Accelerator memory
GPU/NPU可能有dedicated/shared/driver allocation。Engine plan分開估算；若API無法觀測，使用實測上界＋margin。未知或driver異常時限制並行、ctx或回CPU；不能只看CPU RSS通過准入。

## 5. Thermal與LMK
SystemReserve與thermal threshold是版本化PolicyProfile，按device matrix校準。Unknown device採更保守profile。ApplicationExitInfo、memory pressure callback與crash trace用於diagnostics，不反向假設OS一定在某門檻kill。

## 6. Capability evidence
Probe key含device/driver/model/workload，且有expiry。小模型或低ctx的取消／memory結果不能自動套用到大模型。Evidence超出envelope回UNKNOWN。
