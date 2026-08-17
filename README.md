# OmniLLM

統一的**本地 Edge LLM 引擎平台**。Android 優先。

OmniLLM 把模型取得、裝置相容性、引擎選擇、推理生命週期、對外呼叫與可視化診斷收成同一個產品。使用者不必先成為 GGUF、量化、driver 或 native runtime 專家，也能在自己的裝置上可靠地跑本地模型。

本倉庫同時收錄：

- **實作 monorepo**（`core/`、`runtime/`、`android/` …）
- **產品文件完整包**（[`docs/product/`](docs/product/)）

| | |
|---|---|
| 目前軟體版本 | **0.2.0**（`versionCode` 2） |
| 授權 | [Apache License 2.0](LICENSE) |
| 預設平台 | Android 9+（`minSdk` 28），target / compile API 36 |
| 主套件 | `com.omnillm` |
| Companion 沙箱 | `com.omnillm.companion`（不同 UID，見 ADR-007） |

> 這份 README 是倉庫入口，不是狀態權威。  
> 「現在能做什麼、什麼還開著」請看 [`BUILD_STATUS.md`](BUILD_STATUS.md)。  
> 產品使命與能力邊界請看 [`docs/product/docs/00-product/product-charter.md`](docs/product/docs/00-product/product-charter.md)。

---

## 為什麼做這個產品

本地 LLM 的痛點通常不是「沒有引擎」，而是：

1. 格式、量化、backend、記憶體包絡與裝置差異把第一次成功推理變得很難。
2. 每個引擎的 request / error / session 語義不同，應用端難以統一整合。
3. 出問題時看不到「誰占用資源、為何變慢、是否降級、能不能恢復」。

OmniLLM 對應三項核心價值（產品憲章 `PROD-CHARTER`）：

| 核心價值 | 使用者實際得到什麼 |
|---|---|
| **低技術門檻自動架設** | 描述用途與偏好，系統處理裝置指紋、候選模型、取得、安裝與保守配置 |
| **統一調用** | 同一套 capability、request、event、error、Session、Job 語義；HTTP / AIDL / UI 共用 |
| **可視化監控** | Dashboard 與診斷把狀態、資源、降級與修復建議變成可採取行動的資訊 |

產品**不是**雲端帳號體系、訓練／微調平台，也不是跨裝置分散式推理。推理預設留在裝置內；任何網路能力都必須由使用者明確開啟。

---

## 能做什麼

| 功能 | 說明 |
|---|---|
| 自動架設 | 裝置發現、模型推薦、安全安裝、第一次推理 |
| ModelHub | Catalog／下載／本機匯入、隔離驗證、安裝與刪除 |
| Playground | 本機 Chat、embeddings、串流與取消 |
| Developer Server | 本機 loopback HTTP（OpenAPI）與 AIDL 客戶端 |
| LAN | 預設關閉；明確啟用後才提供 TLS 與短期配對 |
| Dashboard | 健康、資源、追蹤與可觀測狀態 |
| Benchmark | 可重現的量測 profile／run |
| 診斷匯出 | 最小化、可預覽、可還原的診斷包 |
| 多模型路由 | 明示 fallback，不靜默更換 revision |
| Structured tools | 結構化輸出與 tool calling |
| 管理與 Job | 長工作、命令帳本、復原 |
| AI 內容回報 | Play 要求的應用內 flag／report |

引擎 Pack（設計齊全；**資格細胞一律誠實標示**）：

| 引擎 | 上游鎖定 | 實作 | 資格 |
|---|---|---|---|
| llama.cpp | LOCKED（b9999） | 真實 JNI + vendored 上游；模擬器上驗證過 GGUF 生成 | `UNQUALIFIED` |
| LiteRT-LM | LOCKED（v0.15.0） | 官方 SDK 橋接 | `UNQUALIFIED` |
| MLC-LLM | pin（完整 lock 待補） | runtime binding | `UNQUALIFIED` |
| mllm | LOCKED（2.0.0） | in-app server backend | `UNQUALIFIED` |
| ONNX Runtime GenAI | LOCKED（0.14.0） | GenAI Java API | `UNQUALIFIED` |

