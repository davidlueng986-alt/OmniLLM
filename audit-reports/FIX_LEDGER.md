# FIX_LEDGER — GA-Gaps 整合修復帳本（Stage 0 基線）

| 欄位 | 內容 |
|------|------|
| **分支** | `fix/ga-gaps`（自 `5b8a5b8` 切出） |
| **基線日期** | 2026-08-12（第三方深度審計） |
| **帳本建立者** | Stage 0 ledger-keeper |
| **涵蓋範圍** | 複審發現（D 系列）＋ 第三方 DEEP 審計（C-01…C-15）＋ 殘留登記簿（R 系列）＋ F2 引擎鎖 |

---

## 目標

關閉全部 D / C / R 開放項，使 `fix/ga-gaps` 合併後：
- 控制面耐久性真實（CommitLedger / Governor / ClientRegistration / Assets 不再 process-local）
- 產品 JTBD「生成助理文字」與「SAF→READY 真 GGUF」端到端可證
- Dashboard / Admin / UiSession 能力投影誠實（不 invent、不空 stub）
- llama build 可 fresh-clone 復現（digest == lock），SBOM / NOTICES / 契約回歸全綠
- 最終以 fresh-clone 全量複現 + FIX_LEDGER 全表覆核收斂

## 防回歸規則摘要

見下方「防回歸規則區」：①反向驗證測試 ②契約凍結 ③五閘門 ④互斥檔案權 ⑤spec-first ⑥fresh-clone 最終閘門 ⑦影響面清單 ⑧shell/git 紀律。

---

## 總表

