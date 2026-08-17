---
id: "ANDROID-NATIVE"
title: "Native Packaging、ABI 與 16 KB 設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "android-platform"
lastReviewed: "2026-07-31"
---

# Native Packaging、ABI 與 16 KB 設計

## 1. ABI 與toolchain
Android實作建立exact Toolchain Lock：JDK、Kotlin、Gradle、AGP、NDK、CMake、Ninja/Bazel、bundletool與compiler digest。文件中的最低版本只代表compatibility floor，不能替代release lock。

## 2. 16 KB 全鏈
- 優先使用NDK r28或更新的適用版本，使16 KB alignment為預設。
- 對舊工具鏈或子建置同時考慮linker `max-page-size`與`common-page-size`，但不能只靠一個flag。
- 掃描所有自建、prebuilt、vendor、transitive `.so`的ELF segment alignment。
- 驗證APK/AAB/split中的uncompressed native library zip alignment；使用當期官方工具，例如`zipalign -c -P 16`等價檢查。
- 在16 KB page-size裝置／映像實際cold start、module load、engine load與推理。

## 3. Dynamic Feature／Module
Engine module有ModuleState、EngineBuildId與artifact digest。每個remote/isolated process啟動時安裝SplitCompat或等價loader並握手版本；module update後drain舊worker、restart、probe。安裝中kill、base/split不匹配與rollback是正式失敗狀態。

## 4. Native loading
Runtime只從verified application/module location載入，禁止任意path／downloaded `.so`。Engine model artifact與engine code供應鏈分開。Loaded library版本進EngineBuildId與diagnostic。

## 5. Memory safety與debug channel
設計保留HWASan/ASan／UBSan等debug build、native symbol與crash attribution，但不把缺少實作期evidence當pre-build文件缺陷。Release strip/symbol server與license/SBOM策略由build pack落地。
