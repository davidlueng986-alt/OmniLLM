---
id: "GOV-AUTHORITY"
title: "規範權威地圖"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "architecture-governance"
lastReviewed: "2026-07-31"
---

# 規範權威地圖

| Concern | Authority |
|---|---|
| 產品使命、核心價值 | `PROD-CHARTER` |
| 能力宇宙、建置資格與 runtime availability | `PROD-CAPABILITY-MODEL`、`PROD-CAPABILITY-ELIGIBILITY`、`specs/capability-catalog.yaml`、`specs/capability-availability-matrix.yaml` |
| 架構原則／不變式 | `ARCH-PRINCIPLES`、`ARCH-INVARIANTS` |
| 程序／UID／信任拓樸 | `ARCH-TRUST-TOPOLOGY` |
| Engine contract | `CORE-ENGINE` |
| Engine qualification status／cell | `ENGINE-QUALIFICATION-STATUS`、`specs/engine-qualification-schema.yaml`、`specs/engine-qualification-status.yaml`、對應 `ENGINE-*` |
| Model lifecycle／install／trust input | `CORE-MODEL` |
| Orchestrator／scheduler | `CORE-ORCHESTRATOR` |
| Session／KV | `CORE-SESSION` |
| ResourceVector／admission | `CORE-RESOURCE`、`specs/canonical-types.yaml` |
| HTTP／AIDL／Admin semantics | `CORE-INTERFACE`、`CORE-CONTRACT-ARTIFACTS`、`specs/openapi/omnillm.openapi.yaml`、`specs/aidl/omnillm-aidl.yaml` |
| Access control／scope | `specs/access-control-catalog.yaml` |
| Canonical error | `specs/error-catalog.yaml` |
| Persistence design | `DATA-OWNERSHIP`、`specs/database/omnillm-schema.sql` |
| 版本與相容演進 | `specs/compatibility-policy.yaml` |
| 設定 key、value source 與 hard constraint | `specs/configuration-catalog.yaml`、`DATA-CONFIG` |
| 保留與刪除政策 | `specs/retention-policy.yaml`、`DATA-STORAGE` |
| Metric 語義與 privacy | `specs/observability-catalog.yaml`、`CORE-OBSERVABILITY`、`SEC-PRIVACY` |
| UX state/error/capability 投影 | `specs/ux-projection-catalog.yaml`、`UX-STATE-CATALOG` |
| 無障礙與 UX 接受標準 | `UX-A11Y-I18N`、`specs/ux-acceptance.yaml` |
| Identity formulas／encoding | `DATA-IDENTITY`、`specs/canonical-types.yaml`、`specs/golden-vectors/canonical-encoding.yaml` |
| State machines | `DATA-STATES`、`specs/state-machines.yaml` |
| Data ownership／durability | `DATA-OWNERSHIP` |
| Measurement | `DATA-MEASUREMENT` |
| Threat／placement／different-UID sandbox | `SEC-THREAT`、`SEC-PLACEMENT`、`SEC-EXTERNAL-SANDBOX`、`specs/security-control-catalog.yaml` |
| Cryptographic／token／key profile | `SEC-PROFILE`、`specs/security-profile.yaml` |
| Catalog／supply chain | `SEC-SUPPLY` |
| Android services／Binder／native／policy | `ANDROID-BASELINE`、`ANDROID-SERVICE`、`ANDROID-BINDER`、`ANDROID-NATIVE`、`ANDROID-DIST`、`specs/platform-policy-register.yaml` |
| AI 生成內容回報 | `FEAT-AI-CONTENT-REPORT`、`CONTENT_REPORT` state machine、`SEC-PRIVACY`、`ANDROID-DIST` |
| Feature behavior | 對應 `FEAT-*` 文件 |
| Feature-capability dependency | `specs/feature-capability-map.yaml` |
| Quality／traceability | `specs/quality-scenarios.yaml`、`specs/traceability-matrix.yaml` |
| 外部 claim 與再查核 | `specs/source-register.yaml`、`SPEC-SOURCES` |
| 跨平台可移植性矩陣 | `specs/platform-portability-matrix.yaml` |
| Engine specifics | 對應 `ENGINE-*` 文件 |
| 未來平台 | `FUTURE-*` 文件 |

## 產品包與稽核包邊界
FSM audit、finding closure 與歷史報告位於獨立附件包，依產品包 digest 提供 evidence；它們不是產品 authority，也不是本包完整性所需檔案。