| 項目ID | 來源 | 嚴重度 | 標題 | 負責代理(階段) | 完成定義 |
|--------|------|--------|------|----------------|----------|
| D1 | 複審 | 高 | llama build-info pin 47c7869 + fresh-clone digest 復現 | S1a build-repro-fixer | fresh clone 建置 .so digest == lock |
| D2 | 複審 | 高 | R16 digest 政策改 stripped-packaged variant + verify_llama_digest.py CI gate | S1a build-repro-fixer | CI gate 攔截 digest 漂移 |
| F2 | 複審 | 中 | llama capability-matrix.yaml NOT_LOCKED → LOCKED | S1a build-repro-fixer | 矩陣鎖定並與實作一致 |
| D22 | 複審 | 低 | specs/README.md 絕對路徑 → 相對 | S1a build-repro-fixer | 文件路徑可攜 |
| R10 | 複審 | 低 | libs.versions.toml 死項清理 + 引擎依賴入 catalog | S1a build-repro-fixer | catalog 與實際依賴一致 |
| D21 | 複審 | 低 | tag/HEAD/APK 三點對齊決策 | S1a build-repro-fixer | 決策紀錄入庫 |
| D10 | 複審 | 中 | jansi/sqlite-jdbc packaging excludes + sqldelight driver 修正 | S1b packaging-sbom-fixer | APK 減 ~6.4MB 且 16KB/契約測試綠 |
| D9 | 複審 | 中 | SBOM 重建 (CycloneDX + scope) + verify_sbom_vs_apk.py + THIRD_PARTY_NOTICES 補 16 條 | S1b packaging-sbom-fixer | SBOM↔APK 對照 CI 綠 |
| C-01 | DEEP | CRITICAL | Orchestrator←durable CommitLedger 接線 + INTENT 耐久測試 | S2a control-plane-fixer | 生產 wiring 傳入非 null ledger + 測試驗證 recordIntent/outcome 被持久化 |
| C-08 | DEEP | 高 | request fence 接 finishRecovery + ClientRegistration/Assets SQLDelight 耐久 | S2a control-plane-fixer | 重啟後 principal 存活 + fence 生效 |
| C-09 | DEEP | 高 | Governor SQLite 耐久分配帳本 (reservations/allocations 表 PLANNED→IMPLEMENTED) + 重啟復原測試 | S2a control-plane-fixer（決策：實作） | 帳本表落地 + kill/重啟復原測試綠 |
| R4/D7 | 複審 | 中 | COR-18 resourceVersion 單一來源 (SqlDelightInstallationResourceVersionPort) | S2a control-plane-fixer | 單一 port 且測試綠 |
| R9 | 複審 | 低中 | SingleWriterPolicy + ProcessIdentity 強化 | S2a control-plane-fixer | 政策覆蓋新寫入路徑 |
| R12 | 複審 | 低 | probeDeadlineMs 實作或移除 | S2a control-plane-fixer | 二擇一決策落地 |
| C-04 行 | DEEP | 高 | WaveAWiring 行交換（誠實能力投影 class 由 S2b 提供） | S2a control-plane-fixer | wiring 指向 S2b 提供之 port |
| C-02 | DEEP | CRITICAL | 控制面真實助理文字聚合（決策：實作）+ 端到端證明測試 | S2b transport-ui-fixer | 生產路徑輸出非空白 assistantText 且有 E2E 證據 |
| C-04 | DEEP | 高 | 誠實 Dashboard 能力投影 port（features/dashboard）+ 測試 | S2b transport-ui-fixer | 不再 invent SUPPORTED；測試綠 |
| C-05 | DEEP | 高 | Admin binder 路徑真實 ports + 解除關鍵 fail-closed | S2b transport-ui-fixer | LAN/load/pin/delete/token 走 plane-backed ports |
| C-06 | DEEP | 高 | UiSession 掛載 3 個 ViewModel + 非 stub 動作 | S2b transport-ui-fixer | diagnostics/contentReport/routing VM 附著且有實際動作 |
| C-11 | DEEP | 中 | principal 速率限制（transport 層） | S2b transport-ui-fixer | RPS/concurrency 執行器上線 + 測試 |
| D23 | 複審 | 低 | 契約微漂移 8 項 | S2b transport-ui-fixer | 對齊後契約測試綠 |
| D18 | 複審 | 低 | admin facade JSON 逃逸 | S2b transport-ui-fixer | 逃逸修正 + 測試 |
| C-03 | DEEP | CRITICAL | GGUF magic 驗證 + 隔離解析/試載 + SAF 大檔 FGS | S2c modelhub-engine-fixer | label≠parse 消除；信任誠實；FGS 大檔路徑可證 |
| C-07 | DEEP | 高 | LiteRT/ORT AAR 打包 + 政策下 attach + 能力 cell；MLC 誠實 gating（決策：接線） | S2c modelhub-engine-fixer | AAR 入 runtime-service、attach 受政策、MLC 誠實 gating |
| D3/D4 | 複審 | 中高·潛在 | mllm port-squat 防禦 + 身分探測 + LOCAL_ADMIN 顯式啟用 | S2c modelhub-engine-fixer | 防禦/探測測試綠 |
| R15-llama | 複審 | 中 | llama 部分資格化證據 cell 內容準備 | S2c modelhub-engine-fixer | 證據素材備妥待正式寫入 |
| D14 | 複審 | 低 | core/ports 測試 + API-10 完整配對 + API-16 失敗路徑 | S3a test-hardening | 新增測試全綠 |
| D15 | 複審 | 低 | PlaygroundStreamEventsLocalUiGateTest:76 套套邏輯清零 | S3a test-hardening | 測試不再套套 |
| D16 | 複審 | 低 | flaky 測試修正（session concurrency） | S3a test-hardening | 穩定綠 |
| D17 | 複審 | 低 | CI connected-tests set -euo pipefail | S3a test-hardening | CI 腳本嚴格模式 |
| C-15 | DEEP | 中 | 品質情境 Q-001… 證據映射 | S3a test-hardening | 情境→證據對照完成 |
| C-10/D11/D13 | DEEP/複審 | 中 | docs 鏡像 5 檔 + FEAT-SYSTEM 6 處 + validator 30/30 | S3b docs-specs | 鏡像同步 + validator 全過 |
| D12 | 複審 | 中 | llama-cpp.md digest 同步 | S3b docs-specs | 文件 digest == 實際 |
| D20 | 複審 | 低 | 狀態文件過期群 | S3b docs-specs | 狀態文件時效一致 |
| R3 | 複審 | 中 | LOADED_MODEL RESERVED→LOAD_FAILED spec 邊 + codegen + LoadCoordinator + 測試 | S3b docs-specs（spec-first） | spec→codegen→實作→測試 一致 |
| R15-cell | 複審 | 中 | 證據 cell 正式寫入 engine-qualification-status.yaml + 路線圖 | S3b docs-specs | 資格狀態文件誠實反映 |
| R22 | 複審 | 低中 | 總綱組裝工具（best effort） | S3b docs-specs | 工具可用（best effort） |
| R21-evidence | 複審 | 中 | 設備證據包收錄 repo e2e-artifacts/qualification/ | S3c evidence-device | 證據包入庫 |
| C-13 | DEEP | 中 | companion 對抗性儀器測試（模擬器可用時） | S3c evidence-device | 儀器測試執行並產出證據 |
| 最終閘門 | — | — | fresh clone 全量複現 + D1 digest 復現 | S4a full-regression-verifier | 全量複現綠 |
| 驗收 | — | — | FIX_LEDGER 全表覆核 | S4b acceptance-auditor | 全表完成且無造假 |

---

## 內容（逐項）

### S1a — build-repro-fixer

