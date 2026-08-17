---
id: "FEAT-LAN"
title: "LAN 存取與配對"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "feature-team"
lastReviewed: "2026-07-31"
---

# LAN 存取與配對

## 0. 核心價值對應
- **低技術門檻自動架設**：使用者以可視化開關、QR／短碼與明確網路狀態建立安全本地服務，不需手動配置憑證。
- **統一調用**：LAN 仍使用相同 HTTP request、asset、stream、error、scope、scheduler與Governor，只增加網路 identity/transport。
- **可視化監控**：顯示 server identity、介面、clients、scope、流量、active work、epoch、撤銷與異常連線。

## 1. 安全預設與服務生命週期
LAN預設關閉。啟用時使用者選 network/interface/address family；服務只綁核准介面，使用TLS，不提供明文token交換。Network、certificate或binding變化 bump connection epoch，舊pairing不得自動跨epoch沿用。

## 2. Pairing
Canonical `LAN_SERVICE` 狀態：`DISABLED → STARTING → ADVERTISING → PAIRING_AVAILABLE → ACTIVE`，並可進 `ROTATING`、`REVOKING`、`STOPPING`、`ERROR`；pairing challenge 本身使用獨立 `PAIRING_CHALLENGE` machine。Challenge包含server locator、SPKI fingerprint、高熵nonce、expiry、attempt limit、connection epoch與requested scopes，並與TLS channel binding。QR不含長期secret。

## 3. Client identity 與 token
核准後建立 LAN `ClientRegistration` 並換發scoped token。Client保存server identity、registration與token；SPKI/network epoch變更時要求重新確認。Token hash儲存本機，原文只顯示一次；每request/stream綁revocation epoch。

## 4. ACL 與資料最小化
預設只授 `inference.create`、`inference.cancel`、`inference.read-own`。`models.read`、`assets.create`、`assets.read-own`、`assets.delete-own` 或 `metrics.read-summary` 需逐項核准；`metrics.read-detail`、`clients.manage`、`settings.write`、`tokens.manage` 與其他本機管理 scope 預設不對 LAN 提供。Response依scope欄位allowlist，不暴露private path、完整driver dump、其他client活動、token metadata或raw crash detail。

## 5. DoS 與可用性
限制pair attempts、connections、body/header/decompression、assets、queued cost、concurrent operations、output、idle time與bandwidth。LAN不繞過global scheduler；local UI只有明示且有上限的fairness權重。資源壓力可拒絕LAN，但需reason code與Retry-After／狀態。

## 6. UX
顯示可達位址、port、server fingerprint、certificate有效狀態、active clients、scope、最後活動、request數、撤銷進度與停止服務。Client端必須驗證fingerprint／certificate；只設定base URL不構成信任。

## 7. 驗收情境
1. **MITM**：fingerprint/channel binding不符時配對失敗，token不發行；使用者看到server identity差異。
2. **重放／暴力**：challenge過期、已用、epoch不同或attempt超限時拒絕；成功challenge只能消耗一次。
3. **Network/證書變更**：舊connection/token不能在新epoch靜默繼續，需重新確認或明確policy migration。
4. **撤銷**：bump epoch後在deadline內關閉active SSE、移除queue、取消request、關閉owner Session；完成前顯示REVOKING。
5. **權限最小化**：infer-only client不能讀完整models/device/metrics、管理token或執行admin command。
6. **DoS**：大量pair／JSON／asset／output請求在固定資源上被限流，local UI與其他principal仍依公平性規則獲得服務。
