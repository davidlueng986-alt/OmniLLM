---
id: "FEAT-SERVER"
title: "Developer Server 與 SDK 體驗"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "feature-team"
lastReviewed: "2026-07-31"
---

# Developer Server 與 SDK 體驗

## 0. 核心價值對應
- **低技術門檻自動架設**：自動完成 loopback server 啟動檢查、client 建立、最小 scope、範例與 smoke test，不要求使用者理解 engine adapter。
- **統一調用**：HTTP 與 AIDL 投影同一 request、capability、event、error、cancel、query 與 asset 語義。
- **可視化監控**：每個 client 的活動、quota、Session、實際 engine/backend、降級、錯誤與撤銷狀態都可在 Dashboard 檢視。

## 1. 目的
讓開發者以 loopback HTTP 或 AIDL 調用 OmniLLM，取得一致的 capability、request、stream、cancel、query 與 diagnostics，而不接觸底層 engine 差異。

## 2. HTTP Client onboarding
使用者建立具名 client、選 scope 與 expiry；token 原文只顯示一次。畫面提供 health、capability、chat、embedding、cancel、query、asset upload 與 error 範例。Smoke test 使用與正式 request 相同 scheduler/Governor，不走 Admin 捷徑。

## 3. 第三方 AIDL 配對與註冊
`RuntimeBindingService` 被 bind 只代表 transport 可達，不代表已授權。首次 AIDL caller 的完整流程：

1. Service 觀測 `callingUid`、Android user/profile、可取得的 package/signing evidence，建立短期 `PairingChallenge`；caller 自報 package 僅作輔助顯示。
2. App 內顯示 caller evidence、要求的 scopes、有效期及 shared-UID 警告，由使用者明確核准或拒絕。
3. 核准後建立 `ClientRegistration`，綁 observed UID、Android user、granted scopes、registration ID 與 revocation epoch；SDK 取得的是 opaque registration handle，不是 admin binder。
4. 每次 AIDL 呼叫重新讀取系統提供的 UID 並對照 registration。若一個 UID 對應多 package，預設把整個 shared UID 視為同一 principal；不能用 caller 自報 package 偽造 per-package 隔離。
5. Registration 可在 UI 被 suspend、縮 scope、到期或 revoke。撤銷先 durable bump epoch，再拒新、移除 queue、cancel active、關閉 owner Session，最後完成 revoke evidence。
6. App upgrade、signing evidence 變化、Android user 變化或長期未使用，可要求重新核准；managed deployment 可另用 signature permission，但不取代 canonical registration/audit model。

Pairing challenge 具高熵 nonce、短 TTL、單次消耗、attempt limit，並綁 UID/user/requested scopes；challenge 本身不放在可被其他 App 攔截的 implicit intent。

## 4. HTTP profile
列出明確支援欄位與差異；Unknown parameter policy 固定。`/v1/chat/completions` 與 `/v1/embeddings` 投影 canonical semantics，`/omni/v1` 提供 capability、assets、jobs、metrics、commands 與 diagnostics。Large response 分頁／stream。

## 5. AIDL SDK
SDK 完成 parcel size preflight、client-generated IDs、binding lifecycle、registration handle、IStreamSession credit/ACK、transport failure 分類與 PFD ownership。Raw AIDL 仍可用，但不承諾 service 能把 pre-transaction oversize 轉成 custom error。

## 6. Client 管理
顯示 last seen、observed UID/package evidence、scope、active/queued request、Session 數、quota、registration state 與 revocation progress。每個工具使用獨立 client，不共用 admin token／registration。

## 7. 相容性
API version 與 capability version 分離。Client 先 query model/capability，不以 HTTP 200 推測所有 field 受支援。Response 揭露 actual model revision、engine、backend、trust class 與 degradation。

## 8. 驗收情境
- 未配對第三方 UID 只能取得 pairing-required 結果，不能推理、列模型、讀 metrics 或取得 Admin binder。
- 核准後的 caller 只能使用已授 scope；shared UID 行為與 UI 警告一致。
- 撤銷、reply loss、caller process death、Runtime restart 與 registration expiry 都收斂到唯一可查狀態。
- HTTP/AIDL 的相同 request fixture 具有相同 model、engine、backend、terminal、error 與 asset 語義；只有 delivery transport 不同。
- UI、SDK 與 Dashboard 對 client principal、實際能力、降級與風險的描述一致。
