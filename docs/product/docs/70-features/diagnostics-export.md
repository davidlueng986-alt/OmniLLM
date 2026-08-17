---
id: "FEAT-DIAGNOSTICS"
title: "診斷與安全匯出"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "feature-team"
lastReviewed: "2026-07-31"
---

# 診斷與安全匯出

## 0. 核心價值對應
- **低技術門檻自動架設**：把複雜故障轉成可分享、可復現且具修復建議的診斷結果。
- **統一調用**：診斷以 canonical request/state/error/capability/resource IDs 組織，而非只輸出某引擎 raw log。
- **可視化監控**：保存當下 snapshot、事件因果、證據來源與不確定性，讓 Dashboard 與支援人員看到同一事實。

## 1. 診斷流程
使用者從錯誤、request、job、model、engine 或 runtime health 建立 Diagnostic Job。畫面先顯示將收集的類別、敏感性、預估大小、保留期限與加密選項；開始後可取消、query、process-death 恢復。

## 2. Bundle 內容
Manifest、runtime/platform版本、redacted configuration、capability snapshot、model/engine IDs、request/job state、resource snapshot、selected events/traces、error chain、source/evidence labels、reproduction hints 與檔案 digests。預設不含 prompt/output、token secret/hash、license acceptance identity、private path 或 unrestricted native log。

## 3. Reason model
輸出穩定 reason code、observed facts、inferences、confidence、excluded alternatives 與 suggested safe actions。推論不得寫成已測量事實；worker self-report、estimated envelope 與 platform observation分開。

## 4. 完整性與分享
Runtime 建立 content-addressed bundle files與 signed/hashed manifest；可使用使用者密碼／平台分享前加密。Bundle 具 random ID、owner、state、expiry 與 deletion command。分享後資料離開 OmniLLM 控制，UI 明示不可撤回既有副本。

## 5. Redaction
欄位 allowlist 先於黑名單。每個 exporter schema 有版本；未知欄位預設排除。Raw native log 先結構化、長度限制與 secret/path pattern redaction；無法安全 redaction 的資料不匯出。使用者可預覽摘要與逐類別排除。

## 6. 驗收情境
1. **預設隱私**：一般 bundle 不含 prompt/output、bearer token、token hash、raw filesystem path 或其他 principal 的可識別活動。
2. **完整性**：修改任一 bundle file 後 manifest 驗證失敗；離線仍可判斷 bundle schema、來源版本與 digest。
3. **取消／重啟**：在收集或壓縮任一階段 kill/restart，只留下可恢復 Job 或可清理 temporary data，不把 partial bundle標 READY。
4. **不確定性**：同一欄位的 measured、estimated、reported、last sampled 與 unknown 在輸出中不可混用。
5. **到期清理**：expiry 或 delete 後 bundle 與 key material依 state machine 清除；active share stream/reference 時先 drain。
6. **使用者控制**：匯出前可看到類別、敏感性與大小；分享後 UI 不宣稱能收回第三方副本。
