---
id: "ARCH-CONTEXT"
title: "系統情境與外部邊界"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "architecture"
lastReviewed: "2026-07-31"
---

# 系統情境與外部邊界

## 1. 系統定位
OmniLLM 位於使用者、第三方應用、模型來源與多個 native inference runtime 之間。它不是單一模型播放器，而是負責把「取得模型、證明身分、判斷相容、選擇引擎、管理資源、執行推理、暴露介面、監控與恢復」整合成一個本地平台。

## 2. 外部參與者
| 參與者 | 互動 | 信任假設 |
|---|---|---|
| 本機使用者 | UI、檔案選擇、設定、風險確認 | 可授權，但可能誤解技術風險 |
| 第三方 Android App | AIDL runtime API | caller UID 可取得；package 名稱不可單靠自報信任 |
| Loopback client | HTTP API | 必須有 bearer／paired principal；Host/CORS 不是認證 |
| LAN client | TLS、pairing、scoped token | 網路與 DNS 不可信；server identity 必須 channel-bound |
| Catalog／模型 host | metadata、model artifacts、revocation | HTTPS 只保護傳輸；真實性來自簽章／digest pin |
| Storage provider | SAF URI／PFD | 內容、長度、seekability、生命週期與 provider 穩定性不可信 |
| Native engine／driver | 推理與加速 | 可能 crash、hang、不可取消、誤報 memory 或含記憶體安全缺陷 |
| Android OS／OEM | process、FGS、LMK、Binder、driver policy | 平台行為有版本與 OEM 差異，不能寫成無條件 SLA |

## 3. 核心邊界
```text
Users / Apps / LAN Clients
             |
      UI / HTTP / AIDL
             |
     OmniLLM Control Plane
    (identity, policy, state,
     scheduling, persistence)
             |
  Engine Execution / Parser / Sandbox
             |
 Models / Native Runtimes / Drivers
```

Control Plane 是所有資源、資料與 operation identity 的權威。Execution Plane 只能透過 opaque handle 與版本化 IPC 接收已准入工作；不能自行修改 catalog、token、settings 或資料庫。

## 4. 資料流分類
- **控制資料**：principal、capability、plan、lease、operation state、health、policy；需要完整性與可恢復性。
- **模型資料**：大檔案或多檔 package；使用 PFD／file descriptor，不進 Binder 大物件。
- **推理資料**：prompt、asset handle、token delta、embedding；受 transport cap、owner 與 retention policy 約束。
- **證據資料**：source assertion、compatibility evidence、measurement profile、license event；不可與即時狀態混合。
- **診斷資料**：event、metrics、trace；依 principal 與 redaction policy 投影。

## 5. 系統外部保證
OmniLLM 保證語義一致、狀態可查、能力不虛報、資源先准入、安全邊界如實宣稱；不保證任意模型輸出正確、任意 driver 可取消、所有惡意 native code 無 DoS，或 OS 在固定秒數內重啟服務。
