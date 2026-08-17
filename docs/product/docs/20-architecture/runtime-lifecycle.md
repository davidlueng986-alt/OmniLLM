---
id: "ARCH-RUNTIME-LIFECYCLE"
title: "Runtime 生命週期與可用性"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "architecture"
lastReviewed: "2026-07-31"
---

# Runtime 生命週期與可用性

## 1. 權威狀態
`specs/state-machines.yaml#RUNTIME` 是狀態名稱與轉移的單一權威：

```text
STOPPED → STARTING → RECOVERING → READY
                         └──────→ DEGRADED
READY/DEGRADED → DRAINING → STOPPED
STARTING → WAITING_FOR_USER_FOREGROUND → STARTING
RECOVERING → FAULTED → STOPPED
READY ⇄ DEGRADED
```

Runtime 是可重啟的循環 aggregate，因此 `STOPPED` 是 stable state，不是 terminal。文件與 UI 不得另創同義狀態名稱；若需要友善文案，只能映射上述 canonical machine states。

## 2. 啟動流程
1. 取得 single-writer lock，durable advance bootId/runtimeEpoch，fence 舊 worker。
2. 開啟 DB，驗證 schema／canonical type version，載入 trusted clock 與 policy。
3. 對帳 request、commit、command、job、asset、model installation、allocation 與 client registration。
4. 驗證 model store/DB projection、unfinished transfer、quarantine、revocation與catalog root。
5. 建立 DeviceExecutionFingerprint、capability cache invalidation與health snapshot。
6. 啟動本機 Gateway／binding services；只有 recovery gate通過才進 READY，部分功能受限時進 DEGRADED。
7. LoadedModel／Session只有在identity、owner、epoch、allocation與engine recovery能力均可證明時才恢復；否則drain/poison。

## 3. Ready 與 Degraded
`READY` 表示 control plane、durable state、auth、scheduler與最低安全路徑可接受符合policy的request；不表示每個engine/model capability都SUPPORTED。`DEGRADED`仍可接受明確允許的子集，例如CPU可用但accelerator失效；每個拒絕附reason與恢復條件。

## 4. 停止與 drain
停止新 request → durable固定queue／client epoch → 依policy完成或取消active operation → 關閉／poison Session → unload model → 等待AllocationHandle release barrier → flush journal/DB → 停止FGS／Gateway／binding。使用者停止、OS限制、App更新與安全撤銷可以使用不同reason，但不能跳過資源和持久化barrier。

## 5. Crash recovery
- Durable request／command／job／commit record決定client query結果。
- Worker journal是non-authoritative evidence；runtime DB/journal才是安全決策權威。
- 跨process commit的unknown outcome進RECONCILING；無法證明時標ABORTED_UNCERTAIN並隔離相關Session/allocation。
- Supervisor binder death要求worker在短期限內停止新工作、fence輸出並退出；新runtime epoch不接受舊命令。
- Asset、download、install與diagnostic等temporary files由state+content identity對帳，不以filename猜測成功。

## 6. 平台可用性表述
不承諾「崩潰後固定五秒恢復」。可定義指定Android版本、裝置、資源充足、使用者未停止、foreground eligibility成立下的量測SLO，並報percentile與profile。OS不允許啟動FGS時進`WAITING_FOR_USER_FOREGROUND`，不得以隱藏重試違反平台限制。

## 7. Worker／module lifecycle
Engine module 使用 `specs/state-machines.yaml#ENGINE_MODULE`；其狀態包含 ABSENT、INSTALLING、INSTALLED、VALIDATING、AVAILABLE、DRAINING、RESTART_REQUIRED、ROLLING_BACK、FAILED。Split/module digest綁EngineBuildId；runtime與每個worker完成protocol/artifact handshake後才可接收operation。Module更新先drain舊LoadedModel，再受控restart與qualification。
