---
id: "ARCH-PRINCIPLES"
title: "架構原則"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "architecture"
lastReviewed: "2026-07-31"
---

# 架構原則

## 1. 以語義而非 module 為中心
Engine、Model、Request、Session、Resource、Trust 與 Observation 是跨平台核心；Android Service、AIDL、Ktor、Room、Metal 或 Windows ML 都是 adapter。產品功能不能直接綁某引擎物件或 Android framework type。

## 2. 規劃、准入、提交、執行分離
任何可能載入模型、配置大記憶體、修改 KV、建立 Session 或啟動 vendor runtime 的操作依序為：

```text
plan (pure/bounded)
→ reserve (Governor)
→ commit (idempotent/queryable)
→ execute (cancel/deadline/health)
→ account/reconcile
```

Plan 不得修改 domain state；分析 scratch 由有界 pool／AnalysisLease 負責。Commit 後若不能 rollback，必須有 poison／destroy 與可對帳結果，不能要求物理 byte-for-byte 回復。

## 3. 能力必須在操作前可用
用於 placement、admission 或 API preflight 的 capability 不能等操作完成後才取得。Phase qualification 由 engine build、backend、device/driver、model/workload envelope 與 evidence 組成；未知即 fail closed 或放入可 kill worker。

## 4. 身分與所有權先於物件參考
跨 process 或持久化邊界只傳 opaque ID／descriptor，不傳行為介面或 native pointer。每個 handle 綁 principal、Android user、bootId、runtimeEpoch、resource epoch 與 expiry。

## 5. 單一權威寫入
Database、DataStore、model store、token／catalog state 的唯一 privileged writer 是 runtime control plane。不同 UID worker 不取得可寫 private path；同 UID worker 的「不要寫」只能是程式約定，不能算安全控制。

## 6. 信任、相容性與性能分離
- Trust：工件來自何處、簽章／digest／revocation 是否成立。
- Compatibility：指定組合是否能執行。
- Performance：在完整 profile 下速度與資源如何。

任何維度都不自動提升另一維度。

## 7. 故障是正常狀態
Binder reply loss、client disconnect、worker death、runtime restart、partial download、provider revoke、thermal throttling 都是設計輸入。每個有副作用 command 必須具 client-generated identity、idempotent claim、query/reconcile 與 terminal record。

## 8. 大資料不經小 transport
Binder buffer 為 process 共享；模型、asset、長列表與大型輸出以 FD、page、cursor、chunk 或 pull stream 傳輸。Service 只對已抵達的 request 回 semantic error；在 transport 前失敗的情況由 SDK preflight 與明確 transport failure 表達。

## 9. 可觀測值不得偽裝成真實值
Reservation envelope、worker self-report、PSS、RSS、driver estimate 與 application ACK 代表不同事實。資料必須附來源、時間、置信度與適用 envelope。

## 10. 擴充以 capability 與 adapter 完成
新功能或引擎先加入 feature／engine design pack，再映射現有 canonical types。只有改變跨功能不變式時才修改核心文件並建立 ADR。
