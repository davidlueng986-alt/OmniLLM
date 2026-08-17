---
id: "FEAT-TOOLS"
title: "Structured Output 與 Tool Calling"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "feature-team"
lastReviewed: "2026-07-31"
---

# Structured Output 與 Tool Calling

## 0. 核心價值對應
- **低技術門檻自動架設**：由平台驗證 schema、能力與錯誤，使用者不必為每個引擎手寫 grammar adapter。
- **統一調用**：native constrained decoding、post-validation 與 unsupported 都投影同一 structured/tool result，但 actual mode 必須揭露。
- **可視化監控**：顯示 schema complexity、validation attempts、tool proposal、permission、fallback 與失敗原因。

## 1. Structured Output
Request 使用版本化 JSON Schema 子集。入口先限制 bytes、depth、node、properties、enum、regex、reference expansion 與 compile time。Schema canonical digest 進 request hash、plan、measurement profile 與 cache key。

## 2. 能力與執行模式
Capability descriptor 分為 `NATIVE_CONSTRAINED`、`POST_VALIDATE`、`REPAIR_RETRY`、`UNSUPPORTED`，並附 engine/model/workload envelope。Caller policy 決定是否允許非 native 模式、最大 attempts 與 token budget；系統不能靜默從 constrained 退為普通文字。

## 3. Tool Calling
Tool 定義包含 stable tool ID、schema digest、description、data classification、required host permission 與 max argument bytes。Model 只產生 `ToolProposal`；OmniLLM 不執行 shell、任意 URL、檔案或 host command。Host 回傳 `ToolResult`，綁 request、proposal ID、attempt 與 idempotency key。

## 4. Session 與冪等
Proposal／result 進 conversation text/KV 前需有明確 commit point。Reply loss 後 host 查詢 proposal state，不重複執行非冪等工具。若 host outcome 不可證明，Session 不能假裝已有或沒有 tool result，應要求人工確認或建立新 Session。

## 5. 安全與資源
限制 tool count、schema/argument/output bytes、validation CPU/time、repair attempts 與 total token budget。Prompt injection 是模型行為風險；UI/SDK 顯示資料將傳給哪個 host tool。LAN/AIDL scopes可按 tool allowlist 限制。

## 6. 可觀測性
Trace 預設只保存 tool/schema IDs、digest、mode、attempt、validation code 與 timing，不保存敏感 arguments/result。Response 揭露 actual structured mode、validation status 與 tool execution owner。

## 7. 驗收情境
1. **Schema bomb**：深層、循環 reference、巨大 regex/enum 在固定 time/memory cap 內拒絕，不進 engine compile。
2. **明示 fallback**：engine 無 native grammar且 caller 不允許 post-validation時拒絕；允許時揭露 mode與attempt cap。
3. **非執行保證**：model 產生 shell/URL/tool proposal 不會由 OmniLLM 自動執行；只有授權 host 可提交 ToolResult。
4. **工具 reply loss**：同 proposal/idempotency key 重送不重複執行；不同 payload 回 conflict。
5. **Session 不確定結果**：無法證明 host 工具是否完成時，不自動提交相反狀態到 KV，Session 被隔離或要求確認。
6. **隱私**：外部 metrics/diagnostics 不含完整 arguments/result；本機明示匯出才可選擇加入。
