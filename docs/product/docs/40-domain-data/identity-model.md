---
id: "DATA-IDENTITY"
title: "身分與 Canonicalization 模型"
status: "BASELINE"
stage: "PRE_BUILD_DESIGN"
authority: "NORMATIVE"
owner: "data-architecture"
lastReviewed: "2026-07-31"
---

# 身分與 Canonicalization 模型

## 1. Digest 表示
所有 SHA-256 在 canonical JSON/IDL 中使用 64 字元 lower-case hexadecimal string；不得使用語言專屬 ByteArray 序列化。時間使用 UTC epoch milliseconds 或 RFC 3339 UTC，並明確欄位語義；monotonic duration 不與 wall clock 混用。

## 2. BlobId
```text
BlobId = sha256(raw file bytes)
```

BlobId 不含 role、path、format、quantization 或 parser 判定。Parser 升級不能改變 BlobId。

## 3. ArtifactPackageId
Canonical object：

```json
{
  "schemaVersion": 1,
  "files": [
    {"role":"WEIGHTS","blobId":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","bytes":123,"shardIndex":0}
  ]
}
```

Files 以 `(role, blobId, bytes, shardIndex)` total order 排序。重複完全相同項是否允許由 schema 明定；一般模型 package 拒絕無語義重複。

## 4. ModelRevisionId
包含：ArtifactPackageId、format descriptor、model architecture、tokenizer digest、chat template digest、versioned QuantizationDescriptor、tensor layout policy digest、必要 model metadata schema version。它是 executable semantic identity，不是單純 raw bytes identity。

## 5. QuantizationDescriptor
支援全域 quant label、per-file／per-tensor policy digest、mixed quantization 與 parser version。市場字串例如 `Q4_K_M` 可作 display label，但 identity 使用 canonical typed descriptor。

## 6. EngineBuildId
包含 upstream repository、tag/commit、source archive digest、patch set digest、build options、compiler/toolchain lock、ABI、native artifact digest 與 adapter version。不使用「最新」作身分。

## 7. DeviceExecutionFingerprint
包含 platform、OS build、kernel/runtime、ABI、SoC、CPU feature、GPU/NPU vendor/driver、memory class、page size、engine-relevant library versions。Backend-specific fingerprint 可由此投影。

## 8. Profile IDs
- `CompatibilityProfileId`：engine build + backend + device + model + workload envelope + method version。
- `MeasurementProfileId`：上述加上 session config、threads、batch、parallel sessions、prompt fixture、tokenizer/template、thermal/power condition與 metric method。
- `MeasurementRunId`：profile 下單次執行，含 monotonic run sequence。

## 9. ID 驗證
每個持久化 canonical object 同時保存 canonical bytes／JSON與 hash；讀取時可重算。未知 major schema version fail closed，不把缺欄 object 判為 ACTIVE／TRUSTED。
