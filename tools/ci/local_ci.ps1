# OmniLLM local CI parity with .github/workflows/ci.yml (Windows PowerShell)
#
# Usage (from repo root):
#   powershell -ExecutionPolicy Bypass -File tools/ci/local_ci.ps1
#   .\tools\ci\local_ci.ps1
#   .\tools\ci\local_ci.ps1 -SkipAssemble
#   .\tools\ci\local_ci.ps1 -SkipLint
#   .\tools\ci\local_ci.ps1 -SkipRelease
#
# Requirements: JDK 17+, Python 3 + tools/codegen/requirements.txt,
#   Android SDK (for assemble / lint) with platform 36 + build-tools 36.0.0.
# Exit non-zero on any gate failure (fail closed).

[CmdletBinding()]
param(
    [switch]$SkipAssemble,
    [switch]$SkipLint,
    [switch]$SkipRelease
)

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $Root

function Assert-LastExit {
    param([string]$Step)
    if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) {
        throw "FAIL at step: $Step (exit $LASTEXITCODE)"
    }
}

function Invoke-Gradlew {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs)
    if (Test-Path ".\gradlew.bat") {
        & .\gradlew.bat @GradleArgs
        Assert-LastExit ("gradlew " + ($GradleArgs -join " "))
    } elseif (Test-Path ".\gradlew") {
        & bash .\gradlew @GradleArgs
        Assert-LastExit ("gradlew " + ($GradleArgs -join " "))
    } else {
        throw "gradlew missing - run: gradle wrapper --gradle-version 9.5.0"
    }
}

# Resolve Python executable path (Gradle -Pomnillm.python needs a single binary)
$Python = $env:OMNILLM_PYTHON
if (-not $Python) {
    foreach ($cand in @("python", "python3", "py")) {
        $cmd = Get-Command $cand -ErrorAction SilentlyContinue
        if ($cmd) {
            if ($cand -eq "py") {
                $Python = (& py -3 -c "import sys; print(sys.executable)").Trim()
            } else {
                try {
                    $Python = (& $cand -c "import sys; print(sys.executable)").Trim()
                } catch {
                    $Python = $cmd.Source
                }
            }
            break
        }
    }
}
if (-not $Python) {
    throw "Python 3 required for contract codegen / 16 KB gates"
}

function Invoke-Python {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    & $Python @Arguments
    Assert-LastExit ("python " + ($Arguments -join " "))
}

Write-Host "==> [local_ci] repo: $Root"
Write-Host "==> [local_ci] python: $Python"

Write-Host "==> [0/11] Dependency edges (INV-001 / engines→data)"
Invoke-Python @("tools/ci/check_dependency_edges.py")

Write-Host "==> [1/11] Specs authority present"
$required = @(
    "specs/canonical-types.yaml",
    "specs/error-catalog.yaml",
    "specs/state-machines.yaml",
    "specs/access-control-catalog.yaml",
    "specs/capability-catalog.yaml",
    "specs/capability-availability-matrix.yaml",
    "specs/engine-qualification-status.yaml",
    "specs/openapi/omnillm.openapi.yaml",
    "specs/aidl/omnillm-aidl.yaml",
    "specs/platform-policy-register.yaml",
    "specs/database/omnillm-schema.sql"
)
foreach ($f in $required) {
    if (-not (Test-Path $f)) { throw "MISSING: $f" }
}

Write-Host "==> [2/11] Install codegen deps (idempotent)"
Invoke-Python -Arguments @("-m", "pip", "install", "-q", "-r", "tools/codegen/requirements.txt")

Write-Host "==> [3/11] Contract drift gate (BEFORE regenerate)"
Invoke-Gradlew checkContractDrift "-Pomnillm.python=$Python" --stacktrace

Write-Host "==> [4/11] generateContracts + clean generated tree"
Invoke-Gradlew generateContracts "-Pomnillm.python=$Python" --stacktrace

