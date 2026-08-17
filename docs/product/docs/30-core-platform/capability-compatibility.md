---
id: "CORE-CAPABILITY"
title: "Capability 與相容性設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "core-platform"
lastReviewed: "2026-07-31"
---

# Capability 與相容性設計

## 1. Capability 查詢模型
Capability 不是單一 engine 的永久布林值，而是對下列鍵的判定：

```text
(engineBuildId, backendId, deviceExecutionFingerprint,
 modelRevisionId or modelEnvelope, operationKind,
 workloadEnvelope, trustPlacement, methodVersion)
```

查詢回傳 `SUPPORTED／UNSUPPORTED／CONDITIONAL／UNKNOWN／TEMPORARILY_UNAVAILABLE`，附 reason、evidence、expiry、resource envelope 與必要條件。

## 2. Operation capability
共同 operation 包括：

- `PROBE`
- `LOAD`
- `CREATE_OR_SELECT_SESSION`
- `PREPARE_TEXT_GENERATION`
- `GENERATE_TEXT`
- `EMBED`
- `PROCESS_VISION`
- `PROCESS_AUDIO`
- `STRUCTURED_OUTPUT`
- `TOOL_CALLING`
- `CLOSE_SESSION`
- `UNLOAD`

每個 operation 分開描述 cancellation mode、最大不可搶占單元、deadline 支援與 worker-kill fallback。全域 `cancellationMode` 不足。

## 3. 前綴能力
以下能力獨立宣告：

1. `EXACT_SAME_SESSION`
2. `EXACT_CROSS_SESSION`
3. `TRUNCATE`
4. `FORK`

它們的依賴與限制由 engine pack 定義。Engine 在內部掌握 tokenizer/template 與 committed token sequence；Orchestrator 不以文字 hash 或 token count 假裝驗證 KV。

## 4. 多模態與 embeddings
Embeddings 是一級 operation，具有 typed request/result、resource plan、batch cap、cancel、deadline、dimension／normalization metadata。Vision／Audio 使用 `AssetHandle`，不在 JSON/AIDL 直接塞大型 bytes；capability 附 MIME、size、count、resolution、duration 與 preprocessing envelope。

## 5. Compatibility Evidence
Evidence 只回答「此組合在這個 envelope 下曾通過何種檢查」。種類：

- `STATIC_MATCH`
- `BOUNDED_PROBE`
- `DRY_LOAD`
- `SMOKE_GENERATION`
- `BENCHMARK_RUN`

Evidence 保存 canonical profile、engine build、device/driver、model revision、method version、結果、時間與 expiry。超出任何維度即不匹配。

## 6. Placement 聚合
LoadedModel 的 placement 在 load 前以整個預期 lifecycle 所需 phases 聚合。若任何必要 phase 為 `WORKER_ONLY／UNKNOWN`，整個 lifecycle 放入可 kill worker，除非 engine 明確支援序列化狀態移交。

## 7. Quirk 與 driver policy
Quirk key 使用版本化 vendor parser、normalized driver tuple、SoC mapping version 與精確 device fingerprint。無法解析時只能命中 exact fingerprint，不做字典序 range 猜測。
