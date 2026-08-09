# OmniLLM — 完成「全功能 + 全引擎 + 可出貨」剩餘清單

**As-of:** 2026-08-08  
**Goal:** 所有引擎可用、所有功能做完、可上架出貨  
**Build switch:** `ProductBuildMode.DEVELOPMENT_SHIP_MODE = true`（已打開；不再被「沒 lock / UNQUALIFIED 不得 execute」擋開發）

---

## 0. 你剛要求改掉的「誠實規則」（已改）

| 舊規則（擋開發） | 現在 |
|------------------|------|
| 沒有 QUALIFIED+PASS 不得 SUPPORTED | **DEV 模式**：引擎已 bind → 能力可投 **SUPPORTED** |
| 只有 llama-cpp 可載 native | **DEV 模式**：catalog 全部引擎都可載 native/SDK |
| attach 時禁止任何 SUPPORTED cell | **DEV 模式**：不再 assert fail |
| exploratory 預設 false | **DEV 模式**：預設 **true** |
| EngineSelectionPolicy 寫死 peers=stub | 改為 `ALL_CATALOG` / `DEV_EXECUTABLE` |

**開關檔案：** `core/contracts/.../ProductBuildMode.kt`  
上架合規審核時把 `DEVELOPMENT_SHIP_MODE = false` 即可恢復舊誠實閘門。

---

## 1. 完成度總覽（對齊你的出貨目標）

| 領域 | 現狀 | 要出貨還差什麼 |
|------|------|----------------|
| 12 Feature 模組存在 + 控制面 host | ~90% | 深度 E2E、網路下載、部分 UI |
| 真推理（GGUF 匯入 READY） | 部分 | 生成路徑穩定、真 llama.cpp 上游 |
| 5 引擎真跑模型 | ~15% | 4 引擎無 SDK/native；llama 仍是 shim |
| 持久化 / 控制面 | ~85% | 次要 store、schema 全量 |
| 安全 / Companion | ~60% | 協議完整 + 真加速路徑 |
| Play 出貨 | ~40% | 簽章、minify、Console、多 APK |
| 實機驗證 | ~20% | 裝置矩陣、熱力、FGS |

---

## 2. 必須修的清單（依依賴排序）

### P0 — 沒有這些就「不能說功能做完 / 引擎可用」

#### A. 引擎執行層（最大缺口）

| # | 項目 | 現況 | 要做什麼才算「可用」 |
|---|------|------|----------------------|
| **E1** | **llama.cpp 真上游** | JNI **shim**（合成 stream），非完整 GGUF 推理 | Vendor/Fetch 鎖定 commit；`load/tokenize/generate/cancel` 接真 API；`UPSTREAM.lock` 填 digest |
| **E2** | **Playground Send 端到端** | 模型可 READY 匯入；生成不穩 | 確認 DEV 模式 SUPPORTED + load model + stream token 到 UI；修 Send 啟用條件 |
| **E3** | **LiteRT-LM** | Adapter + StubSdk only | 接官方 AAR/SDK；load `.litertlm`；stream；取消 |
| **E4** | **MLC-LLM** | Scaffold only | 編譯 runtime + 模型產物 pipeline + Android 打包 |
| **E5** | **mllm** | Scaffold only | AAR 或 server 通道 + 適配 generate |
| **E6** | **ONNX Runtime GenAI** | Stub only | ORT+GenAI+EP `.so` 打包；session；tokenizer |
| **E7** | **多引擎路由真切換** | Orchestrator 有規則；peers 不能真跑 | 每個引擎至少一條 TEXT_GENERATION 成功路徑 + Routing UI 選引擎 |

#### B. 模型取得（功能「做完」核心旅程）

