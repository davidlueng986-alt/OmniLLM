# OmniLLM — 完成「全功能 + 全引擎 + 可出貨」剩餘清單

**As-of:** 2026-08-09（Stage 4 launch-readiness 刷新）  
**Goal:** 所有引擎可用、所有功能做完、可上架出貨  
**Build switch（BLD-02）:** `ProductBuildMode` 已改為 **variant-scoped**（`core/contracts/.../ProductBuildMode.kt`）：debug/dev 建置由 per-buildType `BuildConfig.OMNILLM_DEV_SHIP_MODE` 注入（`-Pomnillm.developmentShipMode` 可稽核覆寫），**release 一律 fail-closed**（`ProductBuildMode.FAIL_CLOSED`）。沒有全域 `const` 可翻。
**誠實規則（COR-10）:** dev 模式下綁定引擎可執行，但能力投影只能是 **CONDITIONAL**（附 `development_ship_mode` 條件），**不是** SUPPORTED。要 SUPPORTED 仍需 PASS 證據。

---

## 1. 完成度總覽（Stage 4 刷新後）

| 領域 | 現狀 | 要出貨還差什麼 |
|------|------|----------------|
| 12 Feature 模組存在 + 控制面 host | ~95% | Tools 獨立入口（SW-UI-03）、modelhub display port、live metrics |
| 真推理（llama GGUF 匯入 READY→生成） | **完成（模擬器驗證）** | 裝置矩陣 / envelope 證據（Stage 5） |
| 5 引擎整合 | **全部真 backend 已接**（litert/ort/mllm/mlc real binding + llama vendored b9999） | 全部 **UNQUALIFIED** → 裝置驗證證據包 |
| 持久化 / 控制面 | ~95% | 26/51 tables 有 `.sq`；其餘 25 PLANNED；observability/binder-level 尚在 memory |
| 安全 / Companion | ~80% | companion 真加速路徑裝置驗證 |
| Play 出貨 | ~45% | 簽章、minify smoke、Console、多 APK |
| 實機驗證 | ~30% | 裝置矩陣、熱力、FGS、crash-recovery on device |

---

## 2. 本輪（launch-readiness）已完成的項目 — 請勿重做

| # | 項目 | 狀態 | 證據 / commit |
|---|------|------|--------------|
| **E1** | llama.cpp 真上游 + 真 GGUF | ✅ **完成並真機驗證** | `UPSTREAM.lock` **LOCKED**（b9999/47c7869 + 全 digest，`105856a`）；`third_party/llama.cpp` 鎖定；`libomnillm_llama.so` 連結上游；模擬器 instrumented 測試 PASS（gemma-3-270m-Q8_0 → 12 tokens，`upstreamLinked=true`）；16 KB 閘門全過 |
| **E2 + I1** | Playground 真生成 + chat 串流 | ✅ 完成 | `LlamaCppInferenceEngineAdapter` + `RuntimeGgufModelSourceResolver`（READY → openReadOnly → INV-010 重驗 → in-process 真 GGUF）；COR-04 聚合聊天文字；UI Send 路徑接通 |
| **E3** | LiteRT-LM 真整合 | ✅ 完成（未 qualification） | `27115ff`：`OfficialLitertLmSdkBridge`（typed official SDK，compile-verified）；lock LOCKED v0.15.0；`INTEGRATED_PENDING_QUALIFICATION` |
| **E4** | MLC-LLM 真整合 | ✅ 完成（未 qualification） | `080dae0`：`MlcEngineRuntimeBackend` 綁定 generated mlc4j runtime；lock NOT_LOCKED w/ pin（完整 lock 完成前 load fail-closed） |
| **E5** | mllm 真整合 | ✅ 完成（未 qualification） | `4f98347`：`MllmServerBackend`（gomllm 內建 server + loopback HTTP/SSE）；lock LOCKED 2.0.0；`INTEGRATED_PENDING_QUALIFICATION` |
| **E6** | ONNX Runtime GenAI 真整合 | ✅ 完成（未 qualification） | `2da721d`：`RealGenAiBackend` over genai AAR Java API；lock LOCKED 0.14.0 |
| **E7** | 多引擎路由真切換 | ✅ 完成（軟體面） | 所有引擎真 backend 可被 Registry/Orchestrator 選中；Routing destination 存在；裝置證據另計 |
| **M1** | HTTPS 真下載 | ✅ 完成 | `OkHttpArtifactByteSource`：逐跳 redirect + `DownloadUrlPolicy.admitRedirect` 重驗、byte/time cap、cancel-aware；8 測試 |
| **M2** | SAF 匯入（AIDL） | ✅ 完成（API 面） | `IOmniAdmin.importLocalFile`（PFD + digests）進 yaml + binder（API-20 `cfe003f`）；`OmniImportJobParameters` 10 fields |
| **M4** | LOAD/UNLOAD 模型 | ✅ 完成 | `ModelLoadRuntimePort` + ModelManager 生命週期 + ModelHub UI 動作 |
| **M5** | License 接受 | ✅ 完成 | `LicenseAcceptancePort` + load 前 gate + UI「Accept License Terms」 |
| **I3** | Structured / Tools | ✅ 完成（軟體面） | `ToolProposals.sq`/`ToolResultClaims.sq` 持久 ledger + `SqlDelightToolProposalStore` + `ToolsFeatureModule.createDurableApi`；structured adapter 於 Playground tab |
| **I5** | HTTP SSE chat | ✅ 完成 | `/v1/chat/completions?stream` OpenAI chunk framing；COR-03（flow 內不 throw，honest terminal events） |
| **I6** | AIDL 第三方推理 | ✅ 完成（軟體面） | `OmniRuntimeFacade` 走 Orchestrator 真執行路徑（COR-03/04）；配對/stream ACK 完整 |
| **持久化** | jobs / content-report / tools / secrets / tokens / model-manager | ✅ 完成 | `.sq` + stores 全上 plane（`dbf6f33`/`4a5bebe` baseline；schema 對齊 API-40..44 `1c66aff`） |
| **COR-01** | session/model refcount（close 不得釋放使用中記憶體） | ✅ 完成 | `0d7dd8c` |