沒有裝置資格證據，就不會把能力標成 `SUPPORTED`。這是產品規則，不是還沒寫完的行銷文案。

---

## 五分鐘開始

完整步驟（含 Windows）見 **[安裝與建置指南](docs/setup/README.md)**。最短路徑：

```powershell
git clone https://github.com/davidlueng986-alt/OmniLLM.git
cd OmniLLM

# 指向本機 Android SDK（只留在本機，勿提交）
Copy-Item local.properties.example local.properties
# 編輯 local.properties：
# sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk

.\gradlew.bat :android:app-ui:assembleDebug
```

安裝到裝置或模擬器：

```powershell
adb install -r android\app-ui\build\outputs\apk\debug\app-ui-debug.apk
```

若要走未受信任加速路徑，另外安裝 companion（不同套件／UID）：

```powershell
.\gradlew.bat :android:companion-sandbox:assembleDebug
adb install -r android\companion-sandbox\build\outputs\apk\debug\companion-sandbox-debug.apk
```

接下來在 App 內完成 Onboarding → 匯入或取得模型 → Playground 第一次對話。不需要先記住引擎名稱。

**最低工具鏈**

| 工具 | 鎖定版本 |
|---|---|
| JDK | 17 |
| Gradle Wrapper | 9.5.0（已含於倉庫） |
| Android Gradle Plugin | 9.3.0 |
| Kotlin | 2.2.0 |
| compileSdk / targetSdk | 36 |
| minSdk | 28 |
| NDK | 28.2.13676358 |
| Python 3 | 合約產生與 CI 閘門（`tools/codegen/requirements.txt`） |

---

## 倉庫怎麼組織

Gradle 根目錄就是倉庫根目錄：clone 之後直接 `.\gradlew.bat`，不必再進子資料夾。產品文件獨立放在 `docs/product/`，不干擾建置圖。

```text
OmniLLM/
├── README.md                 ← 你現在看的入口
├── CONTRIBUTING.md           ← 貢獻與模組規則摘要
├── AGENTS.md                 ← 給人與 coding agent 的模組地圖
├── BUILD_STATUS.md           ← 實作狀態清單
├── LICENSE / NOTICE
│
├── docs/
│   ├── README.md             ← 文件地圖
│   ├── setup/                ← 安裝、建置、第一次執行
│   ├── product/              ← 產品文件完整包（規範權威）
│   └── architecture/         ← 實作備註（不可覆寫產品規範）
│
├── specs/                    ← 實作使用的機器可讀合約（工作副本）
├── tools/                    ← codegen、CI、產品 specs 同步
│
├── core/                     ← 可攜純 Kotlin：型別、狀態機、合約、錯誤
├── data/                     ← 持久化與模型倉（只有控制面可寫）
├── runtime/                  ← 單一寫入控制面
├── engines/                  ← Engine Pack adapters
├── interfaces/               ← HTTP / AIDL / Admin
├── features/                 ← 12 個 Feature Pack
└── android/                  ← 程序適配：UI、runtime、worker、sandbox、native
```

依賴只能向內：

```text
體驗（app-ui）
  → 介面 / Admin
    → 控制面（runtime）
      → 引擎抽象 + 資料 + 平台埠
        → 各引擎 adapter / Android OS adapter
```

UI 行程不得載入 native 引擎，也不得寫資料庫（`INV-001`）。只有 runtime 控制面是 DB／model store／token 的單一寫入者（`ADR-010`）。

---

## 文件從哪裡讀

