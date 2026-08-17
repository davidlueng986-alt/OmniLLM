---
id: "ENGINE-STANDARD"
title: "引擎整合設計標準"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "engine-architecture"
lastReviewed: "2026-07-31"
---

# 引擎整合設計標準

`specs/engine-qualification-schema.yaml` 定義每個 qualification cell、upstream lock、evidence、expiry／invalidation 與 publication gate；各 Engine Pack 是語義說明與初始 record。

## 1. Engine Pack 的目的
每個引擎只在自己的文件中保存上游整合形態、format/backend、phase cancellation、resource envelope、process placement與known limitations。共同平台只定義必須提供的語義，避免某引擎成為隱性標準。

## 2. 必備內容
- Upstream repository、license、tag/commit、source/artifact digest與observedAt。
- Adapter形態：JNI/C API、prebuilt SDK、local server、compiler/runtime或其他。
- 支援model format、backend、ABI與device constraints。
- Operation mapping：probe/load/plan/commit/start/embed/close/unload。
- Phase capability與cancellation；最大不可搶占單元。
- Session/KV/tokenizer/template與prefix能力。
- Resource envelope及CPU/GPU/NPU觀測方式。
- Trust placement與sandbox可行性。
- Error/event mapping、crash recovery與module lifecycle。
- Qualification profile與known limitations。

## 3. Adapter 規則
Adapter不得：

- 直接寫OmniLLM DB/model store；
- 回傳native pointer或行為介面跨process；
- 把unsupported parameter靜默忽略；
- 以dry-load提升model trust；
- 以單一裝置證據宣稱全域supported；
- 在plan階段修改KV或載入大資源。

## 4. Upstream lock
架構文件不寫「latest」。每個可重建整合保存`UPSTREAM.lock`概念欄位：repository、tag、commit、source digest、patch digest、build toolchain、artifact digest、tested profile與license。上游更新建立新EngineBuildId，舊evidence不自動沿用。

## 5. Qualification
Qualification分format、backend、device、model/workload與operation phase。引擎可以部分qualified；例如CPU text generation supported、Vulkan unknown、embedding unsupported。Registry只發布有證據的cell。