- **D1（複審/高）** llama build-info pin `47c7869` + fresh-clone digest 復現。完成定義：fresh clone 建置 `.so` digest == lock。
- **D2（複審/高）** R16 digest 政策改為 stripped-packaged variant，並以 `verify_llama_digest.py` 作 CI gate。完成定義：CI gate 可攔截 digest 漂移。
- **F2（複審/中）** llama `capability-matrix.yaml` 由 NOT_LOCKED → LOCKED。完成定義：矩陣鎖定且與實作一致。
- **D22（複審/低）** `specs/README.md` 絕對路徑改為相對路徑。完成定義：文件路徑可攜。
- **R10（複審/低）** `libs.versions.toml` 死項清理 + 引擎依賴納入 catalog。完成定義：catalog 與實際依賴一致。
- **D21（複審/低）** tag/HEAD/APK 三點對齊決策。完成定義：決策紀錄入庫。

### S1b — packaging-sbom-fixer

- **D10（複審/中）** jansi/sqlite-jdbc packaging excludes + sqldelight driver 修正。完成定義：APK 減 ~6.4MB 且 16KB/契約測試綠。
- **D9（複審/中）** SBOM 重建（CycloneDX + scope）+ `verify_sbom_vs_apk.py` + THIRD_PARTY_NOTICES 補 16 條。完成定義：SBOM↔APK 對照 CI 綠。

### S2a — control-plane-fixer

- **C-01（DEEP/CRITICAL）** Orchestrator←durable CommitLedger 接線 + INTENT 耐久測試。完成定義：生產 wiring 傳入非 null ledger + 測試驗證 recordIntent/outcome 被持久化。
- **C-08（DEEP/高）** request fence 接 `finishRecovery` + ClientRegistration/Assets SQLDelight 耐久。完成定義：重啟後 principal 存活且 fence 生效。
- **C-09（DEEP/高）** Governor SQLite 耐久分配帳本（reservations/allocations 表 PLANNED→IMPLEMENTED）+ 重啟復原測試。完成定義：帳本表落地 + kill/重啟復原測試綠。（決策：實作）
- **R4/D7（複審/中）** COR-18 resourceVersion 單一來源（`SqlDelightInstallationResourceVersionPort`）。完成定義：單一 port 且測試綠。
- **R9（複審/低中）** SingleWriterPolicy + ProcessIdentity 強化。完成定義：政策覆蓋所有新寫入路徑。
- **R12（複審/低）** `probeDeadlineMs` 實作或移除。完成定義：二擇一決策落地。
- **C-04 行（DEEP/高）** WaveAWiring 行交換：誠實能力投影 class 由 S2b 提供，S2a 完成 wiring 交換。

### S2b — transport-ui-fixer

- **C-02（DEEP/CRITICAL）** 控制面真實助理文字聚合（決策：實作）+ 端到端證明測試。完成定義：生產路徑輸出非空白 assistantText 且有 E2E 證據。
- **C-04（DEEP/高）** 誠實 Dashboard 能力投影 port（features/dashboard）+ 測試。完成定義：不再 invent SUPPORTED；測試綠。
- **C-05（DEEP/高）** Admin binder 路徑真實 ports + 解除關鍵 fail-closed。完成定義：LAN/load/pin/delete/token 走 plane-backed ports。
- **C-06（DEEP/高）** UiSession 掛載 3 個 ViewModel（diagnostics/contentReport/routing）+ 非 stub 動作。完成定義：VM 附著且有實際動作。
- **C-11（DEEP/中）** principal 速率限制（transport 層）。完成定義：RPS/concurrency 執行器上線 + 測試。
- **D23（複審/低）** 契約微漂移 8 項對齊。完成定義：對齊後契約測試綠。
- **D18（複審/低）** admin facade JSON 逃逸修正。完成定義：逃逸修正 + 測試。

### S2c — modelhub-engine-fixer

- **C-03（DEEP/CRITICAL）** GGUF magic 驗證 + 隔離解析/試載 + SAF 大檔 FGS。完成定義：label≠parse 消除、信任誠實、FGS 大檔路徑可證。
- **C-07（DEEP/高）** LiteRT/ORT AAR 打包 + 政策下 attach + 能力 cell；MLC 誠實 gating（決策：接線）。完成定義：AAR 入 runtime-service、attach 受政策、MLC 誠實 gating。
- **D3/D4（複審/中高·潛在）** mllm port-squat 防禦 + 身分探測 + LOCAL_ADMIN 顯式啟用。完成定義：防禦/探測測試綠。
- **R15-llama（複審/中）** llama 部分資格化證據 cell 內容準備。完成定義：證據素材備妥待正式寫入。

### S3a — test-hardening

