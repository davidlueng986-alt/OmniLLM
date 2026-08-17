---
id: "ARCH-TRUST-TOPOLOGY"
title: "程序、UID 與信任拓樸"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "security-architecture"
lastReviewed: "2026-07-31"
---

# 程序、UID 與信任拓樸

## 1. Android 程序設計
| 單元 | UID／權限 | 職責 | 可接觸資料 |
|---|---|---|---|
| `main` | App UID | UI、navigation、local view state | 由 Admin API 投影的最小資料 |
| `:runtime` | App UID | Control Plane、DB/model store owner、Gateway、trusted engine | privileged state、token broker、catalog、trusted models |
| `:engine_worker` | App UID | 只對可信 engine code 提供 crash containment | 不能視為 security sandbox；資料 access 依 App UID |
| `:parser` | isolated UID | bounded metadata parsing | runtime 傳入的 read-only FD 與輸出 pipe |
| `:sandbox_cpu` | isolated UID | 不受信任模型 CPU inference | read-only model FD、bounded request data、supervisor binder |
| Companion Sandbox App | 不同 package／UID | 不受信任 accelerated inference | 透過 explicit grant/PFD 與窄 IPC；不能讀 OmniLLM private state |

## 2. 為何不再使用同 UID 高效能沙箱宣稱
同 UID native code 可繞過應用層 owner 約定，存取 app-private files，並可能調用該 UID 可使用的 Keystore 能力。因此同 UID process 只提供 crash containment。未簽章或來源不可信的 accelerated path 必須使用不同 package／UID 的 companion sandbox；若平台或引擎無法支援，該組合回 `TRUST_PLACEMENT_REQUIRED`，而不是退回同 UID 並淡化風險。

## 3. Supervisor 與 epoch fencing
每個 worker 綁唯一 supervisor binder 與 `runtimeEpoch`。Supervisor 死亡後：

1. 停止接受新 command；
2. 取消 cooperative operation；
3. 在短期限內輸出可用的 terminal／journal frame；
4. 關閉 Session/model handle；
5. 退出 process。

所有 plan、lease、commit、operation 與 callback 都含 bootId/runtimeEpoch；舊 epoch 一律拒絕。

## 4. Secret Broker
Client token 原文、LAN private key 與高權限 Keystore operation 只在 runtime 的 Secret Broker。Worker 不持有 alias 名稱或一般簽章／解密能力；需要的 operation 使用最小化 request、principal、purpose、expiry 與 audit record。

## 5. Parser 與模型資料
Runtime 將輸入先 materialize 到 quarantine，拒絕 symlink、hardlink、special file、稀疏濫用與越界路徑，再以 read-only FD 傳給 parser。Parser output 是版本化 typed descriptor，有數量、字串、tensor、shape 與時間上限；自由 JSON 不能驅動 trust placement。

## 6. 殘餘風險
isolated／different UID 不能消除 CPU、memory、driver、side-channel、incorrect output 或 kernel bug。Resource admission 使用不可由 worker 降低的 envelope，self-report 只補充觀測；system pressure 觸發 fail-safe kill。
