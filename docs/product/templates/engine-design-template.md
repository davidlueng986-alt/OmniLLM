# Engine Integration Design：<引擎名稱>

## Metadata
- Engine ID：`ENGINE-...`
- 文件狀態：`VALIDATION_REQUIRED`
- Owner：
- Official upstream（discovery）：
- License：
- Upstream lock／artifact evidence：`NOT_EXECUTED`

## 1. Integration shape 與 process placement
## 2. Model format、backend、ABI、device 與 driver
## 3. Capability mapping 與明確 unsupported／unknown
## 4. Plan／Reservation／Commit／PreparedOperation
## 5. Load／Session／KV／tokenizer／template
## 6. Phase cancellation、deadline 與最大不可搶占時間
## 7. ResourceEnvelope 與 accelerator memory
## 8. Error／event／recovery／worker death
## 9. Trust placement、parser/native risk 與 input boundary
## 10. Packaging、module lifecycle、16 KB 與更新/rollback
## 11. Known limitations、quirks 與 residual risks
## 12. Qualification cell、evidence status 與 invalidation

能力只能在完整 EngineBuildId、platform、device、driver、model、operation 與 resource envelope 有未過期 PASS evidence 時標記 `SUPPORTED`。