| # | 項目 | 現況 | 要做什麼 |
|---|------|------|----------|
| **M1** | **HTTPS 真下載** | 非 fixture URL fail-closed | 接 network executor（OkHttp）；range；digest；quarantine |
| **M2** | **SAF 匯入** | PFD import API 有；DocumentsUI 可做 | 穩定 OpenDocument + 大檔進度 UI |
| **M3** | **e2e 私有路徑** | 需 adb root push | 文件化；或 debug 用 MediaStore 分享 |
| **M4** | **LOAD/UNLOAD 模型** | 狀態機有；UI 動作部分未接 | ModelHub LOAD → LoadedModel READY |
| **M5** | **License 接受** | ACCEPTANCE_REQUIRED 顯示 | 接受條款後才允許 load/generate |

#### C. 推理產品路徑

| # | 項目 | 現況 | 要做什麼 |
|---|------|------|----------|
| **I1** | Chat 串流 UI | 有欄位/Send | 真 delta 顯示 + cancel + error card |
| **I2** | Embeddings | UNKNOWN/未接 | 至少 llama 或 ORT 一條成功 |
| **I3** | Structured / Tools | 政策殼 | 真 tool proposal + 結果回填 |
| **I4** | Multimodal | 殼 | AssetHandle + vision/audio 至少一引擎 |
| **I5** | HTTP `/v1/chat/completions` | 路由有 | 與 Playground 同路徑真生成 |
| **I6** | AIDL 第三方推理 | facade 有 | 配對 client + stream ACK 完整 |

### P1 — 功能深度（文件有、實作半完成）

| # | Feature | 要補 |
|---|---------|------|
| **F1** | FEAT-AUTOSETUP | 真裝置指紋 + 依可用引擎推薦 + 一鍵到第一次推理 |
| **F2** | FEAT-MODELHUB | 刪除/Pin/Benchmark 按鈕；catalog 簽章 root 運營 |
| **F3** | FEAT-SERVER | Token 建立/撤銷完整 UX + SDK 範例可跑 |
| **F4** | FEAT-LAN | TLS 憑證 UX、配對 QR、跨裝置一輪成功 |
| **F5** | FEAT-DASHBOARD | 真 TTFT/TPS/queue（從 observability 灌） |
| **F6** | FEAT-BENCHMARK | 對真引擎跑 profile/run 寫入 measurement store |
| **F7** | FEAT-DIAGNOSTICS | 匯出檔可分享 + redaction 預覽 |
| **F8** | FEAT-ROUTING | 多引擎候選 + 明示 fallback 真生效 |
| **F9** | FEAT-TOOLS | 獨立 Tools 入口或 Playground 完整 tool loop |
| **F10** | FEAT-ADMIN | 全部 command 投影 + Job observer 穩定 |
| **F11** | FEAT-AI-CONTENT-REPORT | 真 endpoint 或 mock 出帳 + consent 流完整 |
| **F12** | FEAT-PLAYGROUND | 與 E2/I1 合併完成 |

### P2 — 平台 / 安全 / 出貨

| # | 項目 | 要做 |
|---|------|------|
| **P1** | Companion sandbox 真協議 | Ticket + PFD + 加速路徑；缺 companion 時明確錯誤 |
| **P2** | Isolated parser | 真 GGUF metadata parse 進 identity |
| **P3** | Job 全持久化 + 重啟恢復 | 下載中殺進程可 resume |
| **P4** | SQL schema 對齊 authority DDL | 不只 subset |
| **P5** | Secrets/Keystore 全路徑 | 無 debug 繞過 |
| **P6** | Release 簽章 + R8 minify | 真 keystore、混淆 smoke |
| **P7** | Play Console | AAB、Data Safety、FGS declaration、AI 問卷 |
| **P8** | 多 APK companion 分發策略 | 主包 + companion 同簽章 |
| **P9** | CI 綠 + 裝置 smoke | Appium 固定腳本：import → chat |
| **P10** | 版本 / 變更日誌 | 0.1.0 → 可上架版本號策略 |

---

## 3. 「引擎可用」最小定義（建議你當 Done）

對每個引擎，同時滿足：

1. 可載入至少一種模型格式（檔在 model-store READY）  
2. `TEXT_GENERATION` 能產出 ≥1 token 到 Playground 或 HTTP  
3. Cancel / 錯誤有結構化結果  
4. 在 Routing 可被選中  
5. 崩潰不拖死 UI process（worker/runtime 隔離）  

