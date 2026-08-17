---
id: "ENGINE-LLAMACPP"
title: "llama.cpp 引擎整合設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "engine-integration"
lastReviewed: "2026-07-31"
---

# llama.cpp 引擎整合設計

## 1. 上游、授權與 Qualification eligibility
| 項目 | 設計值 |
|---|---|
| Official repository | `https://github.com/ggml-org/llama.cpp` |
| Integration family | C/C++ library adapter；不把上游HTTP server當核心介面 |
| License handling | Qualification時從pinned source保存license bytes/digest、notice與dependency report |
| Lock state | `NOT_QUALIFIED_UNTIL_UPSTREAM_LOCK` |
| Required lock | repository、tag/commit、source digest、patch digest、NDK/CMake/toolchain、build flags、ABI、artifact digest、observedAt |

沒有完整lock的build只能作exploratory evidence，Registry不得發布`QUALIFIED`。

## 2. 定位與 Integration shape
llama.cpp作GGUF與CPU主要候選。Native library由CMake/NDK建置，Adapter擁有model/context、tokenizer、chat template、sampling、grammar與KV操作。Model bytes由runtime驗證後以read-only FD／受控path broker提供；native pointer不跨process。

## 3. Format、backend 與 device
- GGUF支援按pinned build與metadata parser qualification，不因副檔名直接SUPPORTED。
- CPU是portable candidate；Vulkan/OpenCL/其他accelerator逐backend、device、driver、model/workloadcell驗證。
- Multimodal需明確projector/artifact package與adapter，不因GGUF自動成立。
- Android ABI、16 KB、page alignment、threading與backend libraries進EngineBuildId。

## 4. Operation mapping
| Canonical operation | Adapter責任 |
|---|---|
| PROBE | bounded metadata/backend check，不在privileged process盲載untrusted model |
| LOAD | create model/context resources only after matching Reservation |
| PLAN_INFERENCE | template/tokenize、context check、prefix decision、resource delta；無KV mutation |
| COMMIT_INFERENCE | one-shot truncate/fork/new-session mutation，綁CommitId |
| START/GENERATE | token loop、sampling、event/usage translation |
| EMBED | 僅對qualified pooling/model config發布dimension/normalization |
| CLOSE/UNLOAD | drain-aware native release與Allocation barrier |

## 5. Session、KV、tokenizer 與 template
Session handle綁owner、revision、load key、engine build、tokenizer/template digest與epoch。Adapter內部保存committed token fingerprint；`EXACT_SAME_SESSION`、`EXACT_CROSS_SESSION`、`TRUNCATE`、`FORK`逐項qualification。Model metadata提供的template先經typed/bounded policy；override會bump template epoch並drain舊Session。

## 6. Phase cancellation
Decode loop預期可cooperative cancel，但LOAD、backend init、large prefill、grammar compile、close/unload均需實測max non-preemptible。每個cell保存phase capability；UNKNOWN phase使完整LoadedModel lifecycle放可kill worker。Kill後所有該workerSession失效，不能只重試同native handle。

## 7. Resource envelope
Plan區分weights mmap/file charge、anon model/context、KV、compute/sampling/grammar buffers、threads、FD、temporary disk與accelerator memory。Context反解同時受engine hard max、KV formula、app/anon/accelerator cap與OperatingConstraint。Metadata parser及analysis cache使用有界pool。

## 8. Placement 與供應鏈
Signed engine code + verified catalog model + all phases qualified可進trusted placement；untrusted GGUF只走isolated CPU，或different-UID companion的qualified acceleration。每次privileged load fresh verify actual FD。Parser與native kernels視高風險輸入面，dry-load不提升TrustClass。

## 9. Error、Event 與 Recovery mapping
Native return/error/log被映射為`OmniError`，不穿透raw pointer/path。Token delta採half-opensequence；terminal唯一。Commit/start reply loss使用CommitId/OperationId query；worker crash進WORKER_LOST與Session poison/allocation reconciliation。Unsupported parameter不靜默忽略。

## 10. Known limitations／Unsupported-by-default
- 未qualification的Android accelerator、mixed quantization、multimodal projector、embedding pooling、prefix fork/truncate均為UNKNOWN／UNSUPPORTED。
- GGUF可攜性不等於任意model architecture均受該build支援。
- Native RCE、driver/kernel bug與惡意memory pressure是殘餘風險。
- KV跨process持久恢復不假定可用；worker death後通常重建Session。

## 11. Qualification closure
精確lock、license/SBOM、format corpus、phase cancel、resource envelope、prefix conformance、error/event fixtures、reply-loss/kill-at-boundary、16 KB與device/backend evidence全部完成後，特定cell才可由UNKNOWN轉SUPPORTED。


## Qualification interpretation
本文件的整合設計為 `BASELINE`；`qualificationStatus: UNQUALIFIED` 表示尚無實作期 EngineBuildId／artifact／device／driver／model／workload evidence。Registry 在有證據前只回 `UNKNOWN／UNSUPPORTED`，不得把設計完成誤寫成 runtime supported。
