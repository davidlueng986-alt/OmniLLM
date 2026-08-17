---
id: "PROD-GLOSSARY"
title: "核心術語表"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "product-architecture"
lastReviewed: "2026-07-31"
---

# 核心術語表

| 術語 | 定義 |
|---|---|
| **BlobId** | 單一檔案 raw bytes 的 `sha256` 身分，不包含角色或路徑。 |
| **ArtifactPackageId** | 由版本化角色清單、BlobId、大小與全序產生的 package 身分。 |
| **ModelRevisionId** | 描述可執行模型語義的版本化身分，包含 ArtifactPackage、模型架構、tokenizer、template、quantization descriptor 與必要 manifest。 |
| **InstallationId** | 某 ModelRevision 在某儲存位置的一次安裝實例。 |
| **EngineBuildId** | 上游 commit/tag、patch set、build options、toolchain 與 artifact digest 的身分。 |
| **LoadKey** | 決定 LoadedModel 可否共用的 canonical config，包括 revision、engine build、backend、device、template/tokenizer epoch 與 load-time options。 |
| **Capability** | 在特定 evidence envelope 下可以執行的操作或語義。 |
| **Compatibility Evidence** | 某 model／engine／backend／device profile 曾通過 probe 或執行的證據；不提升來源信任。 |
| **Source Assertion** | 來源、簽章、hash、revision pin、license 與時效等版本化聲明。 |
| **Trust Placement** | 依來源證據、engine code 與 operation phase 決定應在哪個 process／UID 執行。 |
| **Crash containment** | 獨立 process 只限制崩潰擴散；同 UID 時不提供資料機密性。 |
| **Permission isolation** | 使用 isolated UID 或不同 package／UID，使 code 無法直接存取 privileged app data／secrets。 |
| **Plan** | 執行前、無 domain mutation 的有界分析結果，綁 request、owner、epoch 與 evidence。 |
| **Reservation** | Governor 在副作用前核發的暫時資源預約。 |
| **AllocationHandle** | commit 後代表仍常駐資源的帳本所有權；不能在 request terminal 時自動釋放。 |
| **PreparedOperation** | 已完成 domain commit、尚未或正在開始 execution 的 idempotent operation handle。 |
| **Session** | 由 owner、revision、load key、template/tokenizer fingerprint 與 KV state 共同定義的對話執行上下文。 |
| **Delivered checkpoint** | 應用層確認 client 已接收的輸出位置；AIDL 可由 ACK 建立，SSE socket write 不足以證明。 |
| **Poisoned Session** | 可能部分 mutation、無法 rollback 或 owner／epoch 不再可信的 Session；不得回池。 |
| **MeasurementProfileId** | 完整量測條件的 canonical identity。 |
| **MeasurementRunId** | 在 profile 下的一次實際執行歷史。 |
| **Principal** | 經認證的 caller 身分。AIDL 以 calling UID、Android user 與已驗證 binding 資料組成；HTTP 以 token subject／scope 組成。 |
| **Revocation epoch** | token、ACL、trust 或 risky-mode 變更後用來 fence 舊 connection、queue、Session 與 command 的單調版本。 |