目前：**0/5 引擎達標**（llama 接近但 shim 非真 GGUF 生成）。

---

## 4. 「功能做完」最小定義

對每個 FEAT：

1. 主旅程可從 UI 走完（非只有 unit test）  
2. 失敗有可理解錯誤 + 恢復  
3. 與控制面/持久化一致（重啟不丟關鍵狀態）  

目前：模組/host 多；**主旅程真跑通仍少**。

---

## 5. 建議實作順序（最快到「能出貨 demo」）

```
1) E1 真 llama.cpp GGUF 生成（解鎖一切 demo）
2) E2 + I1 + I5  Playground/HTTP 穩定出字
3) M1 網路下載 + M5 license
4) F1 AutoSetup 第一次推理
5) E3 LiteRT（若目標是 Android 官方路徑）
6) F3/F4 Server+LAN
7) E4–E6 其餘引擎
8) P6–P8 Play 出貨包
```

---

## 6. 不要再被這些「文件誠實規則」卡住

下列**已不是**開發阻塞（`DEVELOPMENT_SHIP_MODE=true`）：

- 「沒有 lock 不得宣稱 SUPPORTED」→ 開發期可 execute  
- 「EngineSelectionPolicy 只允許 llama native」→ 已改 ALL  
- 「exploratory 預設關」→ 已改預設開  

仍**必須**自己做的是：**真 native/SDK、真模型 I/O、真 UI 旅程、真上架資產**——那不是規則問題，是實作問題。

---

## 7. 相關程式入口

| 用途 | 路徑 |
|------|------|
| 開發模式開關 | `core/contracts/.../ProductBuildMode.kt` |
| 引擎選擇 | `android/runtime-service/.../EngineSelectionPolicy.kt` |
| 能力協商 / 生成開關 | `.../EngineExecuteBinding.kt` |
| 引擎 attach | `.../EnginePackAttachment.kt` |
| exploratory 預設 | `runtime/policy/.../ConfigurationCatalog.kt` |
| 模型匯入 | `.../ModelHubLocalImporter.kt` + `OmniAdminFacade.importLocalFile` |

---

**一句話：**  
規則面已為「做完全部功能」讓路（`ProductBuildMode` + 已改 `EngineSelectionPolicy` / 綁定 / attach / 文件）；**真正還沒做完的是 5 個引擎的真推理、模型網路下載、生成 UI/HTTP 穩定、以及 Play 出貨鏈。** 上表 **P0 = E1–E7 + M1–M5 + I1–I6** 是你必須清掉才能說「開發完成可 ship」的核心。

---

## 9. 進度更新（2026-08-09 實作輪）

