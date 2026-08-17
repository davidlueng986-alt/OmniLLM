---
id: "SEC-SUPPLY"
title: "模型供應鏈與 Catalog 設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "security-architecture"
lastReviewed: "2026-07-31"
---

# 模型供應鏈與 Catalog 設計

## 1. Root bootstrap
App package內嵌完整initial root metadata bytes及其digest，不只保存遠端URL。首次離線啟動可建立信任。Root rotation逐版驗證，禁止跳過中間版本；root recovery有明確manual/update path。

## 2. Metadata roles
Root、timestamp、snapshot、targets與revocation records皆有typed schema、threshold signature、version、expiry與canonical bytes。未知major version fail closed。Catalog state保存最高sequence、trusted clock anchor與source artifact。

## 3. Trusted time
Clock record含trusted source、wall time、bootId、elapsedRealtime anchor、max forward advance與uncertain state。首次完全離線且無可信anchor時，只有APK內嵌不過期root可用；遠端metadata不能因本機任意回撥而延長。Grace policy是版本化狀態機，不以散落常數實作。

## 4. Anti-rollback邊界
可證明的保證限於保存catalog state的安裝生命週期。卸載重裝後若沒有外部可信服務／硬體持久狀態，不能宣稱知道裝置曾見過更高sequence。

## 5. Download manifest
每個artifact列出canonical URL policy、file role、size、sha256、package/revision、license terms與optional mirrors。Redirect每跳重新驗證scheme、host、port、resolved IP；拒絕userinfo、file/content、自動private/loopback/link-local與不允許代理。

## 6. Revocation
Revocation保存authority role、metadata version/sequence、reason、target kind/id、effective interval與canonical record。較舊metadata不能解除較新撤銷。Effective trust重新計算後驅動placement/drain。

## 7. License provenance
License terms保存canonical text bytes、digest、locale、source assertion與version。接受event綁terms與使用者；相同bytes但不同來源條款不自動共用。

## 8. Parser boundary
安全關鍵source assertion使用typed schema，不以自由`statement_json`驅動trust。Parser output有schema version、field limits與canonical signature coverage。
