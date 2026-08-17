---
id: "FEAT-BENCHMARK"
title: "Benchmark 與研究工作流"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "feature-team"
lastReviewed: "2026-07-31"
---

# Benchmark 與研究工作流

## 0. 核心價值對應
- **低技術門檻自動架設**：提供預設但可解釋的測試設定，避免使用者因不同 profile 得到不可比較數字。
- **統一調用**：不同引擎都以同一 MeasurementProfile、Run、metric、resource與Job語義執行。
- **可視化監控**：展示 TTFT、throughput、distribution、資源、thermal、error、evidence與差異維度。

## 1. Profile
`MeasurementProfileId` 覆蓋 engine build、adapter、backend、model revision/package/quantization、device/driver/OS/page size、ctx/KV/threads/batch/parallelism、tokenizer/template、sampling、fixture、warmup/sample、thermal/power與metric method。Canonical object與hash一起保存。

## 2. 建立測試
UI提供使用情境模板，但每個值可展開來源。系統在執行前檢查 capability、trust、storage、thermal與完整 ResourceVector，並顯示預計影響互動request的公平性。

## 3. 執行
Benchmark是 Job，與互動request共享 scheduler。每次 run有 `MeasurementRunId/runSeq`、environment snapshot、warmup/sample events與 outcome。條件偏離 profile（thermal、backend、driver reset、background restriction）時標記 invalid/degraded，不偷偷合併。

## 4. Metrics
Load time、TTFT、throughput、inter-token latency distribution、end-to-end latency、peak resident/accelerator/workspace、energy hint、thermal events與 errors分開。Percentile來自versioned histogram/sketch，顯示樣本數與誤差；count/sum只算平均。

## 5. 比較與匯出
只自動比較 canonical profile相容的runs。差異時列出 engine build、driver、model、template、ctx、threads、fixture、method等維度，不生成簡化勝負。匯出包含 profile、run summary、metric sketch、environment與digests，預設不含 prompt/output原文。

## 6. 驗收情境
1. **Profile identity**：任一影響結果的維度改變都產生不同 profile ID；跨語言 canonical vector一致。
2. **多 run history**：同 profile多次run均保留，current以runSeq唯一選取，不因timestamp tie漂移。
3. **Thermal deviation**：超出profile ceiling的run標invalid/degraded，不與正常run rollup。
4. **Percentile正確性**：已知分布 fixture的p50/p95在宣告誤差內；刪除raw sample後仍保留足以驗證的sketch/digest。
5. **公平性／取消**：benchmark不餓死互動request；在每phase取消後Job、Session與allocation收斂。
6. **比較防誤導**：不同engine build/driver/ctx/fixture時UI禁止單一排名，清楚列差異。
