---
id: "CORE-INTERFACE"
title: "統一介面平台設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "core-platform"
lastReviewed: "2026-07-31"
---

# 統一介面平台設計

## 1. Canonical 模型
HTTP、AIDL、Admin 與 UI 共享 canonical：

- `InferenceRequest`
- `EmbeddingRequest`
- `AssetHandle`
- `GenerationEvent`
- `TerminalResult`
- `OmniError`
- `CapabilityDescriptor`
- `RequestState`
- `JobState`
- `ModelDescriptor`

Transport adapter 只做認證、大小限制、編碼、stream projection 與 delivery semantics，不重新路由 engine。

## 2. Request identity
Caller 先建立 `requestId` 與 `idempotencyKey`。服務以 principal + operation + key claim canonical request hash；相同 key 不同 payload 回 `IDEMPOTENCY_CONFLICT`。HTTP／AIDL 都能 `queryRequest(requestId)`。

## 3. HTTP profile
- Loopback 仍需 token；`/health` 可提供最小 unauthenticated liveness，但不得洩漏 model/device detail。
- `/v1/chat/completions`、`/v1/embeddings`、`/v1/models` 與 `/omni/v1/*` 使用明確 schema、status、error、pagination 與 idempotency。
- Context overflow 固定使用 `413 CONTEXT_LIMIT_EXCEEDED`。
- Unknown parameter policy 固定於 API profile；裝置設定 `strictParams` 不可改 public semantics。
- ignored parameter header 有 count／byte cap；超出以 digest/diagnostic body 表達。

## 4. SSE
開流前錯誤用 HTTP response；一旦第一個 SSE event committed，後續錯誤用 terminal event。Terminal reason 不依賴結束後才補的普通 header。SSE 不宣稱 socket write 等於 app delivery；斷線後 Session 預設不可自動續用。

## 5. AIDL runtime
`IOmniRuntime.start(request, callback)` 回 `IStreamSession` 或 caller-provided requestId 的 accepted result。StreamSession 提供 `ack(lastDataSeq, epoch)`、`grantCredit(bytes/events)`、`cancel(reason)`、`query()`、`closeObserver()`。所有 large list 分頁；asset/model 使用 PFD/handle。

Binder transaction 可能在 service 收到前因 buffer 限制失敗，因此區分：SDK preflight 413、service semantic 413、transport failure。不能承諾所有 oversize call 都回自訂 error。

## 6. Admin commands
所有 mutation 回 `CommandResult(commandId, state, resourceVersion, error)`，或 start/query command；不得使用 void 並同時宣稱 reply loss 可恢復。Job observer 有 subscription handle、ACK/window、cancel、linkToDeath 清理與 cursor resume。

## 7. Principal 與 scope
- AIDL：calling UID、Android user、配對／allowlist evidence。
- HTTP：token subject、scope、client id、revocation epoch。
- LAN：TLS server identity + paired token + connection epoch。

Canonical scope 由 `specs/access-control-catalog.yaml` 定義，例如 `inference.create`、`inference.read-own`、`assets.create`、`assets.read-own`、`models.read`、`models.manage`、`metrics.read-summary`、`metrics.read-detail`、`jobs.read-own`、`jobs.manage`、`clients.manage`、`settings.read`、`settings.write` 與 `diagnostics.export`。不得使用 `infer` 或 `admin` 之類別名作 wire authority；Response schema 依 scope 使用欄位 allowlist 與 redaction。

## 8. Binary asset
Multimodal 與 import 使用 AssetHandle/PFD broker：owner、MIME hint、declared size、digest、TTL、single-use／reuse policy。Service dup FD、fstat、限制 bytes/time，必要時 materialize quarantine；MIME 由 sniff + allowlist 驗證。

## 9. HTTP AssetHandle 協定
HTTP 不傳遞 PFD，也不接受任意本機 path。二進位輸入採 owner-bound `AssetHandle`：

