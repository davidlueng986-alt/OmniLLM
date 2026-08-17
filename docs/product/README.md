# OmniLLM 產品文件完整包（建置前設計）

本包是 OmniLLM — 統一的本地 LLM 引擎平台之建置前產品與架構設計 repository。

核心價值：聚合多種 local edge LLM 執行引擎，提供低技術門檻自動架設、統一調用與可視化監控。

## 閱讀方式

1. 先讀 `OmniLLM_建置前產品與架構設計總綱_產品版_繁體中文.md`。
2. 依 `DOCUMENT-MAP.md` 進入各權威來源文件。
3. `specs/` 保存 canonical machine-readable authority；Markdown 不應建立私有別名。
4. iOS／PC／IoT 僅為未來平台架構 roadmap，不包含日期、人力或開發排程。

## Package boundary

本產品包不包含 FSM 稽核報告、finding closure、歷史稽核附件或稽核結果。稽核材料以獨立 ZIP 交付。

## 完整性

- `MANIFEST.sha256`：除自身外所有檔案的 SHA-256。
- `specs/repository-index.yaml`：除自身與 manifest 外的 path／size／digest 索引。
- `tools/validate_repository.py`：可重跑的結構與完整性驗證器。
