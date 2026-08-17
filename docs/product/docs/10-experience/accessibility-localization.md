---
id: "UX-A11Y-I18N"
title: "無障礙與在地化設計"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "product-design"
lastReviewed: "2026-07-31"
---

# 無障礙與在地化設計

## 1. 適用範圍
Onboarding、ModelHub、Playground、Dashboard、Client／LAN 管理、Job、Diagnostics 與 AI 內容回報都遵守同一接受標準。無障礙不是 UI 實作後補測，而是每個 Feature Design 的必要狀態與驗收。

## 2. Semantics 與導航
- 每個可操作元件有穩定 accessible name、role、state、value 與 hint；圖示不能只靠形狀或顏色。
- TalkBack traversal 依視覺／工作順序；modal、sheet、progress 與 error 出現時焦點移動可預期，關閉後回原觸發點。
- 鍵盤、switch access 與 D-pad 可到達所有 action；不要求複雜 gesture 作唯一入口。
- 動態 token／metric 不逐字洗版；以節流的 summary／live-region policy 告知重要狀態。

## 3. 視覺與觸控接受標準
- 一般文字對比至少 4.5:1；大文字／大型圖形至少 3:1；狀態不能只靠顏色。
- 觸控 target 至少 48×48 dp；危險 action 與鄰近 action 有足夠間距。
- 支援至少 200% font scaling，不裁切核心文字／按鈕，不以固定高度藏內容。
- 支援 dark／light 與高對比；動畫尊重 reduced-motion／system animator scale，關鍵流程不依賴動畫完成。

## 4. 內容與數值
- Error、trust、compatibility、resource 與 evidence label 使用可翻譯 message key；程式碼 token 保留但不作唯一說明。
- 數值包含 locale-aware unit、timestamp／age 與 evidence label；`UNKNOWN` 不翻成 0。
- Model／engine／backend 技術名詞有 glossary help；一般模式先顯示結果與建議，詳細欄位可展開。

## 5. 在地化
- 不拼接可翻譯句子；plural、gender-neutral copy、數字／日期／byte unit 由 locale formatter 處理。
- 支援 RTL layout；圖示方向、code block、model ID 等必要 LTR 片段明確標記。
- 安全／風險／同意／AI report 文案修改需 security-ux review；translation 不能淡化不可逆風險或資料外送。

## 6. 驗證義務
Pre-build 階段只定義接受標準，不偽造實作證據。每個可建置 UI 必須產出：TalkBack traversal 記錄、keyboard/switch checklist、contrast/touch/static scan、200% font screenshot、RTL snapshot 與至少一個端到端人工測試。結果登錄 `specs/ux-acceptance.yaml` 所列 scenario ID。
