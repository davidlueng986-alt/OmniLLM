---
id: "ENGINE-LITERT"
title: "LiteRT-LM 引擎整合設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "engine-integration"
lastReviewed: "2026-07-31"
---

# LiteRT-LM 引擎整合設計

## 1. 上游、授權與 Qualification eligibility
| 項目 | 設計值 |
|---|---|
| Official repository | `https://github.com/google-ai-edge/LiteRT-LM` |
| Integration family | 官方SDK／AAR與其Engine/Conversation API adapter |
| License handling | pinned source/SDK保存license、notice、transitive native dependency與artifact digest |
| Lock state | `NOT_QUALIFIED_UNTIL_UPSTREAM_LOCK` |
| Required lock | repository、release/tag/commit、SDK/AAR digest、native libs、toolchain、ABI、model artifact schema、observedAt |

## 2. Integration shape
優先使用官方documented SDK形態，不假設傳統JNI可取得所有handle。`.litertlm`或官方artifact由typedModelRevision描述。Adapter包裝Engine/Conversation object、stream callback與backend configuration；不把SDK object跨AIDL。

## 3. Format、backend 與 device
Model package、tokenizer/template、backend/NPU/GPU requirement依pinned SDK schema。Capability key包含SDK build、artifact revision、SoC/driver、OS與workload。未列於官方或未實測的backend保持UNKNOWN；不可只因裝置有NPU便SUPPORTED。

## 4. Operation mapping
PROBE、LOAD、CREATE/SELECT SESSION、PLAN/COMMIT INFERENCE、START/GENERATE、EMBED、CLOSE、UNLOAD逐項映射。若SDK把plan與mutation綁在單一API，Adapter需在worker中以保守envelope與commit ledger包裝；無法提供pre-mutation plan的組合不得進核心qualification。

## 5. Session、KV、tokenizer 與 template
Conversation object只由Adapter registry以opaqueSessionId表示。Tokenizer/template digest與artifact config進LoadKey/Profile。只有SDK明確提供token/KV等價資訊且通過conformance時，才發布prefix reuse；否則只允許new session或same-SDK conversation continuation，且明示限制。

## 6. Phase cancellation
量測PROBE、runtime init、model load/compile、conversation create、prefill、decode、embed、close/unload。SDK cancellation callback若只停止future output，不等於native execution stopped；terminal需在output fenced、Session disposition與allocationaccounting確定後寫入。不可取消phase使用可kill worker。

## 7. Resource envelope
包含SDK/runtime fixed overhead、model bytes、compiled cache、KV/session、prefill/decode workspace、CPU/GPU/NPU、threads、FD與temporary disk。Vendor self-report不能降低qualified envelope。Cache位置、版本、上限與清理需納入model/module lifecycle。

## 8. Placement 與安全
Verified SDK code/模型及全phasequalified可trusted；user import/untrusted model使用isolated CPU或different-UID companion，取決於SDK是否可在該邊界工作。若SDK強制在主App UID使用privileged accelerator且不能隔離，untrusted組合為policy unsupported。

## 9. Error、Event 與 Recovery mapping
SDK errors映射stableOmniError；stream callback映射metadata/delta/usage/warning/terminal與sequence。Reply loss使用runtime ledger，不以SDK callback是否到達作exactly-once證明。Worker death使其Engine/Conversation object全部失效，Session不能回pool。

## 10. Known limitations／Unsupported-by-default
- Prefix truncate/fork、cross-session reuse、embedding、multimodal與structured output皆需SDK版本/模型cell證據。
- Vendor/NPU memory與cancel observability可能不足，使用保守envelope/worker kill。
- SDK artifact與model schema演進可能不向後相容；EngineBuildId隔離。
- 無法在different UID使用的accelerated路徑不能承載untrusted artifact。

## 11. Qualification closure
完成精確SDK lock、license/SBOM、artifact schema、operation mapping、session/tokenizer/template、phase cancel、resource、placement、event/error與device matrix後，逐cell發布。


## Qualification interpretation
本文件的整合設計為 `BASELINE`；`qualificationStatus: UNQUALIFIED` 表示尚無實作期 EngineBuildId／artifact／device／driver／model／workload evidence。Registry 在有證據前只回 `UNKNOWN／UNSUPPORTED`，不得把設計完成誤寫成 runtime supported。
