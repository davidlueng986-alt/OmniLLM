---
id: "SEC-THREAT"
title: "威脅模型"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "security-architecture"
lastReviewed: "2026-07-31"
---

# 威脅模型

## 1. 受保護資產
- Prompt、output、conversation state、embedding與multimodal assets。
- 模型庫、catalog root、source assertion、license與compatibility evidence。
- Client token、LAN private key、Keystore-backed broker能力。
- Database integrity、request/job/command ledger、resource accounting。
- 裝置與driver細節、diagnostic與measurement資料。

## 2. 攻擊者
- 惡意或被入侵的本機App／HTTP client／LAN client。
- 惡意網站與browser script。
- 惡意模型、模型host、SAF provider或catalog mirror。
- 含漏洞或惡意的native engine／vendor runtime。
- 被竄改的download、redirect、DNS與proxy。
- 無意但高負載的合法使用者造成resource exhaustion。

## 3. 主要入口
HTTP、SSE、AIDL、Admin callback、LAN pairing、URL download、SAF/PFD、模型parser、native backend、engine extension、diagnostic export與DFM/module loading。

## 4. 威脅與控制
| 威脅 | 主要控制 | 殘餘風險 |
|---|---|---|
| 本機未授權推理 | token/AIDL principal、scope、rate limit、session owner | 同UID/sharedUID情境需保守處理 |
| 惡意模型parser RCE | isolated UID、read-only FD、bounded typed output | kernel/side-channel/DoS |
| 不受信任加速native RCE | different package/UID companion、secret broker、fresh verify | driver/kernel/DoS |
| 模型替換 | content identity、signed manifest、privileged load revalidation | root compromise |
| SSRF／DNS rebinding | HTTPS allowlist、IP policy、redirect revalidation、network cap | approved host compromise |
| JSON/PFD DoS | compressed/uncompressed cap、depth/node/time、FD size/time | platform resource contention |
| Reply loss重複操作 | client IDs、durable command/commit ledger、query/reconcile | irrecoverable external side effect需uncertain outcome |
| Session跨租戶 | owner partition、revocation epoch、ACK checkpoint、poison | explicit sharing需額外ACL |
| Resource exhaustion | ResourceVector、quota、envelope floor、kill/fail-safe | 惡意native可超envelope |
| 診斷洩漏 | field allowlist、redaction、user-triggered export | 使用者主動分享後離開控制 |

## 5. 非安全保證
OmniLLM不保證模型內容無惡意輸出、所有native/parser無memory-safety bug、isolated process無side-channel、OEM SELinux一定拒絕所有device node，或worker self-report可形成硬RSS上限。

## 6. Threat review觸發
新增network exposure、engine/backend、model parser、different UID broker、browser integration、secret用途、platform adapter或canonical identity版本時重新審閱。


## Security Profile authority
密碼學、token、pairing、key lifecycle 與 compromise response 由 `SEC-PROFILE`／`specs/security-profile.yaml` 定義；本文件不得建立不同 primitive 或數值。
