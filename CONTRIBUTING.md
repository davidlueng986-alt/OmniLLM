# 貢獻指南

謝謝你願意改 OmniLLM。請先讀根目錄 [README.md](README.md) 與 [AGENTS.md](AGENTS.md)。這份文件只補「送出變更前要遵守的習慣」。

## 開始之前

1. 依 [docs/setup/prerequisites.md](docs/setup/prerequisites.md) 對齊 JDK 17、SDK 36、NDK 28.2.x。
2. 用乾淨 worktree 工作，避免把 `build/` 與編譯 log 混進 diff。
3. 確定你改的是哪一層權威：產品敘事、`specs/`，還是 Kotlin 實作。

## 變更分類

| 類型 | 先改哪裡 | 完成定義 |
|---|---|---|
| 產品敘事／ADR | `docs/product/` | 文件 ID 穩定，不自創 catalog 別名 |
| 合約（型別、錯誤、狀態、OpenAPI、AIDL、SQL） | 根目錄 `specs/` | `generateContracts` + `checkContractDrift` 通過 |
| Feature Pack | `features/<name>/` + 產品 `70-features` | 單元測試；不直連引擎 C API |
| Engine Pack | `engines/<name>/` + 產品 `80-engines` | SPI 對齊；無證據不標 QUALIFIED |
| UI | `:android:app-ui` | 只經 Admin／projection；不寫 DB、不載 native |
| CI／工具鏈 | `gradle/`、`tools/ci/`、`.github/` | 本機 `local_ci.ps1` 能重現 |

## 禁止

- 發明 `specs/` 沒有的 capability、error code、state、enum。
- `:android:app-ui` 依賴 `:engines:*` 或 persistence writer。
- 引擎 adapter 開啟 Room／SQLDelight 當寫入者。
- Worker／isolated／companion 開啟 token vault 或 catalog 可寫路徑。
- 提交金鑰、keystore、模型權重、真實使用者 DB。
- 把 dry-load 成功寫成來源信任或 `SUPPORTED`。

## 送出前

```powershell
.\gradlew.bat checkContractDrift
.\gradlew.bat checkModuleDependencyRules
.\gradlew.bat checkDependencyEdges
.\gradlew.bat test
```

行為有變就更新 `BUILD_STATUS.md` 或 `CHANGELOG.md`。只寫你驗證過的事。

## 授權

貢獻視為依 Apache License 2.0 授權。第三方程式碼必須可再散布，並更新 `THIRD_PARTY_NOTICES.md`。
