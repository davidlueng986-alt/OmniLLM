---
id: "CORE-RESOURCE"
title: "多維資源治理設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "core-platform"
lastReviewed: "2026-07-31"
---

# 多維資源治理設計

## 1. 可加總資源與操作限制分離
`ResourceVector` 只包含可用同一單位或獨立維度進行守恆計算的非負數量：

```text
cpuAnonBytes
cpuFileBytes
cpuPeakBytes
sharedMemoryChargeBytes
gpuDedicatedBytes
gpuSharedBytes
npuBytes
nativeThreads
fileDescriptors
temporaryDiskBytes
networkBytesInFlight
```

Thermal、電力、前景需求、網路條件與最大連續運算時間不是可加總的資源，統一放入 `OperatingConstraint`。不得把 `powerThermalClass` 加入向量後與 bytes 相加。

每個 engine plan 回傳 steady、peak、confidence、evidence、reclaimability、`ResourceVector` 與 `OperatingConstraint`。未知維度使用保守 envelope，不以零表示。

## 2. 數值與單位規則
- Bytes 統一以非負 `uint64` bytes 表示；threads、FD 以非負整數表示。
- 外部輸入先正規化到 canonical unit；負值、NaN、Infinity、未知 suffix 或超出上限一律拒絕。
- 加、減、乘、向上取整及 context 反解使用 **checked arithmetic**；任何 overflow／underflow 都 fail closed 為 `ADMISSION_REJECTED` 或 `INVALID_REQUEST`，不得 wraparound。
- `cpuPeakBytes` 是同一 operation envelope 的峰值約束，不與 steady resident 欄位重複相加；計算式必須明示哪些欄位是 additive、max 或互斥候選。
- 每次 admission 保存 policy version、unit normalization version、input vector、budget snapshot 與計算結果，供重現與稽核。

## 3. Budget 組成
- **App cap**：依裝置 class 與 policy profile 固定／版本化的上限。
- **System headroom**：動態系統可用量與安全 reserve。
- **Accelerator cap**：driver/API 可得資訊與實測 envelope。
- **Operational cap**：thread、FD、disk temp、network 與 `OperatingConstraint`。

不能直接用 `availMem × 0.7` 當總 resident budget，因 App 自身載入會使 budget 自我收縮。PolicyProfile 必須版本化並按裝置證據校準。

## 4. Reservation 與 Allocation
- `Reservation`：短期，保留執行峰值與 temporary workspace；含 issuer boot/runtime epoch、owner、nonce、deadline、ResourceVector。
- `AllocationHandle`：commit 後代表模型、Session 或 cache 的常駐資源；有 resource owner、state 與 release barrier。
- Commit 可原子把 reservation 的一部分轉為 allocation；terminal 只釋放未轉移部分。
- 每次轉移必須逐維守恆：`beforeReserved = convertedAllocation + releasedRemainder`。重複 commit/release 由 ID 保證冪等。

## 5. Admission
任一維度不足、限制條件不允許或證據超出 workload envelope 時，整體拒絕或產生另一個較小 candidate plan。Context limit 由所有限制共同反解：app cap、anon headroom、accelerator、engine hard max、thermal/policy；回傳的任何 ctx 必須在同一 snapshot 下可 reserve，`ctx + step` 不可。

## 6. Eviction
1. 選擇可回收 candidate；
2. 設為 DRAINING，阻止新引用；
3. cancel／等待 active operation；
4. close Session／unload model；
5. 觀測 worker/native release 或 process death；
6. 完成 AllocationHandle release；
7. 容量才可回補。

Planned eviction 不預先抵扣。

## 7. 記憶體觀測
- 同 UID process：採明確 charge policy（PSS/USS/RSS 角色），避免 shared mmap 重複計費。
- isolated／different UID：worker self-report 附 timestamp、seq、來源；但 charge floor 永遠不低於事前 envelope。
- Self-report 過期、異常或 worker hang 時回到保守上界。
- 無法對惡意 native code 提供絕對硬 RSS 上限，殘餘 DoS 必須明示並以 kill/fail-safe 處理。

## 8. Thermal 與電力
`OperatingConstraint` 可要求降低 batch、threads、並行數、後端，或暫停新工作。每次調整產生 policy event，UI 顯示實際 backend 與原因。不可在未取得新 reservation 的情況下增大 workspace；thermal policy 也不得偷偷改變模型或資料信任語義。
