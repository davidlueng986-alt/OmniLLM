---
id: "SEC-EXTERNAL-SANDBOX"
title: "不同 UID 加速沙箱 Companion 設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "security-architecture"
lastReviewed: "2026-07-31"
---

# 不同 UID 加速沙箱 Companion 設計

## 1. 目的與適用條件
當模型或compiled artifact不具足夠來源信任、但使用者仍明確要求GPU/NPU加速時，同UID worker無法保護App private data、DB、token或Keystore能力。OmniLLM以**不同package、不同Linux UID**的optional companion承載此路徑。Companion不可用或無法提供所需backend邊界時，組合為`TRUST_PLACEMENT_REQUIRED`，而不是退回same-UID「高效能沙箱」。

## 2. Package 與權限
- 主App與Companion使用不同applicationId/UID，不使用sharedUserId或shared storage。
- Companion不要求INTERNET、contacts、media、broad storage、backup或主App Keystore alias；只保留執行所需最低foreground/service與accelerator能力。
- Binding使用explicit component + same-signer signature permission + protocol handshake；signature permission保護誰可控制Companion，但不把Companion內的untrusted native code變成可信。
- Companion private data視為可被該次model/native code完全控制；不得保存主App secret、catalog root、token、license identity或長期模型庫。

## 3. Handshake 與工作身分
主App建立`SandboxExecutionTicket`：runtime instance/epoch、operation/commit ID、engine build、model content IDs、backend、ResourceVector/OperatingConstraint、deadline、nonce與protocol version。Companion回報package signer digest、version、process instance與supported protocol。任一不匹配fail closed；舊runtime epoch、重放ticket或不同artifact被拒。

## 4. 模型與資料傳遞
主App在每次工作前對實際FD fresh verify，將read-only／sealed PFD、bounded shared memory或pipe傳入；不傳path、DB handle、directory FD或general file capability。Companion `dup`所需FD並依ownership表關閉。Vision/audio/tool資料以request-scoped Asset stream傳送，完成或cancel後失效。

## 5. IPC 與輸出
控制面只提供plan-qualified load/start/cancel/query/close，不提供任意文件、network、secret或admin RPC。Event採bounded batch/credit或pipe；每個message綁operation/stream epoch與sequence。大tensor／cache不經Binder。Companion self-report只供observability，不能把Governor charge降到pre-admission envelope以下。

## 6. 生命週期與故障
主App持有supervisor binder；死亡時Companion停止接受新工作、fence輸出、清request-scoped資料並自我終止。Companion crash／hang使其LoadedModel與Session全部POISONED/LOST；主App依durablerequest/commit ledger決定query結果。Hard timeout可kill整個Companion process，但不宣稱能防GPU driver/kernel或全系統DoS。

## 7. 更新、版本與回滾
Companion與主App以protocol major/minor、engine artifact digest及signer handshake。更新前drain，更新後cold start與qualification；舊Companion不接受新major ticket。回滾只使用仍被主Apppolicy允許且簽章有效的版本。Companion缺失、被停用或signer異常時，不自動安裝/啟用未授權package。

## 8. 撤銷與風險模式
RiskAck綁model/artifact/principal/warning version/expiry，不改TrustClass。關閉風險模式先bumprevocation epoch、拒新、cancel/drain、killCompanion，再重新驗主Appmodel store與輪替可能受影響的外部token；UI明示過去資料外洩不可逆。Companion的任意檔案寫入不得影響主Apptrust、catalog、token或privileged load。

## 9. 殘餘風險
不同UID降低App資料與secret暴露面，但不能消除vendor driver、kernel、shared hardware、thermal、battery或system memory壓力風險。這些風險在`GOV-RISKS`與device/backendqualification中明示，不以「sandbox」作絕對安全宣稱。

## 10. 設計驗收
- 模擬Companion任意code執行，不能讀寫主Appprivate files/DB/token/Keystorebroker或建立主App授權操作。
- 重放／跨artifact／舊epoch ticket全部拒絕；主Appcrash後舊Companion不能繼續提供output。
- PFD／shared memory ownership在success、cancel、crash、timeout均無leak，且只包含request所需bytes。
- Companion report偽造低值時，Governor charge仍不低於qualified envelope。
- Companion更新／缺失／signer不符時，產品明確回unsupported或CPU alternative，不回same-UID untrusted acceleration。
