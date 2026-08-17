---
id: "CORE-OBSERVABILITY"
title: "可觀測性與診斷平台設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "core-platform"
lastReviewed: "2026-07-31"
---

# 可觀測性與診斷平台設計

`specs/observability-catalog.yaml` 是 metric ID、單位、來源、聚合、staleness、privacy 與 evidence label 的機器權威。

## 1. 觀測對象
Service、Engine Module、Loaded Model、Session、Request、Job、Client Principal、Storage、Catalog、Device、Thermal 與 Resource Governor 都有 health/state view。

## 2. Metric 語義
| 指標 | 定義 |
|---|---|
| TTFT | request accepted 到第一個 data event committed 的時間；另記 queue、load、prepare 分量 |
| Throughput | output token count／generation active duration，不包含 queue；tokenizer/version 必須入 profile |
| Queue depth | 分 principal、operation class 與可准入/blocked 原因 |
| Memory | charge metric、source、timestamp、confidence；不把 reservation 當 measured RSS |
| Thermal | platform level、policy action、engine/backend before/after |
| Error rate | 依 phase、canonical code、engine build、device、model profile |
| Cancellation | requested、acknowledged、native stop、worker killed 的不同時間點 |

## 3. Health 模型
Health 使用 `HEALTHY／DEGRADED／UNAVAILABLE／UNKNOWN`，附 reason、since、affected capabilities、automatic action 與 recommended action。Engine crash 不一定使整個 service faulted；但 repeated crash、trust failure、DB integrity 或 resource accounting divergence 可升級。

## 4. 事件與 trace
每個 request／job／command 有 monotonic event sequence、causation/correlation ID、principal、phase、policyVersion、engine build、model revision、runtime epoch。Prompt／model path／token 原文預設不進 log；diagnostic bundle 依使用者選擇與 redaction policy輸出。

## 5. Measurement 與 operational metrics 分離
Operational metrics 用於即時健康，不作 benchmark 比較；Benchmark 使用完整 MeasurementProfile/Run。Rollup percentile 需 histogram/t-digest/固定 bucket與 method version，count/sum 只能算平均。

## 6. Dashboard 決策支援
Dashboard 必須回答：

- 現在誰在用哪個模型／engine？
- 請求為何排隊或降級？
- 哪個資源是限制因素？
- 數據是 measured、estimated、stale 還是 unknown？
- 使用者能採取什麼動作，動作的風險是什麼？

## 7. 診斷包
包含 version/build、device fingerprint、module digest、state snapshots、redacted event、measurement profile、resource policy、crash summary 與 integrity results；不包含 token 原文、prompt、license-sensitive content 或 private file path。每個欄位由 allowlist 管理。
