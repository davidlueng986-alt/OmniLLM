# 開發者工作流

給要改程式、改合約或加模組的人。模組地圖與禁止依賴邊以 [`AGENTS.md`](../../AGENTS.md) 為準。

## 日常迴圈

```text
改 specs/ 或 Kotlin
  → generateContracts（若動到目錄）
  → 單元測試
  → checkContractDrift + 依賴邊
  → 針對模組 assemble / 安裝
```

聚焦測試比重建全世界快：

```powershell
.\gradlew.bat :core:state:test
.\gradlew.bat :runtime:orchestrator:test
.\gradlew.bat :interfaces:http:test
.\gradlew.bat :android:runtime-service:testDebugUnitTest
```

測試對照見 [`../architecture/testing.md`](../architecture/testing.md)。

## 權威與 codegen

| 產物 | 來源 | 去向 |
|---|---|---|
| Canonical types / errors / FSM | `specs/*.yaml` | `core/*/…/generated/` |
| OpenAPI | `specs/openapi/omnillm.openapi.yaml` | `:interfaces:http` |
| AIDL | `specs/aidl/omnillm-aidl.yaml` | `tools/codegen/extract_aidl.py` → `:interfaces:aidl` |
| SQL | `specs/database/omnillm-schema.sql` | `:data:persistence` + SQLDelight |

```powershell
pip install -r tools\codegen\requirements.txt
.\gradlew.bat generateContracts
.\gradlew.bat checkContractDrift
python tools\codegen\extract_aidl.py --check
```

產品包更新後，用 [`../MAINTENANCE.md`](../MAINTENANCE.md) 的同步腳本把 `docs/product/specs/` 拷回根目錄 `specs/`，再跑上述閘門。

## 新增 Feature Pack

1. 先在 `docs/product/docs/70-features/` 寫設計，必要時更新 `specs/` capability 對照。
2. 複製既有 `features/<peer>/` 的 `build.gradle.kts`。
3. 在 `settings.gradle.kts` `include(":features:<name>")`。
4. 只依賴 `:core:*` 與需要的 `:runtime:*`／`:interfaces:admin`。
5. UI 走 Admin facade，不要讓 `:android:app-ui` 依賴引擎或 DB writer。
6. 補單元測試。

模板：[feature-design-template.md](../product/templates/feature-design-template.md)。

## 新增 Engine Pack

1. 先寫 `docs/product/docs/80-engines/` 與 qualification schema。
2. 實作 `:engines:api` SPI，不要改 Orchestrator 或 transport 語義。
3. 宣告程序放置。未受信任加速必須走 `:android:companion-sandbox`。
4. Native `.so` 走 `:android:native` 或引擎自己的 JNI；所有 ABI 通過 16 KB 檢查。
5. 沒有 Stage 5 證據就保持 `UNQUALIFIED`。

模板：[engine-design-template.md](../product/templates/engine-design-template.md)。

## 程序拓樸（改 IPC 前必讀）

| 行程 | 角色 |
|---|---|
| 主行程 | 只有 UI |
| `:runtime` | 控制面、FGS、閘道、單一寫入 |
| `:engine_worker` | 同 UID 崩潰隔離 |
| `:parser` | `isolatedProcess` 有界解析 |
| `com.omnillm.companion` | 不同 UID 的未受信任加速 |

## CI

| 檔案 | 作用 |
|---|---|
| `.github/workflows/ci.yml` | PR／push：漂移、依賴、測試、組裝、16 KB |
| `.github/workflows/release.yml` | 可選簽名的 release；不上傳 Play |
| `tools/ci/local_ci.ps1` | 本機對齊 |

detekt **刻意未配置**。品質閘門是測試 + lint + 合約 + 架構邊 + 16 KB。

## 提交前檢查清單

- [ ] 沒有新的 `specs/` 未記載型別／錯誤碼
- [ ] generated 檔與 catalog 無漂移
- [ ] 沒有 UI → engine／DB writer 的新依賴
- [ ] 沒有 keystore、模型、`local.properties`
- [ ] `BUILD_STATUS.md` 若行為變了有更新（不要編造裝置證據）
