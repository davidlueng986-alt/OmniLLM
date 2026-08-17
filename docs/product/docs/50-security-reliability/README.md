---
id: "NAV-SEC"
title: "安全與可靠性設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "GUIDANCE"
owner: "architecture-governance"
lastReviewed: "2026-07-31"
---

# 安全與可靠性設計

本目錄保存 OmniLLM 建置前產品與架構設計的現行文件。章節號只作閱讀導航；跨文件引用以文件 ID 與規格 ID 為準。

| 文件 ID | 名稱 | 狀態 | 權威 | Owner |
|---|---|---|---|---|
| `SEC-AUTH-NET` | [認證、授權、網路與 Secrets](auth-network-secrets.md) | `BASELINE` | `NORMATIVE` | `security-architecture` |
| `SEC-EXTERNAL-SANDBOX` | [不同 UID 加速沙箱 Companion 設計](external-sandbox-companion.md) | `BASELINE` | `NORMATIVE` | `security-architecture` |
| `REL-RECOVERY` | [故障、復原與冪等設計](failure-recovery-idempotency.md) | `BASELINE` | `NORMATIVE` | `reliability` |
| `SEC-INPUT` | [輸入、下載與資源濫用防護](input-abuse-protection.md) | `BASELINE` | `NORMATIVE` | `security-architecture` |
| `SEC-SUPPLY` | [模型供應鏈與 Catalog 設計](model-supply-chain.md) | `BASELINE` | `NORMATIVE` | `security-architecture` |
| `SEC-PRIVACY` | [隱私、Telemetry 與資料最小化](privacy-telemetry.md) | `BASELINE` | `NORMATIVE` | `privacy-security` |
| `SEC-PROFILE` | [Security Profile：密碼學、Key、Token 與事件處理](security-profile.md) | `BASELINE` | `NORMATIVE` | `security-architecture` |
| `SEC-THREAT` | [威脅模型](threat-model.md) | `BASELINE` | `NORMATIVE` | `security-architecture` |
| `SEC-PLACEMENT` | [信任、證據與 Runtime Placement](trust-runtime-placement.md) | `BASELINE` | `NORMATIVE` | `security-architecture` |

機器可讀單一來源位於 [`../../specs/`](../../specs)；治理規則位於 [`../../governance/`](../../governance)。
