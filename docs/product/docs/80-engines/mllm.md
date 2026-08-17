---
id: "ENGINE-MLLM"
title: "mllm 引擎整合設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "engine-integration"
lastReviewed: "2026-07-31"
---

# mllm 引擎整合設計

## 1. 上游、授權與 Qualification eligibility
| 項目 | 設計值 |
|---|---|
| Official repository | `https://github.com/UbiquitousLearning/mllm` |
| Integration family | 裝置內client-server／Go `mllm_server.aar` adapter |
| License handling | pinned repository/AAR/native dependencies保存license、notice與digests |
| Lock state | `NOT_QUALIFIED_UNTIL_UPSTREAM_LOCK` |
| Required lock | tag/commit、Go/NDK/toolchain、AAR/native artifact digest、server protocol、model format、patch digest、observedAt |

## 2. Integration shape
mllm server視為embedded engine service，不直接暴露給external client。OmniLLM Adapter控制server lifecycle、private channel、random runtime credential與canonical request translation。若上游只能使用localhost port，需端口隔離、Host/auth、orphan cleanup；優先private IPC/Unix domain等不對其他App可達通道。

## 3. Format、backend 與 device
模型格式、mobile CPU/NPU/GPU能力依pinned server/API與device evidence。Adapter不得以server宣稱或模型名稱推測capability。Package identity包含server build、model files、tokenizer/template/config與backend target。

## 4. Operation mapping
PROBE/LOAD/PLAN/COMMIT/START/EMBED/CLOSE/UNLOAD映射到server RPC。若server沒有separate plan，Adapter使用保守qualified envelope及runtime intent/commit wrapper；無法避免plan階段mutation時，該operation不能宣稱符合core contract。外部Gateway仍是唯一HTTP/AIDL入口。

## 5. Session、tokenizer、template 與 prefix
Server conversation/session ID只封裝為opaque handle並綁runtime/worker epoch、owner、revision與load key。Tokenizer/template ownership明確在server/adapter；其digest進profile。Server未暴露exact token/KV proof時，不發布cross-session reuse/truncate/fork。

## 6. Phase cancellation 與 resource
量測server start、model load、prefill、decode、embedding、close、shutdown。Cancellation需上游RPC ack或worker termination；HTTP socket close不證明native停止。Resource包含Go/server fixed overhead、model/KV/workspace、queue、CPU/accelerator、port/socket/FD與temporary cache；self-report不降低envelope floor。

## 7. Placement 與網路安全
Server/native inference在獨立worker process。Trusted artifact可same-UID crash-contained；untrusted accelerated路徑需different-UID companion。Embedded server只接受runtime-generated credential與operation epoch，禁止CORS wildcard、unauthenticated management或LAN binding。

## 8. Error、Event、Commit 與 Recovery
上游HTTP/SSE/RPC映射canonical metadata/delta/usage/terminal；Adapter不能把雙重SSE write當client delivery。Server-side mutation綁CommitId並可query；reply loss先查server/runtime ledger。Server crash使其sessions全POISONED，Runtime ledger決定client query。

## 9. Known limitations／Unsupported-by-default
- 若server protocol無idempotent commit/query，相關mutation只可在受控worker以conservative recovery，不能宣稱exactly-once。
- Localhost port可能被其他本機App攻擊，必須private channel或strong credential；不能只依Host/CORS。
- Tokenizer/KV/prefix、embedding、structured/multimodal能力需API證據。
- Go/server process overhead與shutdown latency需實測，不以JNI假設估算。

## 10. Qualification closure
精確server/AAR lock、private-channel/auth、operation/session mapping、cancellation/resource、event/error、orphan/server death、port collision及Android packaging證據完成後逐cell發布。


## Qualification interpretation
本文件的整合設計為 `BASELINE`；`qualificationStatus: UNQUALIFIED` 表示尚無實作期 EngineBuildId／artifact／device／driver／model／workload evidence。Registry 在有證據前只回 `UNKNOWN／UNSUPPORTED`，不得把設計完成誤寫成 runtime supported。
