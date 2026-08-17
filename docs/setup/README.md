# 安裝與建置指南

這份指南假設你要在本機把 OmniLLM Android App 建出來並跑第一次。不需要先讀完產品包。

## 選一條路

| 你是誰 | 走哪一頁 |
|---|---|
| 第一次 clone，想編譯出 debug APK | 先 [prerequisites.md](prerequisites.md)，再 [build.md](build.md) |
| APK 已經裝上，想完成第一次對話 | [first-run.md](first-run.md) |
| 要改合約、加功能、跑 CI | [developer.md](developer.md) |
| 只想讀產品設計 | [../product/DOCUMENT-MAP.md](../product/DOCUMENT-MAP.md) |

## 最短成功路徑（Windows）

1. 安裝 JDK 17 與 Android SDK（platform 36、build-tools 36.0.0、NDK 28.2.13676358）。
2. Clone 本倉庫。
3. 複製 `local.properties.example` 為 `local.properties`，填 `sdk.dir`。
4. 在倉庫根目錄執行：

```powershell
.\gradlew.bat :android:app-ui:assembleDebug
```

5. `adb install` 產生的 debug APK，打開 App 走 Onboarding。

完整命令與常見錯誤見 [build.md](build.md)。

## 會產生什麼

| 產物 | Gradle 任務 | 套件 |
|---|---|---|
| 主 App debug APK | `:android:app-ui:assembleDebug` | `com.omnillm.debug` |
| 主 App release APK（未簽名亦可） | `:android:app-ui:assembleRelease` | `com.omnillm` |
| Companion debug APK | `:android:companion-sandbox:assembleDebug` | `com.omnillm.companion` |

Companion **不是**可選外掛那麼簡單：未受信任加速推理依法必須走不同 UID。一般本機聊天（llama.cpp 受信任路徑）可以先只裝主 App。

## 不要做的事

- 不要提交 `local.properties`、`keystore.properties`、`.jks`。
- 不要把 `Downloads\OmniLLM_Build\gradle-home-mh` 或任何 `build/` 拷進 git。
- 不要把 `.gguf` 模型權重提交進倉庫。
- 不要在沒有資格證據時，把引擎標成 `SUPPORTED`。
