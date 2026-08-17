---
id: "FEAT-AUTOSETUP"
title: "低門檻自動架設"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "feature-team"
lastReviewed: "2026-07-31"
---

# 低門檻自動架設

## 0. 核心價值對應
- **低技術門檻自動架設**：使用者只描述用途與偏好，系統完成裝置辨識、候選模型、取得、安裝、保守配置與第一次推理。
- **統一調用**：Recommendation 不直接呼叫特定引擎；所有候選都經 capability、Plan、Reservation、Commit 與 canonical request。
- **可視化監控**：每個自動選擇都顯示原因、信心、證據來源、實際 engine/backend、降級與資源結果。

## 1. 使用者工作
使用者表達「我想在本機聊天／取得 embeddings／處理圖片」及品質、速度、儲存、電力與風險偏好。系統不得要求使用者先知道 GGUF、execution provider、context、threads 或 driver 名稱。

## 2. 所需能力
`DEVICE_DISCOVERY`、`MODEL_ACQUISITION`、`MODEL_IDENTITY`、`COMPATIBILITY_EVALUATION`、`RECOMMENDATION`、`AUTOMATED_CONFIGURATION`、`SAFE_INSTALLATION`、`RESOURCE_ACCOUNTING` 與目標 operation capability。

## 3. 完整流程
1. 建立 `DeviceExecutionFingerprint`、storage/battery/thermal snapshot 與平台限制。
2. Capability service 形成 engine/backend/model-format cells；UNKNOWN 與 UNSUPPORTED 分開。
3. Recommendation 依 trust、hard compatibility、resource fit、stability evidence、使用者偏好及 performance estimate 排序。
4. 每個候選顯示模型大小、license、來源信任、placement、預估記憶體、下載量、信心與替代方案。
5. 使用者選 catalog、pinned download 或 SAF import；Acquisition Job 執行 quarantine、verification、license 與 atomic install。
6. `planLoad` 與 `planInference(SourceSessionRef.None)` 產生資源 envelope；Governor 准入後才 commit。
7. 第一次推理使用正式 scheduler、interface、stream 與 error，不走特殊捷徑。
8. Dashboard 顯示實際配置；使用者可以保留、比較或重新選擇，但產品不靜默換 revision。

## 4. Recommendation 規則
硬性順序為：安全／政策可執行 → operation capability → 完整資源 fit → 已知穩定性 → 使用者偏好 → 性能估計。未知 accelerator 不優先於已知可用 CPU。推薦結果包含 `reasonCodes`、被淘汰候選及最小可行調整，不只回一個 model name。

## 5. 自動配置
配置輸出包含 model revision、engine build candidate、backend、context、KV format、threads、batch、parallelism、placement 與 fallback policy。所有值標記來源：model hard limit、engine envelope、device evidence、policy 或使用者偏好。配置變更若增加 workspace，必須重新 Plan／reserve。

## 6. 降級與恢復
- RAM／accelerator 不足：提出較小 context、模型、量化或卸載／取消 pin；不直接改變已接受 request。
- Accelerator UNKNOWN／初始化失敗：只有 caller policy 允許時改同 revision 的 CPU backend，並揭露 actual backend。
- Network 不可用：保留 SAF import、已安裝模型與離線 root 能力；不把未完成下載標 READY。
- Catalog／license／trust 失效：停止新 acquire／privileged load，顯示原因與安全替代。
- Process death：Job 從 durable checkpoint 恢復；不可持久化的 URI／FD 精確要求使用者重新選擇。

## 7. 資料與隱私
裝置指紋與推薦 profile 保存在本機；不需把硬體清單、模型來源或 prompt 上傳。Recommendation history 可刪除，且不得包含 token secret、private path 或原始敏感內容。

## 8. 驗收情境
1. **零模型起點**：新安裝在無既有 Session／LoadedModel 下，只輸入用途即可到達第一個成功 terminal；過程不要求 engine name。
2. **未知加速器**：GPU/NPU evidence 為 UNKNOWN 時，系統選擇已知 CPU 或明確拒絕；不得把 probe 成功升級來源 trust。
3. **准入拒絕**：Plan 顯示資源不足時，未發生 model load、KV mutation 或未計費 allocation，UI 提供可行調整。
4. **下載／安裝中斷**：在每個 materialize、verify、fsync、rename、DB 邊界終止程序，重啟後只有可恢復 Job、QUARANTINED 或 READY，沒有半安裝可被 load。
5. **明示降級**：只在 fallback policy 授權時切 backend／revision，response、history、Dashboard 都揭露 actual choice。
6. **可解釋性**：每個推薦與拒絕至少有一個穩定 reason code、evidence label 與受影響資源／能力。
