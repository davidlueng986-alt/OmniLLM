---
id: "SEC-PRIVACY"
title: "隱私、Telemetry 與資料最小化"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "privacy-security"
lastReviewed: "2026-07-31"
---

# 隱私、Telemetry 與資料最小化

## 1. 預設
Prompt、output、image/audio content與embedding不離開裝置，也不預設寫入長期log。History、diagnostic export與任何telemetry均需明確設計與使用者控制。

## 2. Data inventory
每類資料記錄purpose、owner、storage、retention、access、export與delete：

- Model metadata／license／source；
- Request metadata與optional conversation history；
- Operational metrics與measurement；
- Token/client metadata；
- Device/driver fingerprint；
- Crash／diagnostic event；
- AI content report（與 telemetry 分離，只有使用者確認的最小 payload）。

## 3. Telemetry
若啟用外送，只允許明確allowlist的aggregated事件，預設關閉；不得含prompt、output、model file path、token、完整IP、stable hardware identifier或可重建個人內容。Sampling與schema version可見，使用者可撤回並刪除queued telemetry。

## 4. Diagnostics export
匯出前顯示內容摘要與敏感性。Bundle使用random ID、可選加密、到期清理；欄位allowlist由schema控制。Raw native log須經redaction，未知欄位預設排除。

## 5. Android backup與screenshots
Private model/trust/token/database排除backup。顯示token原文、risk ack或敏感prompt的畫面可依政策使用secure flag；使用者應能複製必要資料但收到明示提醒。

## 6. 多使用者／工作設定檔
Android user/profile進principal與owner key。不同user的token、Session、history與model access依平台storage boundary隔離；不假設同一package across profile可共享private state。


## 7. AI 內容回報
AI content report 是使用者主動建立的個別資料流，不屬於 telemetry。送出前逐欄顯示；完整 prompt/output 預設排除，optional excerpt 需明示選取。Queue 加密、具 TTL 與刪除；developer endpoint 的 retention、用途、聯絡方式與跨境處理必須在 UI 揭露。