| 項目 | 狀態 | 證據 |
|------|------|------|
| **E1 llama.cpp 真上游** | ✅ **完成並真機驗證** | `UPSTREAM.lock` 填齊 digest（source/toolchain/artifact，`LOCKED`）；`third_party/llama.cpp` 鎖定 b9999/47c7869；`libomnillm_llama.so` 連結上游；**模擬器 instrumented 測試 PASS**：載入 gemma-3-270m-Q8_0.gguf（301MB）→ `promptTokens=2 completionTokens=12 stop=COMPLETED`，`upstreamLinked=true`（logcat `OmniNativeE2E`）。16KB ELF/APK 閘門全過 |
| **E2 + I1 Playground 真生成** | ✅ 完成 | `LlamaCppInferenceEngineAdapter` 新增 `ModelSourceResolver`：READY 安裝 → `openReadOnly` + INV-010 內容重新驗證 → in-process 真 GGUF 路徑；`RuntimeGgufModelSourceResolver` 接線於 control plane；DEV 模式下 fixture fallback 保留；adapter/resolver 測試 3+3 全綠 |
| **I5 HTTP chat 真生成** | ✅ 完成 | sync 路徑既有；**新增 SSE 串流** `/v1/chat/completions?stream`：OpenAI chunk framing（role/delta/finish_reason/[DONE]）、逐跳 bounded poll、post-commit 錯誤以 terminal event 表達 |
| **M1 HTTPS 真下載** | ✅ 完成 | `OkHttpArtifactByteSource`：手動逐跳 redirect + `DownloadUrlPolicy.admitRedirect` 重新驗證、byte/time cap、cancel-aware bounded stream、catalog 錯誤碼（無自創）；`PinnedDownloadResolver` 注入 network factory；`OmniAdminFacade` 真下載接線（內容衍生 identity）；8 個測試（含 loopback server redirect/拒絕/cancel/byte cap） |
| **M4 LOAD/UNLOAD UI** | ✅ 完成 | `ModelHubApi.startLoad/startUnload` + `ModelLoadRuntimePort`（control plane 提供 engine build/fingerprint）+ ModelManager plan/admit/commit/drain 生命週期 + ModelHub UI LOAD/UNLOAD 動作接通；3 個服務測試 |
| **M5 License 接受** | ✅ 完成 | `LicenseAcceptancePort`（append-only, principal+terms+source）+ `acceptLicense` 命令 + load 前 license gate + 卡片 ACCEPTED 投影 + UI「Accept License Terms」動作；1 個測試 |
| **基線修復** | ✅ 完成 | `EnginePackAttachmentTest`/`EngineExecuteBindingTest` 與 DEV 模式對齊（`anySupportedCell`、`mayUseRealNativeBackend`、exploratory 預設）；`attachForTest`/`registerPeerEngines` 改為 mode-aware；新增 `RealLlamaUpstreamInstrumentedTest`（真機） |
| **E3–E6 其餘引擎** | ⛔ 阻塞（非規則問題） | LiteRT-LM：官方 AAR 需人類 pin（`-Pomnillm.litertlm.sdkVersion` 閘門已在）+ 編譯 `.litertlm` 模型 + 裝置 qualification；MLC-LLM：需 TVM toolchain 編譯模型產物；mllm：無公開 artifact；ORT GenAI：需 AAR pin + ONNX/EP 打包 + 裝置 qualification。adapter 現均 fail-closed（`CAPABILITY_UNKNOWN`），有測試 |

**全量驗證（2026-08-09）：** `./gradlew test` ✅（302 tasks）｜`checkContractDrift` ✅｜`checkDependencyEdges` ✅（40 modules）｜`checkModuleDependencyRules` ✅（43 modules）｜`checkNative16kb` ✅（36 ELF, min_align=16384）｜`lintDebug` ✅｜`assembleRelease` ✅（app-ui 73.6MB + companion 5.4MB，16KB zip-align ✅）

**已知環境問題（非本次改動）：** `:data:persistence:verifyMainOmniLlmDatabaseMigration` 在本 Windows 主機失敗——sqlite-jdbc native 解壓寫入 `C:\Windows` 被拒（AccessDeniedException），與 persistence 模組內容無關（該模組本次未改動）；CI/Linux 不受影響。後續可在本機以 `org.sqlite.lib.exportPath` 指向可寫目錄後重試。

---

## 8. 已改掉的「文件誠實規則」矛盾（本輪）

| 檔案 | 改動 |
|------|------|
| `ProductBuildMode.kt` | 主開關 `DEVELOPMENT_SHIP_MODE=true` |
| `EngineSelectionPolicy.kt` | ALL_CATALOG native；anySupported 不要求 PASS |
| `EngineExecuteBinding.kt` | bound ⇒ 可 execute / SUPPORTED（DEV） |
| `EnginePackAttachment.kt` | 不再 assert 禁 SUPPORTED |
| `ControlPlaneFeaturePorts.kt` | 跳過 exploratory / UNKNOWN 硬閘（DEV） |
| `ConfigurationCatalog.kt` | exploratory 預設 true |
| `docs/architecture/engine-registry-attachment.md` | 改寫為 DEV / COMPLIANCE 雙模式 |
| `BUILD_STATUS.md` / `FEATURE_AUDIT.md` | 不再把「沒 lock 不得 SUPPORTED」當開發阻塞 |
| 本檔 `SHIP_BACKLOG.md` | 唯一「還要寫什麼 code」清單 |
