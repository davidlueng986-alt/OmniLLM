---
id: "SEC-PLACEMENT"
title: "信任、證據與 Runtime Placement"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "security-architecture"
lastReviewed: "2026-07-31"
---

# 信任、證據與 Runtime Placement

## 1. 五個獨立維度
1. **Authenticity**：來源、簽章、digest與revision pin。
2. **License**：條款、接受／撤回與來源scope。
3. **Compatibility**：能否在指定組合運作。
4. **Performance**：在完整profile下的結果。
5. **Placement**：可在哪個UID/process執行。

這些維度分開計算與顯示。Compatibility success不能提升Authenticity；Performance優勢不能降低Placement要求。

## 2. Placement classes
| Class | 條件 | 執行位置 |
|---|---|---|
| `PRIVILEGED_TRUSTED` | engine code與model artifact均有可驗證trust，必要phase qualified | `:runtime`或受控same-UID trusted worker |
| `CRASH_CONTAINED_TRUSTED` | 工件可信但engine穩定性不足 | same-UID worker；只宣稱crash containment |
| `ISOLATED_CPU_UNTRUSTED` | model來源不可信但CPU engine支援PFD | isolated UID CPU worker |
| `EXTERNAL_UID_ACCELERATED` | model不可信且需GPU/NPU；companion sandbox可用 | different package/UID |
| `TRUST_PLACEMENT_REQUIRED` | 無法提供需要的邊界或phase qualification未知 | 不執行 |

## 3. Effective trust
Effective trust由版本化函式計算active source assertions、revision relation、installation attestation、root/metadata sequence、expiry、revocation、license與local integrity。DB以composite relation防止installation借用其他revision的assertion。

## 4. Privileged load gate
每次load前：

- 對實際FD重算Blob／package identity；
- 驗證manifest signature/root chain與revocation；
- 驗證installation、template/tokenizer epoch與engine build；
- 聚合全lifecycle phase qualification；
- 決定placement與ResourceVector。

舊READY flag、dry-load或DB cache不能單獨通過。

## 5. Risk acknowledgement
只在使用者明確選擇風險模式時發行一次性signed RiskAck，綁revision、artifact、principal、user、warning version、nonce與expiry。RiskAck不改變trust class；它只記錄使用者對明示殘餘風險的同意。

## 6. 撤銷
Trust下降會bumprevocation epoch，阻止新load/request，drain active model/session，必要時kill worker與輪替token／integrity scan。若仍有另一條active assertion可維持相同trust，則不過度停用；函式必須對所有組合產生唯一結果。


## Security Profile authority
密碼學、token、pairing、key lifecycle 與 compromise response 由 `SEC-PROFILE`／`specs/security-profile.yaml` 定義；本文件不得建立不同 primitive 或數值。
