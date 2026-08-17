---
id: "DATA-MEASUREMENT"
title: "量測與 Benchmark 資料設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "data-architecture"
lastReviewed: "2026-07-31"
---

# 量測與 Benchmark 資料設計

## 1. Profile identity
MeasurementProfile必須包含：

- EngineBuildId、adapter version、backend；
- ModelRevisionId、ArtifactPackageId、quantization descriptor；
- DeviceExecutionFingerprint、driver、OS、page size；
- context、KV format、threads、batch、parallel sessions、prompt/output token limits；
- tokenizer/template digest、sampling/structured config；
- fixture/corpus digest、warmup、sample count、thermal/power condition；
- metric method、clock、histogram/sketch version。

Profile canonical object與hash一併保存，可重算。

## 2. Run history
`MeasurementRun(runId, profileId, runSeq, startedAt, completedAt, outcome, environmentSnapshot)`。同一 profile可有多run；current view以 `runSeq DESC` 唯一選取，不只看可能相同的timestamp。

## 3. Metrics
- latency distribution：HDR histogram、t-digest或固定buckets，保存methodVersion；count/sum只用於average。
- throughput、TTFT、load time、peak resource、energy/thermal event分開。
- percentile顯示誤差界與樣本數。
- 不同profile不能直接合併比較；UI必須指出差異維度。

## 4. Compatibility vs Performance
Smoke generation可建立compatibility evidence，但不能自動變成性能基線。Benchmark failure不等於模型不可信；trust revocation也不刪除歷史measurement，只標記當時與目前trust state。

## 5. Rollup
Rollup key含profileId、metric、window、methodVersion，有明確PK。Raw run保留足以驗證rollup的資料或digest。Driver／engine build／ctx／threads不藏在自由JSON之外。

## 6. Benchmark 工作
Benchmark是可取消Job，受Governor與thermal policy；不能與互動request無限制競爭。若thermal state超出profile envelope，run標記invalid/degraded，不與正常run合併。
