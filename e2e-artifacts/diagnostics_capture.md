# Post-UI-journey diagnostics capture
# Device: emulator-5554 (Pixel 7 class, API 36)
# Time: 2026-08-06T15:35:22.6675048+08:00
# Method: adb shell + run-as (no Appium needed for this step)

## 1) logcat_snip.txt
Command (PowerShell equivalent of): adb logcat -d -t 500 | findstr /i "omnillm OmniLLM llama engine crash AndroidRuntime"
Result: sparse — only ActivityThread REPLACED noise for com.omnillm.debug + artd GetBestInfo (no usable ART artifacts).
No AndroidRuntime FATAL, no llama/engine load lines in last 500 buffered lines matching the filter.
Supplemental: logcat_snip_fuller.txt has full-buffer filter (103 matches) — mostly Appium/UiAutomator QueryController against com.omnillm.debug UI text (Download, etc.). No crash stack traces observed in sample.

## 2) package.txt
Requested package com.omnillm: NOT INSTALLED ("Unable to find package").
Installed: com.omnillm.debug
  versionName=0.1.0-debug
  versionCode=1
  minSdk=28 targetSdk=36
  dataDir=/data/user/0/com.omnillm.debug

## 3) Installed models paths
run-as com.omnillm: FAIL (unknown package)
run-as com.omnillm.debug: SUCCESS (debuggable)
Model store layout present but EMPTY:
  /data/user/0/com.omnillm.debug/files/model-store/blobs/
  /data/user/0/com.omnillm.debug/files/model-store/packages/
  installations/, diagnostics/, quarantine/, license-text/ all empty
No GGUF on /sdcard; no Android/data/com.omnillm(.debug) external dir.
=> gemma-3-270m Q8_0 not present on device at diagnostic time.
=> Smoke note only: cannot qualify engine PASS; no model blobs to load.

## Artifacts written
- e2e-artifacts/logcat_snip.txt
- e2e-artifacts/package.txt
- e2e-artifacts/models_paths.txt
- e2e-artifacts/logcat_snip_fuller.txt (extra)
- e2e-artifacts/diagnostics_capture.md (this file)
