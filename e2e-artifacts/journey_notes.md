# OmniLLM E2E smoke journey notes

**Result: FAIL overall** (smoke only — no engine PASS invented)

## Environment
- Device: emulator-5554 (API 36 / Android 16)
- Package: `com.omnillm.debug` (not `com.omnillm`)
- Model file present: `/sdcard/Download/omnillm-e2e/gemma-3-270m-Q8_0.gguf` (~288 MiB)
- Automation: Appium MCP embedded UiAutomator2

## Launch blockers fixed mid-session
1. **Keystore IV** — `AndroidKeystoreMasterKey.encryptWithKeystore` passed caller IV with `setRandomizedEncryptionRequired(true)` → runtime crash. Fixed to use cipher IV.
2. **primaryRail nulls** — companion eager `listOf(Home, …)` before data object init → bottom bar crash. Fixed with `by lazy`.
3. **ModelHub LazyColumn keys** — pin + SAF suggested cards shared `modelRevisionId` → crash. Fixed composite keys.

## Step outcomes
| Step | Result | Notes |
|------|--------|-------|
| A Launch | PASS | Home, Runtime READY, no blocking onboarding |
| B Exploratory flag | FAIL | Settings shows key=false, no toggle; no settings table |
| C SAF import | FAIL | Action `onClick` stubbed; no DocumentsUI |
| D Import wait | FAIL | No job |
| E Playground prompt | FAIL | Empty gate, no chat input |
| F Generate | FAIL | Blocked by E/C/B |
| G Screenshots | PASS | See artifacts |
| H journey.json | PASS | This run |

## Import UI blocker (source)
```kotlin
// ModelHubScreen.kt ModelDetailPane
card.allowedActions.forEach { action ->
    SecondaryActionButton(
        label = actionLabel(action),
        onClick = { /* mutations via ViewModel when user confirms */ },
    )
}
```

ViewModel has `import(StartImportSpec)` / `download(...)` but UI never calls them.  
`StartImportSpec.assetId` is an opaque asset handle (never a filesystem path).

## Playground empty (page source excerpt)
- `text="No usable model for inference"`
- `text="Inference entry points route to Model acquisition when no model is ready. This is not a blank chat."`
- CTA: `Open ModelHub`

## Artifacts
- `A_launch_home.png`, `B_settings.png`, `C_modelhub.png` / `modelhub.png`
- `C_import_detail.png` / `import.png`
- `playground_before.png`, `playground_after.png`
- `journey.json`
