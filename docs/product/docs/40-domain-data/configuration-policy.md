---
id: "DATA-CONFIG"
title: "設定與政策模型"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "data-architecture"
lastReviewed: "2026-07-31"
---

# 設定與政策模型

`specs/configuration-catalog.yaml` 是 setting key、type、可寫來源、兩軸 precedence、identity/profile 影響與 restart 行為的機器權威；本文件說明合併語義。

## 1. 設定類型
- **User Preference**：品質／速度偏好、history、LAN、diagnostic detail。
- **Safety Policy**：trust placement、input cap、token scope、revocation、sandbox requirement。
- **Resource PolicyProfile**：device class、memory margin、thermal threshold、queue weight、eviction。
- **Engine Configuration**：只有已宣告 allowlist 欄位，並進 `LoadKey`／`MeasurementProfile`。
- **Derived Suggestion**：系統根據 evidence 產生，保存 reason 與 policy version。

## 2. 兩條互不混淆的軸
### 2.1 值來源順序
`request-explicit > principal-profile > user-preference > model-recommendation > device-policy > product-default > engine-suggestion`。

這只回答「多個合法候選值選哪一個」，不授權 caller 放寬安全或資源限制。每個 setting 另有 `allowedSources`；未列入的來源不得寫入。

### 2.2 Hard constraint 順序
`platform-safety-limit > security-trust-policy > resource-hard-cap > admin-policy`。

Hard constraint 以集合交集生效；較低層只能縮小允許區間，不能放寬較高層。User／request／engine suggestion 都不是 hard constraint authority。

## 3. 決定性合併演算法
1. 依型別、範圍、enum 與 unknown-field policy 驗證候選值；
2. 從允許的 value source 中選最高順位候選；
3. 套用所有 hard constraints；
4. 若 setting 明示允許 clamp，回傳 `effectiveValue`、`selectedSource`、`clampReason` 與 constraint versions；否則以 canonical error 拒絕；
5. 若 effective change 增加資源，必須先 plan／reserve／commit；
6. 保存 effective config、每個來源、policy version、request snapshot 與 reason。

## 4. Public semantics 與本機設定分離
本機設定不得改變公開 HTTP／AIDL 對未知欄位、terminal、error、scope 或 idempotency 的語義。Debug／research 設定只影響明確 extension namespace 或 diagnostic detail。

## 5. 版本與原子更新
`SettingsSnapshot` 含 `resourceVersion`。Patch 指定 `baseVersion`，使用 compare-and-set；跨欄位 invariant 在單一交易驗證。Reply loss 以 `CommandResult` 查詢。

## 6. Secret 分離
設定只保存 token metadata／hash、key alias reference、certificate fingerprint；原始 secret 只由 Secret Broker 管理。Diagnostic export 依 allowlist 遮蔽。

## 7. 驗收
- 相同 source set、policy versions 與 request snapshot 必須產生 byte-equivalent effective config。
- Request explicit 值即使順位最高，也不能越過 security／resource hard cap。
- Clamp 與 reject 行為必須由 setting schema 決定，不能由 adapter 自行選擇。
- 任一資源增加在沒有 reservation 時 fail closed。