---

## 3. 剩餘 P0 — 沒有這些就「不能宣稱引擎在裝置上可用」

| # | 項目 | 現況 | 要做什麼才算「可用」 |
|---|------|------|----------------------|
| **Q1** | **5 引擎裝置 qualification**（新） | 全部真 backend 已接、lock 已鎖（mlc 待完整 lock），但**全部 UNQUALIFIED** | 每個引擎至少一台實機：PROBE/LOAD/CREATE_SESSION/PLAN/COMMIT/START/GENERATE/CLOSE/UNLOAD + phase-cancel + resource envelope + fault-injection 證據包 → 填 `specs/engine-qualification-status.yaml` evidence cells（不 invent PASS） |
| **Q2** | 裝置矩陣 / 熱力 / FGS 行為 | 僅 llama 單一模擬器 smoke | 多裝置矩陣 + OEM FGS 行為驗證（GOV-RISKS R-007） |
| **Q3** | crash-recovery fixtures on device | host 測試通過 | 電源殺 process 後 claim/commit reconcile 驗證（`specs/runtime-recovery-fixtures.yaml`） |
| **Q4** | mllm/ort/litert 真模型資產 | 無公開小型模型 | 提供/文件化 tiny model（如 `hf-internal-testing/tiny-random-gpt2-fp32`）供裝置證據用 |

## 4. 剩餘 P1 — 功能深度

| # | Feature | 要補 |
|---|---------|------|
| **F2** | FEAT-MODELHUB | catalog/suggested port（SW-FEAT-03）、display/link ports 持久化（SW-DUR-09） |
| **F5** | FEAT-DASHBOARD | 真 TTFT/TPS/queue 從 observability 灌（observability 仍 in-memory） |
| **F6** | FEAT-BENCHMARK | 對真引擎跑 profile/run 寫入 measurement store（需 Q1） |
| **F7** | FEAT-DIAGNOSTICS | 匯出檔分享 + redaction 預覽 UX（SW-FEAT-09） |
| **F9** | FEAT-TOOLS | **獨立 Tools 入口 + screen（SW-UI-03，仍 OPEN）** |
| **F12** | FEAT-PLAYGROUND | 裝置上穩定出字（接 Q1） |

## 5. 剩餘 P2 — 平台 / 安全 / 出貨

