#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Automated Build, v2+v3 APK Signing, and GitHub Push Script for Zai-Bridge (com.ziauddin.zaibridge).
.DESCRIPTION
    1. Executes a clean Gradle debug/universal build across all 4 ABIs (arm64-v8a, armeabi-v7a, x86_64, x86).
    2. Aligns the produced APK on 4-byte page boundaries with zipalign (-p -f 4).
    3. Signs the APK with APK Signature Scheme v2 and v3 using apksigner and outputs Zai-Bridge-Universal.apk.
    4. Stages, commits, and pushes the updated source tree and universal binary to GitHub.
#>

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Zai-Bridge v3.0.0 — Automated Build, Sign & Push Pipeline" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# 1. Locate Android SDK Build-Tools (zipalign & apksigner)
$SdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "/opt/android/sdk" }
$BuildToolsDir = Get-ChildItem -Path (Join-Path $SdkRoot "build-tools") -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName "apksigner") -or Test-Path (Join-Path $_.FullName "apksigner.bat") } |
    Sort-Object Name -Descending |
    Select-Object -First 1

# 2. Run Gradle Clean & AssembleDebug
Write-Host "`n[1/4] Building Universal APK with Gradle..." -ForegroundColor Yellow
if ($IsWindows) {
    & .\gradlew.bat clean assembleDebug --stacktrace
} else {
    chmod +x ./gradlew
    & ./gradlew clean assembleDebug --stacktrace
}
if ($LASTEXITCODE -ne 0) {
    throw "Gradle assembleDebug failed with exit code $LASTEXITCODE"
}

$UnsignedOrDebugApk = Join-Path $RepoRoot "app/build/outputs/apk/debug/app-debug.apk"
if (-not (Test-Path $UnsignedOrDebugApk)) {
    throw "Expected APK not found at: $UnsignedOrDebugApk"
}

# 3. Zipalign & Sign with v2 + v3 APK Signature Schemes
$AlignedApk = Join-Path $RepoRoot "app/build/outputs/apk/debug/app-debug-aligned.apk"
$FinalUniversalApk = Join-Path $RepoRoot "Zai-Bridge-Universal.apk"

if ($BuildToolsDir) {
    Write-Host "`n[2/4] Running zipalign (-p -f 4) and apksigner (v2 + v3)..." -ForegroundColor Yellow
    $ZipAlign = Join-Path $BuildToolsDir.FullName ($IsWindows ? "zipalign.exe" : "zipalign")
    $ApkSigner = Join-Path $BuildToolsDir.FullName ($IsWindows ? "apksigner.bat" : "apksigner")

    & $ZipAlign -p -f 4 $UnsignedOrDebugApk $AlignedApk
    if ($LASTEXITCODE -ne 0) {
        throw "zipalign failed with exit code $LASTEXITCODE"
    }

    $KeystorePath = Join-Path $RepoRoot "app/build/debug.keystore"
    if (-not (Test-Path $KeystorePath)) {
        & keytool -genkeypair -v -keystore $KeystorePath -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
    }

    & $ApkSigner sign --ks $KeystorePath --ks-pass pass:android --key-pass pass:android --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out $FinalUniversalApk $AlignedApk
    if ($LASTEXITCODE -ne 0) {
        throw "apksigner sign failed with exit code $LASTEXITCODE"
    }

    & $ApkSigner verify --verbose $FinalUniversalApk
    if ($LASTEXITCODE -ne 0) {
        throw "apksigner verify failed with exit code $LASTEXITCODE"
    }
} else {
    Write-Host "`n[2/4] Copying signed debug APK to Zai-Bridge-Universal.apk..." -ForegroundColor Yellow
    Copy-Item -Path $UnsignedOrDebugApk -Destination $FinalUniversalApk -Force
}

$ApkSizeMB = [math]::Round((Get-Item $FinalUniversalApk).Length / 1MB, 2)
Write-Host "Produced Zai-Bridge-Universal.apk ($ApkSizeMB MB)" -ForegroundColor Green

# 4. Git Commit & Push (if inside a Git repository)
if (Test-Path (Join-Path $RepoRoot ".git")) {
    Write-Host "`n[3/4] Staging and committing build artifacts..." -ForegroundColor Yellow
    & git add -A
    $GitStatus = & git status --porcelain
    if ($GitStatus) {
        & git commit -m "release(v3.0.0): complete Zai-Bridge universal 32-to-64-bit container & Zai-Scale Ultra engine"
        Write-Host "`n[4/4] Pushing to origin/main..." -ForegroundColor Yellow
        & git push origin HEAD:main
    } else {
        Write-Host "Working tree clean; nothing new to commit." -ForegroundColor Green
    }
} else {
    Write-Host "`n[3/4] Notice: .git directory not present in this workspace snapshot. Ready for manual git push." -ForegroundColor Cyan
}

Write-Host "`nAll pipeline steps completed successfully!" -ForegroundColor Green
