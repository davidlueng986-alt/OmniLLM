---
id: "FEAT-PLAYGROUND"
title: "本機 Playground：Chat、Embeddings 與多模態"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "feature-team"
lastReviewed: "2026-07-31"
---

# 本機 Playground：Chat、Embeddings 與多模態

## 0. 核心價值對應
- **低技術門檻自動架設**：以單一工作區讓使用者選模型、輸入內容、取得建議設定並直接看懂錯誤，不需認識底層 runtime。
- **統一調用**：Chat、Embeddings、Vision／Audio、Structured Output 與 Tools 都使用同一 capability/query、request identity、cancel 與 terminal 模型。
- **可視化監控**：每次操作同步顯示 queue、load、TTFT、tokens、資源、actual engine/backend、Session 與降級狀態。

## 1. 工作區與能力
頁籤為 Chat、Embeddings、Vision／Audio、Structured Output／Tools。入口固定可見，但操作狀態由選定 model/engine capability 決定；UNSUPPORTED、UNKNOWN、CONDITIONAL 與 TEMPORARILY_UNAVAILABLE 使用不同說明。

## 2. Chat
支援 messages、sampling、max output、stop、seed、structured/tool extension 與 Session policy。第一個 request 使用 `SourceSessionRef.None`。延續只在 owner、revision、load key、tokenizer/template epoch、engine build 與 session epoch 一致時發生。取消依序顯示 requested、acknowledged、execution stopped、terminal。

## 3. Embeddings
支援單筆／batch text，顯示 dimension、normalization、actual revision 與 output encoding。受 item count、input tokens、output bytes 與 resource cap；不依賴 generation Session。若 adapter 需要 context object，該 allocation 不暴露為 conversation Session。

## 4. Multimodal
UI 先建立 `AssetHandle`，顯示 purpose、MIME hint、actual sniff result、size、digest、TTL、state 與 preprocessing。只有 READY 且 owner-matched asset 可被 request claim；single-use asset 不可重複使用。大型 bytes 不直接進 JSON／Binder。

## 5. Structured Output 與 Tools
Schema 經 depth、node、string、regex、enum 與 total bytes cap。Capability 揭露 native constrained decoding、post-validation 或 unsupported；fallback 必須 caller 明示。Tool call 只產生 typed proposal，OmniLLM 不直接執行 shell、檔案、網路或 host command；執行權與資料授權在 host client。

## 6. History 與 Session
Conversation text 與 native KV 是兩個不同資料物件。History 預設由使用者控制；不保存時只保留 policy TTL 內的 runtime state。SSE 斷線不建立 delivered checkpoint；本機 AIDL UI 只有 application ACK 後才可把 assistant prefix視為已交付。不可證明的 Session 被 poison／關閉。

## 7. 驗收情境
1. **首次 Chat**：零 Session 可完成 plan/reserve/commit/start；拒絕前無 KV mutation，reply loss 可用 requestId 查詢。
2. **Session ownership**：另一 principal、revision、template epoch 或 engine build 不能重用既有 Session；不允許時建立新 Session 並揭露。
3. **Embedding batch**：超出 item/token/output/resource cap 時在高成本執行前拒絕，且不建立 generation Session。
4. **Asset lifecycle**：過期、未 READY、owner 不符、digest 失敗或已消耗的 asset 都被拒；request pin 期間 TTL 不會刪除使用中的 bytes。
5. **Structured fallback**：engine 無 native grammar 時，未授權 fallback 必須拒絕；授權後 response 標記 actual mode與 validation attempts。
6. **斷線與取消**：任意 event 邊界斷線／cancel 後，UI 可查唯一 terminal，且不可從未確認的 hidden KV 自動延續。
