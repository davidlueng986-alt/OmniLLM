---
id: "SEC-AUTH-NET"
title: "認證、授權、網路與 Secrets"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "security-architecture"
lastReviewed: "2026-07-31"
---

# 認證、授權、網路與 Secrets

## 1. AIDL principal
對外 Runtime AIDL principal 由 observed calling UID、Android user/profile、binding service policy與 **ACTIVE ClientRegistration** 組成；缺少或過期 registration 只能取得 bounded pairing challenge／`PAIRING_REQUIRED`。App 內部 UI 使用 `LOCAL_UI` principal，不藉「同 UID 所以免配對」混入外部 AIDL 規則。Caller 自報 package 只作 display evidence；shared UID 時不能提供不可偽造 per-package identity。Admin binder 不 export，第三方只能取得 Runtime binding。

## 2. HTTP token
Token依 `SEC-PROFILE` 使用 256-bit CSPRNG secret，DB只存 HMAC verifier、subject、scope、issued/expiry、revoked、revocation epoch與client metadata。原文只在建立時顯示一次。每request／stream綁epoch；撤銷在policy deadline內關閉active connection、清queue與Session。

## 3. Browser integration
不鼓勵把長期bearer放localStorage。Browser client使用短期origin-bound token與使用者配對／PKCE式exchange；CSP、origin allowlist與token TTL是輔助，不能把CORS/Host當auth。

## 4. LAN pairing
- TLS key由Secret Broker持有；UI顯示SPKI fingerprint。
- Pairing challenge 至少 128-bit entropy、預設 5 分鐘 TTL、最多 5 次失敗、one-time，綁TLS channel、connection epoch與principal。
- 配對成功換發scoped token；QR只含server locator、fingerprint與一次性challenge，不含長期secret。
- Network/certificate epoch改變時client重新確認。

## 5. Secret Broker
Worker不直接使用general Keystore alias。Broker operation有purpose、principal、resource、expiry、rate limit與audit；只回最小結果。Risky worker或companion不能列舉alias或取得token原文。

## 6. Rate limit與quota
Principal有request rate、concurrent operation、queued cost、token/output、asset bytes與download quota。Rate limit與scheduler fairness協作，但429原因區分quota、queue saturation、resource unavailable與policy block。

## 7. Sensitive response allowlist
Device、metrics、admin、diagnostic等response按scope列出允許欄位。Raw path、full driver dump、model source URL、token metadata與crash details只在本機使用者明確操作後提供。


## 8. Profile authority
演算法、key purpose、token encoding、TTL、rotation 與 incident order 由 `SEC-PROFILE`／`specs/security-profile.yaml` 定義；本文件不得另建不同數值。
