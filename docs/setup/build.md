# 建置

Gradle 根目錄是倉庫根目錄。下列命令都在 `OmniLLM/` 下執行。

## 1. 取得原始碼

```powershell
git clone https://github.com/davidlueng986-alt/OmniLLM.git
cd OmniLLM
```

若你已經用 git worktree（本機開發建議如此，可與 `Downloads\OmniLLM_Build` 的髒工作區隔離）：

```powershell
git -C C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android worktree list
```

乾淨的出貨 worktree 預設在 `C:\Users\daive\OmniLLM`，分支 `pack/github-ship`。

## 2. 指向 Android SDK

```powershell
Copy-Item local.properties.example local.properties
```

編輯 `local.properties`（此檔已被 gitignore）：

```properties
sdk.dir=C:\\Users\\daive\\AppData\\Local\\Android\\Sdk
```

反斜線請寫成 `\\`，或改用正斜線 `C:/Users/daive/AppData/Local/Android/Sdk`。

## 3. 組出 App

只驗證 JVM／Kotlin 能編過：

```powershell
.\gradlew.bat :core:canonical:compileKotlin
```

組 debug APK（第一次會下載 Gradle 9.5.0 與依賴）：

```powershell
.\gradlew.bat :android:app-ui:assembleDebug
```

產物：

```text
android\app-ui\build\outputs\apk\debug\app-ui-debug.apk
```

Release（未設定簽名時為 unsigned，這是允許的）：

```powershell
.\gradlew.bat :android:app-ui:assembleRelease
.\gradlew.bat :android:companion-sandbox:assembleRelease
```

列出所有模組：

```powershell
.\gradlew.bat omnillmModules
```

## 4. 測試

不需要裝置：

```powershell
.\gradlew.bat test
```

只跑 JVM 測試或 Android host 測試：

```powershell
.\gradlew.bat jvmTest
.\gradlew.bat androidUnitTest
```

與 GitHub Actions 對齊：

```powershell
.\tools\ci\local_ci.ps1
.\tools\ci\local_ci.ps1 -SkipAssemble
```

## 5. 合約與架構閘門

改了 `specs/` 之後：

```powershell
.\gradlew.bat generateContracts
.\gradlew.bat checkContractDrift
.\gradlew.bat checkModuleDependencyRules
.\gradlew.bat checkDependencyEdges
.\gradlew.bat checkNative16kb
```

`checkContractDrift` 必須在重新產生並提交 generated 檔案之後保持乾淨。CI 會 fail-closed。

## 常見問題

### `sdk.dir is missing`

沒有 `local.properties`，或路徑寫錯。Android Studio 開啟專案也會自動寫入此檔。

### 找不到 NDK 28.2.x

用 SDK Manager 安裝精確版本 `28.2.13676358`。不要只裝「最新 NDK」。

### Java 版本不是 17

`JAVA_HOME` 指向 21／11 都會讓 AGP 9.3 行為異常。先在同一終端機執行 `java -version`。

### Windows 上 `gradlew` 沒有執行權限

使用 `.\gradlew.bat`，不要用 bash 的 `./gradlew`（除非你在 Git Bash 且檔案保持 LF）。

### 編譯很慢、磁碟爆掉

不要把 `Downloads\OmniLLM_Build\gradle-home-mh` 當專案的一部分。那是本機 Gradle cache。倉庫內的 `build/` 可隨時刪除：

```powershell
.\gradlew.bat clean
```

### 我想用 Android Studio

Open 倉庫根目錄（有 `settings.gradle.kts` 的那層），不要只開 `android/app-ui`。同步完成後選 `:android:app-ui` 的 debug 變體即可 Run。

## 簽名

本機 release 預設可不簽名。若你有 keystore，建立 **未被追蹤** 的 `keystore.properties`，路徑與密碼只留在本機。倉庫與 CI 說明見 `tools/ci/README.md`。永遠不要把 keystore 或密碼提交到 git。
