---
id: "ANDROID-STORAGE"
title: "Android Storage、SAF 與檔案權限"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "android-platform"
lastReviewed: "2026-07-31"
---

# Android Storage、SAF 與檔案權限

## 1. Storage model
Model store、DB、trust state與journal位於app-private/no-backup storage，由runtime唯一寫入。外部shared storage不作active model store；SAF只作使用者選擇的輸入／匯出介面。

## 2. SAF Import
兩種策略：

1. 立即dup/read並materialize到quarantine；適合一般model import。
2. 取得persistable URI permission並保存typed ImportSpec；只在provider支援且Job需延後時使用。

ImportSpec含URI token、grant flags、expected size/digest、format hint、owner與expiry。自由payload JSON不能承載安全關鍵欄位。

## 3. PFD處理
Receiver dup FD、fstat、記錄seekability/size，設定byte/time cap。對pipe或size未知內容，先有界複製。Provider revoke、death、short read、content change與process restart都有明確Job結果。

## 4. Atomic promotion
Quarantine驗證完成後，fsync內容與父目錄、atomic move到content-addressed store，再用DB transaction建立package/installation。若檔案與DB跨界crash，reconciler以digest及journal收斂。

## 5. Backup與資料匯出
Manifest/data extraction rules排除models、DB、DataStore、trust、journal與tokens。使用者要遷移時，由OmniLLM輸出版本化、可驗證且可選加密bundle；不依賴系統backup複製不完整state。
