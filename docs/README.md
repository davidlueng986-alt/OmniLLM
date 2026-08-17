# OmniLLM 文件地圖

先決定你要解決的問題，再進對應目錄。不要把實作備註當成產品規範，也不要把產品敘事當成「目前程式已經做到哪」。

## 快速分流

| 目的 | 入口 |
|---|---|
| 把專案在本機建起來、裝到裝置 | [setup/README.md](setup/README.md) |
| 理解產品是什麼、做什麼、不做什麼 | [product/docs/00-product/product-charter.md](product/docs/00-product/product-charter.md) |
| 查某份規範文件的穩定 ID | [product/DOCUMENT-MAP.md](product/DOCUMENT-MAP.md) |
| 查模組、依賴邊、如何加 Feature／Engine | [../AGENTS.md](../AGENTS.md) |
| 查實作完成度與已知缺口 | [../BUILD_STATUS.md](../BUILD_STATUS.md) |
| 維護產品包、specs 同步、權威衝突 | [MAINTENANCE.md](MAINTENANCE.md) |
| 貢獻流程 | [../CONTRIBUTING.md](../CONTRIBUTING.md) |

## 目錄

```text
docs/
├── README.md                 ← 本頁
├── MAINTENANCE.md            ← 倉庫維護契約
├── setup/                    ← 給人看的安裝與開發指南
├── product/                  ← 產品文件完整包（原樣收錄）
│   ├── DOCUMENT-MAP.md
│   ├── docs/00-product … 90-future-platforms
│   ├── governance/           ← ADR、風險、變更協議
│   ├── specs/                ← 產品側機器可讀權威
│   ├── templates/
│   └── tools/validate_repository.py
└── architecture/             ← Android 實作備註（非產品權威）
```

## 產品文件怎麼讀

產品包自己的閱讀順序：

1. [product/README.md](product/README.md)
2. [product/DOCUMENT-MAP.md](product/DOCUMENT-MAP.md)
3. 依領域進入 `product/docs/00-product` … `90-future-platforms`
4. 機器可讀合約在 [product/specs/](product/specs/)；Markdown **不得**自創別名

領域入口：

| 領域 | 路徑 |
|---|---|
| 產品與價值 | [product/docs/00-product/](product/docs/00-product/) |
| 體驗 | [product/docs/10-experience/](product/docs/10-experience/) |
| 系統架構 | [product/docs/20-architecture/](product/docs/20-architecture/) |
| 核心平台 | [product/docs/30-core-platform/](product/docs/30-core-platform/) |
| 領域與資料 | [product/docs/40-domain-data/](product/docs/40-domain-data/) |
| 安全與可靠性 | [product/docs/50-security-reliability/](product/docs/50-security-reliability/) |
| Android 基線 | [product/docs/60-android/](product/docs/60-android/) |
| 功能 | [product/docs/70-features/](product/docs/70-features/) |
| 引擎 | [product/docs/80-engines/](product/docs/80-engines/) |
| 未來平台 | [product/docs/90-future-platforms/](product/docs/90-future-platforms/) |

iOS／PC／IoT 只有架構方向，不含時程或人力承諾。

## 兩份 `specs/` 不要搞混

| 路徑 | 角色 |
|---|---|
| `docs/product/specs/` | 產品文件包內的規範快照 |
| 倉庫根目錄 `specs/` | **程式碼、codegen、CI 實際使用的工作副本** |

兩者應該對齊，但實作過程可能短暫超前產品包。衝突時：**以根目錄 `specs/` 建置，再依 [MAINTENANCE.md](MAINTENANCE.md) 回寫或重拷。** 不要在 Kotlin 裡手寫一份第三套 enum。