| 你想… | 去這裡 |
|---|---|
| 安裝、建置、第一次跑起來 | [`docs/setup/README.md`](docs/setup/README.md) |
| 產品是什麼、能力與邊界 | [`docs/product/docs/00-product/`](docs/product/docs/00-product/) |
| 完整文件地圖 | [`docs/product/DOCUMENT-MAP.md`](docs/product/DOCUMENT-MAP.md) |
| 架構不變式與程序拓樸 | [`docs/product/docs/20-architecture/`](docs/product/docs/20-architecture/) |
| 功能設計 | [`docs/product/docs/70-features/`](docs/product/docs/70-features/) |
| 引擎整合標準 | [`docs/product/docs/80-engines/`](docs/product/docs/80-engines/) |
| 機器可讀合約 | 產品包 [`docs/product/specs/`](docs/product/specs/)；實作工作副本 [`specs/`](specs/) |
| 現在實作到哪 | [`BUILD_STATUS.md`](BUILD_STATUS.md) |
| 怎麼加 Feature / Engine | [`AGENTS.md`](AGENTS.md)、[`CONTRIBUTING.md`](CONTRIBUTING.md) |
| 維護產品包與 specs 同步 | [`docs/MAINTENANCE.md`](docs/MAINTENANCE.md) |

權威順序（不可顛倒）：

1. 機器可讀 `specs/`（型別、錯誤、狀態、OpenAPI、AIDL、SQL）
2. 產品規範文件（`docs/product/`，含 ADR 與 Android baseline）
3. `AGENTS.md` 與 Gradle 模組依賴邊
4. `docs/architecture/` 實作備註

**禁止**在程式裡發明 `specs/` 沒有的 type、enum、state、error 或 capability ID。

---

## 常用指令

```powershell
# 模組清單
.\gradlew.bat omnillmModules

# 合約產生與漂移閘門
pip install -r tools\codegen\requirements.txt
.\gradlew.bat generateContracts
.\gradlew.bat checkContractDrift

# 架構邊（UI 隔離、引擎不得寫 DB）
.\gradlew.bat checkModuleDependencyRules
.\gradlew.bat checkDependencyEdges

# 單元測試（不需要裝置）
.\gradlew.bat test

# 組裝
.\gradlew.bat :android:app-ui:assembleDebug
.\gradlew.bat :android:app-ui:assembleRelease
.\gradlew.bat :android:companion-sandbox:assembleDebug

# 與 CI 對齊的本機閘門
.\tools\ci\local_ci.ps1
```

Unix / macOS 使用 `./gradlew` 與 `bash tools/ci/local_ci.sh`。

驗證產品文件包：

```powershell
pip install PyYAML==6.0.3
python docs\product\tools\validate_repository.py docs\product
```

新版本產品包的 `MANIFEST.sha256` 列有連續閱讀總綱，但上游 ZIP 未附該檔；驗證器會報告此缺口。導航請改走 [`DOCUMENT-MAP.md`](docs/product/DOCUMENT-MAP.md)。詳見 [`docs/MAINTENANCE.md`](docs/MAINTENANCE.md)。

---

## 誠實的現況

| 已完成 | 仍開放 |
|---|---|
| 43 個 Gradle 模組、12/12 Feature Pack 掛在控制面 | 五個引擎皆為 `UNQUALIFIED`（缺裝置資格證據） |
| llama.cpp 真實 GGUF 路徑（模擬器驗證） | 實機多程序 E2E 與 OEM 矩陣 |
| SQLDelight 控制面帳本（claim／commit／session／jobs 等） | 部分 schema 表仍標 `PLANNED` |
| HTTP OpenAPI、AIDL、Compose UI 殼 | Tools 頂層目的地、部分 UI 深度 |
| CI：合約漂移、依賴邊、單元測試、16 KB native | Play Console 上傳為人工流程，未自動化 |

Debug／dev 組建可在開發模式下探索執行；**release 永遠 fail-closed**。開發模式不會把能力假裝成 `SUPPORTED`。

---

## 安全與隱私（摘要）

- Prompt、模型與推理預設留在裝置。
- 同 UID worker 只做崩潰隔離，**不是**安全沙箱。
- 未受信任的加速推理必須走不同套件／UID 的 companion。
- Token 只顯示一次明文；LAN 預設關閉。
- 不要提交 `local.properties`、`keystore.properties`、`.jks` 或模型權重。

---

## 授權

原始碼為 [Apache License 2.0](LICENSE)。第三方告知見 [NOTICE](NOTICE) 與 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
