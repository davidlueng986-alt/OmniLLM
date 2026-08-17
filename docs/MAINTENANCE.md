# 倉庫維護

這份文件說明「產品文件包」與「Android 實作」如何在同一個 git 倉庫裡共存，以及之後怎麼更新才不會把權威搞亂。

## 為什麼是現在這個形狀

- **Gradle 根目錄 = 倉庫根目錄**  
  clone 之後立刻 `gradlew`，CI 路徑不用改，模組座標（`:core:canonical`）維持不變。
- **產品包整包放在 `docs/product/`**  
  保持原相對路徑，才能跑 `validate_repository.py`。
- **根目錄 `specs/` 仍是建置權威**  
  codegen、測試、CI 讀這裡。不要改成去讀 `docs/product/specs/`，以免產品包更新半套時弄壞建置。

不要為了「看起來更乾淨」把整個 Android 樹再搬進 `android/` 子目錄——那會一次打斷所有 Gradle、CI 與 agent 文件。

## 兩份 specs 的更新方向

```text
上游產品 ZIP
    ↓ 整包覆寫（保留相對路徑）
docs/product/          （規範閱讀 + 驗證器）
    ↓ 有意識地同步（見下方腳本）
specs/                 （codegen / CI / 執行期目錄）
    ↓ generateContracts
core/**/generated/
```

規則：

1. **改產品敘事**（憲章、旅程、ADR 說明）→ 只動 `docs/product/`。
2. **改機器可讀合約且要進 App** → 先改根目錄 `specs/`，跑 codegen 與測試，再決定是否回寫 `docs/product/specs/`。
3. **收到新的產品 ZIP** → 覆寫 `docs/product/`，比對與根目錄 `specs/` 的 diff，**不要盲目整包覆蓋實作 specs**。

實作可能暫時超前產品包（例如 schema 標了更多 `IMPLEMENTED` 表）。這是允許的；要在 PR 寫清楚，並盡快回寫產品包。

## 同步腳本

把產品包 specs 拷到實作工作副本（**會覆寫** `specs/`）：

```powershell
python tools\sync_product_specs.py
```

只看 diff、不寫入：

```powershell
python tools\sync_product_specs.py --check
```

方向相反（把實作 specs 回寫進產品包）必須人工審查，腳本預設不提供「反向覆寫」，以免把未審查的實作細節衝進規範包。

拷完之後一定要：

```powershell
.\gradlew.bat generateContracts
.\gradlew.bat checkContractDrift
.\gradlew.bat test
```

## 驗證產品包

```powershell
pip install PyYAML==6.0.3
python docs\product\tools\validate_repository.py docs\product
```

### 已知上游缺口

新版本產品 ZIP（`OmniLLM_產品文件完整包_新版本_繁體中文`）的 `MANIFEST.sha256` 列有：

```text
OmniLLM_建置前產品與架構設計總綱_產品版_繁體中文.md
```

該檔不在 ZIP 內。驗證器因此會報告：

- `missing compiled design compendium`

這不是 clone 損壞。閱讀入口改用 [`product/DOCUMENT-MAP.md`](product/DOCUMENT-MAP.md)。不要為了讓驗證變綠而編造總綱或改 MANIFEST digest。

## 什麼不該進 git

| 路徑／類型 | 原因 |
|---|---|
| `local.properties`、`keystore.properties`、`*.jks` | 本機路徑與簽名秘密 |
| `build/`、`.gradle/`、`gradle-home-mh/` | 建置快取 |
| `*.gguf`、裝置上的 `omnillm.db` | 權重與使用者資料 |
| 根目錄 `compile-*.txt`、`hs_err_pid*.log` | 本機編譯噪音 |
| `e2e-artifacts/models/`、截圖、logcat | 體積大且含機器路徑 |

`.gitignore` 已覆蓋上述模式。出貨請用乾淨 worktree，不要從塞滿 log 的 `Downloads\OmniLLM_Build` 直接 `git add .`。

## 建議的 git 工作方式

主實作歷史仍在 `Downloads\OmniLLM_Build\omnillm-android`。GitHub 出貨線是 worktree：

| Worktree | 分支 | 用途 |
|---|---|---|
| `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android` | `fix/ga-hardening` 等 | 日常開發、髒產物可留在工作區 |
| `C:\Users\daive\OmniLLM` | `pack/github-ship` | 文件、產品包、對 GitHub 的乾淨樹 |

```powershell
# 從既有 repo 再建一條乾淨工作樹
git -C C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android worktree add -b <branch> C:\Users\daive\OmniLLM-feature
```

推送到 GitHub 時推 worktree 裡審查過的 commit，不要推本機 cache。

## 版本與發布

- App 版本鎖在 `gradle/libs.versions.toml` 的 `appVersionName` / `appVersionCode`（主 App 與 companion 一起升）。
- 變更紀錄：[`CHANGELOG.md`](../CHANGELOG.md)
- Play 人工檢查：[`gradle/RELEASE_CHECKLIST.md`](../gradle/RELEASE_CHECKLIST.md)
- 不要把 tag、HEAD、APK 內嵌 revision 對不齊的組建稱為 GA。