- **D14（複審/低）** core/ports 測試 + API-10 完整配對 + API-16 失敗路徑。完成定義：新增測試全綠。
- **D15（複審/低）** `PlaygroundStreamEventsLocalUiGateTest:76` 套套邏輯清零。完成定義：測試不再套套。
- **D16（複審/低）** flaky 測試修正（session concurrency）。完成定義：穩定綠。
- **D17（複審/低）** CI connected-tests `set -euo pipefail`。完成定義：CI 腳本嚴格模式。
- **C-15（DEEP/中）** 品質情境 Q-001… 證據映射。完成定義：情境→證據對照完成。

### S3b — docs-specs

- **C-10/D11/D13（DEEP/複審/中）** docs 鏡像 5 檔 + FEAT-SYSTEM 6 處 + validator 30/30。完成定義：鏡像同步 + validator 全過。
- **D12（複審/中）** `llama-cpp.md` digest 同步。完成定義：文件 digest == 實際。
- **D20（複審/低）** 狀態文件過期群更新。完成定義：狀態文件時效一致。
- **R3（複審/中）** LOADED_MODEL RESERVED→LOAD_FAILED spec 邊 + codegen + LoadCoordinator + 測試（spec-first）。完成定義：spec→codegen→實作→測試一致。
- **R15-cell（複審/中）** 證據 cell 正式寫入 `engine-qualification-status.yaml` + 路線圖。完成定義：資格狀態文件誠實反映。
- **R22（複審/低中）** 總綱組裝工具（best effort）。完成定義：工具可用。

### S3c — evidence-device

- **R21-evidence（複審/中）** 設備證據包收錄 repo `e2e-artifacts/qualification/`。完成定義：證據包入庫。
- **C-13（DEEP/中）** companion 對抗性儀器測試（模擬器可用時）。完成定義：儀器測試執行並產出證據。

### S4a/S4b — 閘門與驗收

- **最終閘門（S4a full-regression-verifier）** fresh clone 全量複現 + D1 digest 復現。完成定義：全量複現綠。
- **驗收（S4b acceptance-auditor）** FIX_LEDGER 全表覆核。完成定義：全表完成且無造假。

---

## 防回歸規則區

1. **反向驗證測試**：每一「誠實化」改動（C-02/C-04/C-05/C-06/C-07）須伴隨反向證明——證明先前假裝成功之路徑現在會誠實失敗/回報。
2. **契約凍結**：`interfaces/http` 66 tests 為契約基準，任何改動不得破壞；引擎誠實規則（registry 僅在 QUALIFIED_WITH_ENVELOPE+PASS 才 SUPPORTED）為契約之一部分，禁止繞過。
3. **五閘門**：每波（S1a→S1b→S2a→S2b→S2c→S3a→S3b→S3c→S4a）結束時：lint、typecheck、unit tests、契約測試、16KB alignment 五閘門必綠。
4. **互斥檔案權**：`WaveAWiring`、`RuntimeControlPlane`、`ControlPlaneHttpHandler` 為互斥檔案，同一時段僅允許一個代理擁有編輯權，其餘代理不得並行修改（協調以 Stage 0 帳本為準）。
5. **spec-first（R3）**：任何 FSM/契約語義改動先改 spec，再 codegen，再實作，最後測試；順序不可顛倒。
6. **fresh-clone 最終閘門**：S4a 以全新 clone 全量建置複現，D1 digest 必須與 lock 一致；任何以既有工作樹為前提的「綠」不算數。
7. **影響面清單**：所有改動不得觸及——`llama.cpp` 引擎 pin 之外的產物、SBOM 內容、THIRD_PARTY_NOTICES（由 S1b 專責）、`engine-qualification-status.yaml`（由 S3b 專責）、`e2e-artifacts/qualification/`（由 S3c 專責）。
8. **shell/git 紀律**：所有指令限定 timeout（Start-Job 600s / 直跑）；依賴指令不得以 `;` 鏈接；每指令檢查 `$LASTEXITCODE`；git 只 `add <特定路徑>` + commit，禁 `add -A`/`commit -a`/`stash`/`checkout --`/`clean`/`push`。

---

## 決策紀錄區

| 日期 | 決策 | 內容 |
|------|------|------|
| 2026-08-12 | 產品決策：C-02 | 實作控制面真實助理文字聚合（不採 digests-only 產品決策） |
| 2026-08-12 | 產品決策：C-09 | 實作 Governor 耐久分配帳本（SQLite 表 PLANNED→IMPLEMENTED） |
| 2026-08-12 | 產品決策：C-07 | 政策下接線 peer 引擎 attach（MLC 採誠實 gating，不假裝） |
| 2026-08-12 | 流程決策 | 新分支 `fix/ga-gaps`（自 5b8a5b8 切出），第三方稽核報告（`DEEP_AUDIT_REPORT.md` + `audit-reports/`）納入 git 追蹤作為修復帳本權威 |
