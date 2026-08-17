---
id: "FEAT-ROUTING"
title: "多模型、路由與明示降級"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "feature-team"
lastReviewed: "2026-07-31"
---

# 多模型、路由與明示降級

## 0. 核心價值對應
- **低技術門檻自動架設**：使用者可用目標與偏好選模型，不必理解每個 engine/backend 的差異。
- **統一調用**：所有候選經同一 capability、trust、Plan、Session、resource、fallback 與 terminal 模型。
- **可視化監控**：顯示候選、淘汰原因、排隊、公平性、actual revision/engine/backend、fallback與allocation。

## 1. 路由輸入
Caller 可指定 exact revision、alias、required capabilities、latency/resource/quality preference、trust minimum、allowed backends與 `FallbackPolicy`。跨 revision 只有 `ALLOW_LIST` 且 allowlist明示時可發生；alias 在 request accept時固定為 revision。

## 2. 候選與決策
Orchestrator依序評估 trust placement、capability cell、engine lifecycle qualification、Session compatibility、load/inference resource、health/thermal及caller policy。每個 rejection保存 stable reason code；Recommendation與runtime routing使用同一決策輸入，不建立兩套算法。

## 3. Admission 與常駐模型
每個候選建立 Load／Inference Plan與 `ResourceVector`。Pin、LoadedModel、Session KV與workspace分開計費；planned eviction不提前增加容量。若候選需先卸載，進DRAINING並完成release barrier後才重新admit。

## 4. Session 邊界
Session綁 owner、revision、load key、engine build、tokenizer/template epoch。路由切換 revision/backend若無可證明的state transfer，不沿用KV；conversation文字可重送建立新Session，但 response必須揭露 continuity已重建而非原KV。

## 5. 公平性
Scheduler使用 per-principal FIFO + global deficit round-robin、cost classes與aging。大模型load不能永久餓死；大量小request也不能無限阻止已准入工作。Policy version與estimated start對外可見，但不是無條件SLA。

## 6. 驗收情境
1. **無授權 fallback**：exact revision失敗時，policy NONE不得換backend/revision；返回候選拒絕原因。
2. **同 revision backend fallback**：只有明示策略允許，且 response/trace/UI顯示 actual backend與原因。
3. **跨 revision**：只使用allowlist；新revision建立新Session，不能重用舊KV或冒充continuity。
4. **資源競態**：兩候選同時admit時，reservation原子化且不超任一維cap；eviction barrier前不重用容量。
5. **公平性**：決定性queue fixture在相同policy/input下產生唯一順序；任一principal不永久飢餓。
6. **可解釋性**：每個未選候選至少有trust/capability/resource/health/policy reason之一，Dashboard與request metadata一致。
