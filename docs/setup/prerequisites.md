# 環境需求

版本鎖在 `gradle/libs.versions.toml`。請對齊下表，不要自行升到「比較新的應該也可以」。

## 必要

| 工具 | 版本 | 用途 |
|---|---|---|
| Git | 2.40+ | clone／worktree |
| JDK | **17** | AGP 9.x 最低／預設 |
| Android SDK | platform **36** | 編譯與 target |
| Android Build-Tools | **36.0.0** | 打包 |
| Android NDK | **28.2.13676358** | JNI／llama.cpp、16 KB page-size |
| CMake | **3.22.1**（SDK 套件即可） | native |
| Python | 3.10+ | 合約產生、產品包驗證、CI 腳本 |
| adb | 隨 platform-tools | 安裝 APK |

建議同時安裝 Android Studio（Narwhal / 對應 AGP 9.3 的穩定版），方便 SDK Manager 對齊套件。指令列只用 SDK 也可以。

## 建議

| 工具 | 用途 |
|---|---|
| Windows Terminal / PowerShell 7 | 跑 `gradlew.bat`、`local_ci.ps1` |
| 一台 API 28+ 的 64-bit 裝置或 Pixel 模擬器 | 第一次推理 |
| 至少 16 GB RAM 的開發機 | 多模組 + NDK 連結 |

## Windows 安裝提示

### JDK 17

確認：

```powershell
java -version
```

應看到 `17.x`。若系統預設不是 17，在開建置的終端機設定：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
```

路徑依你的安裝位置調整。

### Android SDK

預設位置通常是：

```text
C:\Users\<you>\AppData\Local\Android\Sdk
```

用 SDK Manager 勾選：

- Android 16.0（API 36）Platform
- SDK Build-Tools 36.0.0
- NDK 28.2.13676358
- CMake 3.22.1
- Android SDK Platform-Tools

### Python

```powershell
python --version
python -m pip install -r tools\codegen\requirements.txt
```

目前合約工具只需要 `PyYAML==6.0.3`。

### 裝置

```powershell
adb devices
```

應列出 `device`。若只有 `unauthorized`，在手機上允許除錯。

## 磁碟與時間

第一次 Gradle 同步會下載依賴，可能數 GB。llama.cpp 為 vendored 原始碼，不需再 clone 上游。完整 `:android:app-ui:assembleDebug`（含 native）在乾淨機器上可能需要數十分鐘；之後是增量編譯。
