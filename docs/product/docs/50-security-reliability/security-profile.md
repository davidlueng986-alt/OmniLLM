---
id: "SEC-PROFILE"
title: "Security Profile：密碼學、Key、Token 與事件處理"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "security-architecture"
lastReviewed: "2026-07-31"
---

# Security Profile：密碼學、Key、Token 與事件處理

## 1. 原則
使用平台標準、可審查的 primitive；禁止自行設計 encryption／signature。所有 algorithm、key purpose、format、rotation 與失效條件進 profile version；未知 major profile fail closed。

## 2. Transport
- LAN／reporting endpoint 使用 TLS 1.3；必要相容時最低 TLS 1.2 且只允許平台支援的 AEAD suite，禁止明文降級與 user-installed CA 靜默信任。
- Server identity 綁 SPKI SHA-256 fingerprint；rotation 必須有 overlap／user re-confirm policy與 connection epoch。
- Loopback HTTP 仍使用 scoped token；CORS／Host 不是認證。

## 3. Token 與 pairing
- Bearer token：256-bit CSPRNG，base64url 無 padding；原文只顯示一次。
- DB 不存原文，存由 Secret Broker key 計算的 HMAC-SHA-256 verifier、token ID、scope、expiry、revocation epoch；constant-time compare。
- Pairing challenge：至少 128-bit CSPRNG、單次、預設 5 分鐘 TTL、最多 5 次失敗嘗試，綁 observed principal、requested scopes、TLS SPKI 與 connection epoch。
- Risk acknowledgement：綁 principal、revision／artifact、warning version、nonce、issued/expires、one-shot consumption；不能跨模型重放。

## 4. Catalog／artifact signature
Catalog root 與 metadata 使用 profile 允許的 Ed25519 或 ECDSA P-256 signature，algorithm/key ID 在 root metadata 釘選；threshold 與 role separation 由 root 定義。初始 root bytes 隨 App 提供，rotation 逐版驗證；未知 algorithm 或倒退 sequence fail closed。

## 5. Key lifecycle
Secret Broker 管理 TLS identity、token-verifier key、report queue encryption key及 catalog trust anchors。每個 key 有 purpose、creation、active/retired/revoked、rotation reason與可用 caller policy。Risky worker／external companion 無法直接列舉或使用 general alias。

## 6. Compromise 與 recovery
偵測 token／TLS／catalog key compromise時：durable bump epoch → 拒新 → drain active work → rotate key/token → invalidate cached trust／pairing →記錄 redacted security event →要求使用者重新確認。不能用「關閉風險模式」宣稱恢復過去機密性。

## 7. Evidence 與測試
每項 control 需 owner、negative test、race/fault test、artifact、expiry及 reopen trigger。安全測試只能宣稱機制可提供的保證；例如 isolated worker self-report 不等於硬 RSS 上限。