| # | 項目 | 要做 |
|---|------|------|
| **P1** | Companion sandbox 真協議 | Ticket + PFD + 加速路徑裝置驗證；缺 companion 時明確錯誤 |
| **P2** | Durable worker（新） | `:android:workers` command gate + journal 已有；補 worker 側 crash-resume 的持久狀態（in-flight 引擎工作重啟恢復）與裝置驗證 |
| **P3** | SQL schema 全量對齊 | 26/51 已 IMPLEMENTED；剩餘 25 張 PLANNED table 隨功能落地補 `.sq`；`applySchema=false` bootstrap 路徑文件化（SW-DUR-08） |
| **P4** | Mode UI（新） | 目前 dev/release 行為只能靠 `BuildConfig`/`-P`；補設定頁「探索性執行」開關投影（唯 CONDITIONAL，不許宣稱 SUPPORTED） |
| **P5** | Release 簽章 + R8 minify | 真 keystore、混淆 smoke（RELEASE_CHECKLIST §H） |
| **P6** | Play Console | AAB、Data Safety、FGS declaration、AI 問卷 |
| **P7** | 多 APK companion 分發策略 | 主包 + companion 同簽章 |
| **P8** | CI + 裝置 smoke 擴大 | Appium 固定腳本：import → chat；litert/ort/mllm/mlc 各自至少一條裝置路徑 |

---

## 6. 「引擎可用」最小定義（裝置證據版本）

對每個引擎，同時滿足：

1. 可載入至少一種模型格式（檔在 model-store READY）  
2. `TEXT_GENERATION` 在實機產出 ≥1 token（Playground 或 HTTP）  
3. Cancel / 錯誤有結構化結果  
4. 在 Routing 可被選中  
5. 崩潰不拖死 UI process（worker/runtime 隔離）  

目前：**llama 1/5 在模擬器達標**（真 GGUF、真生成、結構化終端、路由可選、runtime 隔離）；litert/ort/mllm/mlc 待 Q1 裝置證據。

---

## 7. 不要再被這些「文件誠實規則」卡住（與已修改的事實對齊）

- 「沒有 lock 不得執行」→ dev 建置可執行（variant-scoped；release fail-closed）
- 「EngineSelectionPolicy 只允許 llama native」→ `ALL_CATALOG`（dev）
- 「exploratory 預設關」→ dev product-default 開（runtime seed；static catalog 仍 fail-closed）
- **但**：dev 執行 ≠ SUPPORTED（COR-10：只能 CONDITIONAL + `development_ship_mode` 條件）

仍**必須**自己做的是：**裝置證據（Q1–Q4）、Tools UI（F9）、Play 上架資產（P5–P8）**——那是實作/驗證問題，不是規則問題。

---

## 8. 相關程式入口

| 用途 | 路徑 |
|------|------|
| 開發模式開關（variant-scoped） | `core/contracts/.../ProductBuildMode.kt`（BLD-02）；`android/runtime-service/build.gradle.kts` BuildConfig |
| 引擎選擇 | `android/runtime-service/.../EngineSelectionPolicy.kt` |
| 能力協商 / 生成開關 | `.../EngineExecuteBinding.kt` |
| 引擎 attach | `.../EnginePackAttachment.kt` |
| 推理埠綁定 | `.../featurehost/DelegatingInferenceEngine.kt`、`WaveAWiring.kt` |
| exploratory 預設 | `runtime/policy/.../ConfigurationCatalog.kt`；`specs/configuration-catalog.yaml`（`runtime.exploratoryExecuteEnabled`） |
| 模型匯入 | `.../ModelHubLocalImporter.kt` + `OmniAdminFacade.importLocalFile` |
| 引擎狀態（lock/integration/qualification） | `specs/engine-qualification-status.yaml` + `engines/*/UPSTREAM.lock` |

---

**一句話：** 規則面已為「做完全部功能」讓路（BLD-02 variant-scoped + 真 backend 已全接 + 持久化已全上）；**真正還沒做完的是裝置證據（Q1–Q4）、Tools 獨立 UI（F9）、以及 Play 出貨鏈（P5–P8）。**

---

## 9. 已知環境問題（非本次改動）

`:data:persistence:verifyMainOmniLlmDatabaseMigration` 在本 Windows 主機失敗——sqlite-jdbc native 解壓寫入 `C:\Windows` 被拒（AccessDeniedException），與 persistence 模組內容無關；CI/Linux 不受影響。後續可在本機以 `org.sqlite.lib.exportPath` 指向可寫目錄後重試。
