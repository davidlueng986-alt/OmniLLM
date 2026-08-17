---
id: "DATA-OWNERSHIP"
title: "資料所有權、持久化與復原"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "data-architecture"
lastReviewed: "2026-07-31"
---

# 資料所有權、持久化與復原

## 1. 唯一 writer
Runtime control plane是domain DB、model store projection、trust state、request/command/job/commit ledger、Asset與ClientRegistration的唯一權威writer。UI、Gateway、engine worker、isolated parser與external companion只能透過typed command/event更新；不同process不得直接開Room/DataStore作第二個writer。

## 2. 資料分類與持久化
| 類別 | Durable內容 | 非durable／可重建 |
|---|---|---|
| Request／Commit | canonical hash、state、terminal、commit intent/result、output digest/retention policy | live callback、native handle |
| Command／Job | spec、state、attempt、event、checkpoint、result/error | active observer、ephemeral PFD |
| Model／Installation | identity、manifest、assertion、license、state、reference、file projection | parser cache、probe scratch |
| LoadedModel／Session／Allocation | owner、engine/load key、state、allocation、epoch、recovery disposition | native pointer、KV bytes unless engine explicitly snapshots |
| Asset | owner、purpose、declared/actual size、digest、state、TTL、reuse、request references | upload socket、original caller FD |
| ClientRegistration | observed identity evidence、scope、expiry、revocation epoch、state、audit | live Binder connection |
| Measurement | canonical profile、run、metric sketch、environment | transient sampler buffers |
| Trust／Secrets | typed metadata state、token hash、broker metadata | token plaintext、raw secret key |

## 3. Transaction boundaries
- Request claim與canonical hash insert同交易；same key/different hash conflict。
- Commit intent先durable，再送worker；result與target handles/allocation transfer原子記錄或進RECONCILING。
- Job state、attempt、event、checkpoint同交易。
- Asset READY與verified metadata／quarantine promotion同一可reconcile boundary；request claim與asset pin同交易。
- ClientRegistration approval/revocation epoch與audit同交易；revocation完成只在owned active/queued/pooled state已fence後寫入。
- Model atomic promotion由fsync/rename與DB READY marker按journal協定對帳。

## 4. Multi-process 與 worker journal
Worker不持有DB writer。Runtime可給單向pipe/socket寫minimal framed evidence；worker output完全可偽造或中斷，不能直接提升trust或釋放resource。Runtime journal/DB使用CRC、version、sequence、size cap與rotation；unknown frame fail closed。

## 5. Snapshot、Cursor 與 observers
Snapshot由單一dataVersion/high-watermark建立；page cursor綁resource、snapshot version、last sort key、unique ID、expiry與MAC。Event retention gap回CURSOR_GONE；client取得AdminSnapshot再續訂。Observer handle、window與ACK是ephemeral，callback death立即清理。

## 6. Asset recovery
重啟時逐一對帳durable Asset state與quarantine bytes：CREATED/UPLOADING且無可續傳protocol者EXPIRED/REJECTED；VERIFYING可從materialized bytes重算；READY必須驗證digest/path projection；PINNED以request reference重建，request terminal後依reuse policy收斂。Original PFD不被假設可重開。

## 7. ClientRegistration recovery
Binding重建不自動建立registration。Runtime載入ACTIVE/SUSPENDED records與epoch；caller重新bind時以系統UID/user匹配。App update、signing evidence變化、shared UID membership變化或policy version不相容可轉SUSPENDED並要求本機重新核准。

## 8. 備份、還原與刪除
Model bytes、DB、DataStore、trust、journal、token與registration預設排除系統backup。使用者匯出採版本化、可驗證、可選加密bundle；導入時重新驗identity/trust，不把舊UID/token直接復活。Retention與刪除依`DATA-STORAGE`，tombstone防reply loss/cursor誤判。
## 9. Persistence authority 與 table groups
`specs/database/omnillm-schema.sql` 是建置前 persistence design authority，涵蓋：

- Blob／ArtifactPackage／Revision／Installation／Alias／file projection；
- typed source assertion、attestation、license event、template override；
- catalog root／metadata／revocation／trusted clock；
- runtime instance、engine module、phase capability、reservation／allocation／loaded model／Session；
- inference request／attempt／event／terminal／commit／revision lease；
- command／job／attempt／event；
- download attempt／non-overlapping parts、Asset／request pin；
- ClientRegistration／pairing challenge／HTTP token hash／secret-key metadata／settings；
- snapshot checkpoint／admin events；
- compatibility／measurement profile／run／metric sketch；
- diagnostics、security event、AI content report、crash-journal projection 與 deletion tombstone。

DDL 可由 SQLite parser 建立且 foreign-key 結構完整；實作期仍須產生 Room schema export、migration、kill-at-boundary 與 forward/backward compatibility evidence。

## 10. Schema migration authority
`specs/database/migration-policy.yaml` 定義 schema version、min-readable/min-writable、single-writer barrier、transaction/journal、crash recovery、destructive change與 build-time evidence。`schema_metadata`／`migration_history` 是 durable ledger；不能以「App 啟動後表存在」推測 migration 已提交。
## 8. State constraint 與 migration authority
Canonical aggregate 的 `state` 欄位在 DDL 以 `CHECK` 對齊 `specs/state-machines.yaml`；新增 state 必須同時更新 machine、OpenAPI/AIDL projection、DDL/migration 與 fixture，不能只改程式碼。非 FSM 的 operational state 也使用明確 controlled enum，避免自由字串污染。

## 9. Schema migration authority
`specs/database/omnillm-schema.sql` 定義 current schema；`specs/database/migration-policy.yaml` 定義 version compatibility、attempt journal 與 crash reconciliation。`migration_history` 只保存與 schema version 同交易提交的成功 migration，永不以更新 state 表示失敗；`migration_attempts` 是 bootstrap operational ledger，先寫 `STARTED`，再由 committed history／schema metadata 收斂為 `COMMITTED` 或 `FAILED`。任何 STARTED 不能僅因部分 table 存在就推定成功。
