---
id: "ENGINE-MLC"
title: "MLC-LLM 引擎整合設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "engine-integration"
lastReviewed: "2026-07-31"
---

# MLC-LLM 引擎整合設計

## 1. 上游、授權與 Qualification eligibility
| 項目 | 設計值 |
|---|---|
| Official repository | `https://github.com/mlc-ai/mlc-llm` |
| Integration family | compiler + generated model library + runtime adapter |
| License handling | compiler/runtime/generated artifact與dependencies分別保存license/notice/digest |
| Lock state | `NOT_QUALIFIED_UNTIL_UPSTREAM_LOCK` |
| Required lock | MLC/TVM commit、compiler config、target、model config、generated source/library digest、runtime artifact、toolchain、observedAt |

## 2. Integration shape
Model package含compiled executable code、metadata、tokenizer與weights；全部由ArtifactPackage/ModelRevisiontyped manifest管理。Adapter控制MLCEngine/chat object與stream callback，不把generated library當普通data或允許未簽code進trusted process。

## 3. Format、backend 與 device
Backend由實際pinned target與官方/qualification evidence決定。Android上任何OpenCL、Vulkan、CPU或其他路徑都按device/driver/model/workload cell發布；架構不寫死不存在的通用路徑。Compiled target、workgroup/shape、runtime ABI與driver進identity/evidence。

## 4. Operation、Session 與 tokenizer mapping
PROBE檢查package/target/runtime compatibility；LOAD載入runtime與generated module；PLAN/COMMIT映射chat/session建立及prompt preparation；START映射stream generation；EMBED/vision/structured依pinned API。Tokenizer/template/config digest進LoadKey。KV/prefix只有runtime提供可證明snapshot/fork/truncate語義時發布。

## 5. Phase cancellation
Compiler不在一般推理request中隱式執行；離線/安裝compile是Job。Runtime module load、kernel compile/cache、prefill、decode、close逐phase量測。GPU command無法bounded cancel時，整個model lifecycle置different/same-UID worker，依trust決定；kill後driver/resource barrier需觀測。

## 6. Resource envelope
分generated code/module、CPU anon/file、weights、KV、runtime workspace、GPU dedicated/shared/driver、kernel compile peak、temporary disk與threads。CPU RSS不能代表GPU allocation。Plan使用compiled metadata + qualified profile；未知driver allocation用保守上界。

## 7. Placement 與供應鏈
Compiled model code可執行，因此untrusted artifact必須different package/UID；isolated data-only worker不足。Catalog簽署code、data、target metadata與compiler chain。每次trusted load驗artifact digest及runtime compatibility。Companion若無對應GPU能力，組合unsupported而非same-UID fallback。

## 8. Error、Event、Commit 與 Recovery
Runtime/callback錯誤映射OmniError與canonical terminal；delta/usage sequence由Adapter產生。Module/sessionmutation綁CommitId；reply lossquery ledger。Driver crash/hang或worker death使LoadedModel/Session全部失效，allocation在process/driver barrier後才釋放。

## 9. Module lifecycle
Runtime與generated model artifact版本握手。更新前drain，安裝後cold restart/probe；舊compiled artifact不默認兼容新runtime。Cache與generated libs依content ID/EngineBuildId分區，rollback不混用。

## 10. Known limitations／Unsupported-by-default
- 未pinned compiler/runtime/target的任何backend為UNKNOWN。
- Generated code使untrusted路徑隔離要求高於純weights格式。
- GPU memory/cancellation/driver recovery依OEM，不能給全域保證。
- Prefix、embedding、multimodal、tool/structured能力依model/API逐cell驗證。

## 11. Qualification closure
需完整compiler reproducibility、code-signing/supply-chain、backend/device matrix、resource/cancel、session/tokenizer、error/event、crash/reply-loss及Android packaging evidence。


## Qualification interpretation
本文件的整合設計為 `BASELINE`；`qualificationStatus: UNQUALIFIED` 表示尚無實作期 EngineBuildId／artifact／device／driver／model／workload evidence。Registry 在有證據前只回 `UNKNOWN／UNSUPPORTED`，不得把設計完成誤寫成 runtime supported。
