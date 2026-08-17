---
id: "CORE-ENGINE"
title: "統一引擎平台設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "core-platform"
lastReviewed: "2026-07-31"
---

# 統一引擎平台設計

## 1. Engine contract 的責任
Engine Adapter 將上游 runtime 映射為共同 lifecycle，負責 tokenizer、chat template、model-specific preparation、KV mutation、native cancellation 與 event translation。Orchestrator 負責 identity、policy、scheduling、resource admission、ownership 與 recovery；兩者不得互相侵入。

## 2. 核心介面形狀
```kotlin
interface OmniEngine {
  val engineBuildId: EngineBuildId
  suspend fun describe(device: DeviceDescriptor): OmniResult<EngineDescriptor>
  suspend fun planProbe(input: ProbeInput): OmniResult<ProbePlan>
  suspend fun probe(plan: ProbePlan, reservation: Reservation, op: OperationContext): OmniResult<ProbeResult>
  suspend fun planLoad(input: LoadInput): OmniResult<LoadPlan>
  suspend fun commitLoad(plan: LoadPlan, reservation: Reservation, commit: CommitContext): OmniResult<LoadedModelHandle>
  suspend fun queryCommit(commitId: CommitId): OmniResult<CommitState>
}

interface LoadedModelPort {
  suspend fun planInference(input: InferenceInput, source: SourceSessionRef): OmniResult<InferencePlan>
  suspend fun commitInference(plan: InferencePlan, reservation: Reservation, commit: CommitContext): OmniResult<PreparedOperation>
  suspend fun start(prepared: PreparedOperation, op: OperationContext, sink: EventSink): OmniResult<OperationHandle>
  suspend fun planEmbedding(input: EmbeddingInput): OmniResult<EmbeddingPlan>
  suspend fun commitEmbedding(plan: EmbeddingPlan, reservation: Reservation, commit: CommitContext): OmniResult<PreparedOperation>
  suspend fun closeSession(handle: SessionHandle, op: OperationContext): OmniResult<CloseResult>
  suspend fun unload(op: OperationContext): OmniResult<UnloadResult>
}
```

實作語言與最終 signature 可調整，但語義不可退回「先做再估」或以介面物件跨 Binder。

## 3. Plan 規則
Plan 必須：

- deterministic，或明確包含 nondeterministic inputs；
- 無 domain mutation；
- 有 planId、request digest、owner、engine build/load key、target/source session、epoch、expiry、workload envelope；
- 回傳 ResourceVector、phase capability 與 proposed prefix decision；
- 不暴露可由 caller 修改後重放的未簽章結構；優先使用服務端 opaque plan handle。

Tokenizer／grammar cache 的建立只能使用預先保留的 bounded analysis pool，不能把未計費配置藏在「pure」定義內。

## 4. Commit 規則
Commit 是一次性、idempotent、queryable 的 domain mutation。它綁 `commitId`、plan、reservation、owner 與 epoch；結果先持久化或寫入可 reconcile intent，再回覆 caller。重複 commit 回原結果；相同 ID 不同 payload 回 conflict。

## 5. Prepared operation
Prepared operation 與執行中的 handle 共用 `specs/state-machines.yaml#OPERATION`：`PREPARED → STARTING → RUNNING／CANCELLING／RECONCILING → COMPLETED／FAILED／CANCELLED／ABORTED_UNCERTAIN`。`start` 是 idempotent claim；reply loss 以 query operation 解決。取消可在 handle 回覆前使用 caller 先產生的 `OperationId`，且 durable cancel intent 不得在 reconciliation 後重新回到可啟動狀態。

## 6. Event contract
Event 類型：metadata、delta、usage、diagnostic、warning、terminal。序號採 half-open `[from,to)`；同 request 單調且 epoch-bound。Terminal 唯一；stream committed 前後的錯誤映射不同。

## 7. Error contract
所有跨 module suspend operation 回 `OmniResult<T>`；不以 unchecked exception 表達預期錯誤。Native crash／Binder death 由 adapter 映射為結構化 failure，並觸發 Session/Allocation reconciliation。

## 8. Hint 與動態調整
任何增加 workspace 或改變 resource envelope 的 hint 使用 `planHint → reserve → commitHint`。不需額外資源且在 engine 明確保證安全的 hint 才可 direct apply。Hint 結果含 applied、deferred、rejected 與 effective config。

## 9. Qualification
引擎只有在其 Engine Pack 提供精確 upstream lock、build digest、phase capability、resource envelope、placement、format support、known limitations 與 conformance scenarios 後，才可被 Registry 標記為 `QUALIFIED`。未知組合不沿用其他裝置／模型證據。

## 10. Embeddings
Embeddings使用與generation相同的Plan、Reservation、Commit、Operation與error原則，但不依賴generation Session。