1. `POST /omni/v1/assets`：建立 handle。Request 包含 purpose、declared bytes、content type hint、expected digest（可選）、reuse policy 與請求 TTL；Response 回 `assetId`、最大 bytes、實際 expiry 與 upload method。
2. `PUT /omni/v1/assets/{assetId}/content`：以 bounded streaming 上傳；要求 `Content-Length`，預設禁止 `Content-Encoding`，每個 handle 只允許一個 active uploader。斷線後是否可續傳由 handle 明示，不能猜測。
3. `POST /omni/v1/assets/{assetId}/commit`：服務完成 materialize、size、digest、MIME sniff、purpose allowlist 及 quarantine 檢查後，原子轉為 `READY`。驗證失敗轉 `REJECTED`，不得被 inference 引用。
4. `GET /omni/v1/assets/{assetId}`：只回狀態、大小、digest、expiry、purpose 與可安全揭露的錯誤，不回檔案路徑。
5. `DELETE /omni/v1/assets/{assetId}`：owner 或 admin 可刪除未 pinned handle；已被已接受 request pin 住時回 state conflict，待 request 結束再依 policy 清除。

TTL 自建立時計算；client 不得藉重複 query 無限延長。接受 inference 時，Runtime 以同一 DB transaction 檢查 owner、`READY`、未過期與 reuse policy，並建立 request reference。Single-use handle 只能被一個 request claim。Process death 後由 Asset state machine 和 quarantine reconciler 收斂。

AIDL 路徑由 SDK 將 PFD 交給 asset broker；服務 `dup` 後立即明確接管／關閉，仍 materialize 成同一 `AssetHandle`，因此 HTTP 與 AIDL 之後的驗證、TTL、owner 與 request pin 語義完全相同。


## 10. 相容與版本演進
`specs/compatibility-policy.yaml` 定義 canonical schema、HTTP profile、AIDL protocol、state/capability/error 的 change class、版本協商、unknown-field／unknown-enum行為與 deprecation。任何 security boundary、identity formula、terminal semantics 或 required field 變更都是 major change，不能以文件小修靜默發布。
## 11. Formal transport surface
`specs/openapi/omnillm.openapi.yaml` 的 public／admin surface 至少包含：minimal health、capability snapshot、model listing、chat、embedding、request query/cancel、Asset lifecycle、typed Command execute/query、typed Job start/query/cancel、AdminSnapshot／admin event stream、summary/detail metrics、diagnostic export，以及 AI content report create／query／cancel／discard。

`specs/aidl/omnillm-aidl.yaml` 提供同一語義的 runtime、stream session、admin、job/command query 與 credit/ACK 型 observer。Embedding 等非 stream operation 仍以 caller-generated RequestId 透過 `queryRequest`／`cancelRequest` 管理；不得因沒有 `IStreamSession` 而失去取消路徑。

## 12. Canonical inference payload
`InferenceRequest` 以 `RoutingPolicy`、`SessionPolicy`、`ResponseFormat`、`ToolDefinition` 與 typed `MessageContentPart` 組成。Image／Audio 必須以 `IMAGE_ASSET`／`AUDIO_ASSET` content part 指向 READY AssetHandle，不能只附一個與 message 無關的全域 asset list。Structured output 與 Tool Calling 的 schema digest、允許 mode、repair cap、proposal/result identity 均進 canonical request hash。

`GenerationEvent` 為 metadata／delta／usage／warning／tool proposal／terminal 的 sealed union；SSE 只是它的文字 framing，AIDL `StreamBatch` 則直接承載同一事件型別。Terminal 必須揭露 actual routing、runtime placement、fallback、usage、structured mode、tool proposal、Session checkpoint 與 Session disposition。

AI content report 使用 create／query／cancel／discard；`SUBMITTING` 的取消不被假設成功，reply loss 以 reportId／idempotency key 進 reconciliation。
## 13. Admin、Job、Settings 與 metric formal values
Admin event stream 承載 typed `AdminEvent`，不是任意 object；`AdminSnapshot` 使用 `ClientRegistrationSummary`、`SettingsSnapshot`、`ModelPage` 與 `JobPage`。`JobRecord.progress` 使用 bounded `JobProgress`，Benchmark 使用完整 `MeasurementProfile`；settings patch、LAN policy、metric series 與 command outcome 皆有明確 schema。任何 adapter 不得以自由 JSON 取代上述 core values。
