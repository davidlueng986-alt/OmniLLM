---
id: "SEC-INPUT"
title: "輸入、下載與資源濫用防護"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "security-architecture"
lastReviewed: "2026-07-31"
---

# 輸入、下載與資源濫用防護

## 1. HTTP/JSON
限制compressed bytes、decompressed bytes、parse time、nesting depth、node count、array/key/string length與duplicate key policy。使用streaming parser與request deadline。Unknown fields仍計入size/node budget。

## 2. Headers與diagnostics
Request/response header有總量與單欄限制。`ignored params`只列前N項及byte cap，超出回count+digest；不讓合法忽略欄位造成response header overflow。

## 3. URL與DNS
只允許HTTPS與明確host/port。每次DNS resolution及每次redirect都套private/loopback/link-local/multicast/metadata endpoint deny policy；處理IPv4-mapped IPv6、IDNA、trailing dot、userinfo與DNS rebinding。下載連線有byte/time/rate cap。

## 4. Range download
Range採`[start,end)`，end不超content length；同attempt/path不可重疊，verified bytes不超range。Validator綁ETag/Last-Modified；`If-Range`回200時重新開始或依policy完整替換，不拼接不相容partial。

## 5. PFD/SAF
Service dup FD後fstat；檢查regular/pipe策略、offset、declared/actual size、seekability、read time與hard cap。Provider可變內容時先materialize並hash。Ownership明定誰dup/close、process death後如何reopen；不可恢復URI則Job失敗並要求使用者重新選擇。

## 6. Model package
限制file count、total/individual size、path length、nested directories、sparse allocation與tensor metadata。拒絕symlink/hardlink/special file。Manifest列出的FD逐一與size/digest對照。

## 7. Output abuse
限制max output tokens、event rate、buffer、structured output depth與tool call count。Client不ACK或network backpressure時停止生成、取消或detach依明確policy，不能無界buffer。

## 8. Fuzz與negative corpus
設計要求涵蓋JSON/encoding、URL/DNS、PFD、model header、range/validator、canonicalization與IDL unknown version corpus。這些是實作驗證義務，不改變pre-build架構結論。
