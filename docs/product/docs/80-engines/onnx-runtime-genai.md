---
id: "ENGINE-ORTGENAI"
title: "ONNX Runtime GenAI 引擎整合設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "engine-integration"
lastReviewed: "2026-07-31"
---

# ONNX Runtime GenAI 引擎整合設計

## 1. 上游、授權與 Qualification eligibility
| 項目 | 設計值 |
|---|---|
| Official repository | `https://github.com/microsoft/onnxruntime-genai` |
| Integration family | GenAI runtime API + execution provider adapter |
| License handling | pinned source/release、ORT/provider/native dependencies保存license、notice與digests |
| Lock state | `NOT_QUALIFIED_UNTIL_UPSTREAM_LOCK` |
| Required lock | release/tag/commit、ORT/GenAI artifacts、provider libraries、toolchain、ABI、config schema、patch/artifact digest、observedAt |

## 2. Model package
Package包含ONNX model、external data、genai config、tokenizer/template與provider metadata。多檔package使用role+BlobId total order；typed/bounded config與tokenizer/template digest進ModelRevision。自由JSON缺必要欄位或未知major時fail closed。

## 3. Provider、backend 與 device
CPU、NNAPI、QNN或其他execution provider只按實際Android package與device/driver/model/workload evidence發布。Desktop provider support不外推Android。Provider init、graph optimization、compile/cache是LOAD phase並需pre-execution envelope。

## 4. Operation mapping
PROBE驗package/provider；LOAD建model/generator parameters；PLAN/COMMIT建立或選session/sequence state；START驅動token generation；EMBED/multimodal/structured/tools依API/model；CLOSE/UNLOAD drain。若API無separate plan/commit，Adapter以worker、qualified envelope和ledger包裝，無法滿足核心不變式的cell不發布。

## 5. Session、KV、tokenizer 與 template
Generator/sequence/native objects不跨AIDL。Session handle綁owner、revision、provider、engine build、tokenizer/template epoch。Prefix/KV能力只有上游暴露精確token/state操作且通過conformance時開啟；否則new/same-session semantics明示。

## 6. Phase cancellation
量測PROBE、provider init、LOAD/optimization、CREATE_SESSION、PREFILL、DECODE、EMBED、CLOSE、UNLOAD。Provider無bounded cooperative cancel時，完整lifecycle放worker；kill後session/model失效並等待provider/processresource barrier。

## 7. Resource envelope
分weights/external data mmap、graph optimization、provider compile/cache、KV、CPU anon/file、accelerator memory、workspace、threads/FD/temp disk。Provider allocation unknown時使用保守上界，不以CPU RSS或空值表示0。

## 8. Placement、Error、Event 與 Recovery
Verified runtime/provider/model及all phases qualified可trusted；untrusted model/provider code依isolated CPU或different UID。Errors映射OmniError；events使用canonicalsequence/terminal。Commit/start reply loss用ledger query；provider/worker crash poison所有native state。

## 9. Packaging
AAR/native/provider dependencies納入ABI、16 KB、license/SBOM與EngineBuildId。Provider module更新需drain、版本握手、restart與probe，base/feature版本不一致時拒絕load。

## 10. Known limitations／Unsupported-by-default
- 未發布／未pinned版本號、provider或model config不能作可重建基線。
- Provider能力、cancel與memory在Android/OEM間差異大，逐cell qualification。
- Prefix/KV、embedding、multimodal、tool/structured能力不由ONNX格式本身推定。
- Config/external data package有高輸入複雜度，需bounded parser與完整manifest。

## 11. Qualification closure
完成pinned artifacts/provider matrix、config corpus、session/tokenizer、phase cancel/resource、event/error、reply-loss/crash、16 KB及license/SBOM後逐cell發布。


## Qualification interpretation
本文件的整合設計為 `BASELINE`；`qualificationStatus: UNQUALIFIED` 表示尚無實作期 EngineBuildId／artifact／device／driver／model／workload evidence。Registry 在有證據前只回 `UNKNOWN／UNSUPPORTED`，不得把設計完成誤寫成 runtime supported。