$genPaths = @(
    "core/canonical/src/main/kotlin/com/omnillm/core/canonical/generated",
    "core/state/src/main/kotlin/com/omnillm/core/state/generated",
    "core/errors/src/main/kotlin/com/omnillm/core/errors/generated"
)
& git diff --quiet -- @genPaths
if ($LASTEXITCODE -ne 0) {
    Write-Host "FAIL: generated sources dirty after generateContracts" -ForegroundColor Red
    & git --no-pager diff -- @genPaths | Select-Object -First 200
    exit 1
}

Write-Host "==> [5/12] AIDL drift gate (API-20)"
Invoke-Gradlew checkAidlDrift "-Pomnillm.python=$Python" --stacktrace

Write-Host "==> [6/12] Module dependency boundary gate (INV-001 / ADR-010)"
Invoke-Gradlew checkModuleDependencyRules "-Pomnillm.python=$Python" --stacktrace

Write-Host "==> [7/12] Dependency edges (Gradle task + script)"
Invoke-Gradlew checkDependencyEdges "-Pomnillm.python=$Python" --stacktrace

Write-Host "==> [8/12] Unit tests (JVM + Android testDebugUnitTest) - fail closed"
Invoke-Gradlew test "-Pomnillm.python=$Python" --stacktrace --continue

Write-Host "==> [9/12] Root check (drift + native 16 KB + dependency rules)"
Invoke-Gradlew check "-Pomnillm.python=$Python" --stacktrace

if (-not $SkipLint) {
    Write-Host "==> [10/12] Android lint (app modules); detekt intentionally skipped"
    Invoke-Gradlew `
        :android:app-ui:lintDebug `
        :android:companion-sandbox:lintDebug `
        "-Pomnillm.python=$Python" --stacktrace
} else {
    Write-Host "==> [10/12] Android lint SKIPPED (-SkipLint)"
}

if (-not $SkipAssemble) {
    Write-Host "==> [11/12] assembleDebug (+ release unless -SkipRelease)"
    Invoke-Gradlew `
        :android:app-ui:assembleDebug `
        :android:companion-sandbox:assembleDebug `
        "-Pomnillm.python=$Python" --stacktrace
    if (-not $SkipRelease) {
        Invoke-Gradlew `
            :android:app-ui:assembleRelease `
            :android:companion-sandbox:assembleRelease `
            "-Pomnillm.python=$Python" --stacktrace
    }

    Write-Host "==> [12/12] Native 16 KB APK zip-align"
    Invoke-Python -Arguments @("tools/ci/check_elf_16kb_alignment.py", "--min-align", "16384")

    $apkGlobs = @(
        "android/app-ui/build/outputs/apk/debug/*.apk",
        "android/app-ui/build/outputs/apk/release/*.apk",
        "android/companion-sandbox/build/outputs/apk/debug/*.apk",
        "android/companion-sandbox/build/outputs/apk/release/*.apk"
    )
    $apks = @()
    foreach ($g in $apkGlobs) {
        $apks += @(Get-Item $g -ErrorAction SilentlyContinue)
    }
    if ($apks.Count -eq 0) {
        throw "FAIL: no APKs after assemble"
    }
    foreach ($apk in $apks) {
        Write-Host "  zip-align: $($apk.FullName)"
        Invoke-Python -Arguments @("tools/ci/check_apk_16kb_zipalign.py", $apk.FullName)
    }

    Write-Host "==> [13/13] D10 packaged-APK cleanliness (jansi/sqlite-jdbc junk + C-07 natives)"
    $relApks = @(Get-Item "android/app-ui/build/outputs/apk/release/*.apk" -ErrorAction SilentlyContinue)
    if ($relApks.Count -eq 0) {
        throw "FAIL: no app-ui release APK for verify_apk_clean"
    }
    foreach ($apk in $relApks) {
        Invoke-Python -Arguments @("tools/ci/verify_apk_clean.py", $apk.FullName)
    }
} else {
    Write-Host "==> [10-13/13] assemble + APK gates SKIPPED (-SkipAssemble)"
    Invoke-Python -Arguments @("tools/ci/check_elf_16kb_alignment.py", "--min-align", "16384")
}

Write-Host ""
Write-Host "local_ci: OK (all enabled gates passed)" -ForegroundColor Green
