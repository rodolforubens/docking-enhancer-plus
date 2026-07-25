# Builds the e2e harness and pushes it to the connected device.
#
# The suite runs ON the handheld (it needs the real evdev stack and the real PServerBinder), so both
# the synthetic gamepad and the runner have to live there. Everything lands in /data/local/tmp,
# which `shell` owns and can execute from.

$ErrorActionPreference = "Stop"

$SkillRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$Source = Join-Path $SkillRoot "scripts/vgamepad.c"
$OutDir = Join-Path $SkillRoot "build"
$Output = Join-Path $OutDir "vgamepad"
$Runner = Join-Path $SkillRoot "scripts/e2e.sh"

if (-not $env:ANDROID_NDK_HOME) {
    $Candidate = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\ndk" -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($null -eq $Candidate) {
        throw "ANDROID_NDK_HOME is not set and no NDK was found under $env:LOCALAPPDATA\Android\Sdk\ndk"
    }
    $env:ANDROID_NDK_HOME = $Candidate.FullName
    Write-Host "Using NDK $($Candidate.Name)"
}

$Clang = Join-Path $env:ANDROID_NDK_HOME "toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android23-clang.cmd"
if (-not (Test-Path $Clang)) {
    throw "Could not find clang at $Clang"
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
& $Clang -O2 -Wall -Wextra -std=c11 -D_GNU_SOURCE $Source -o $Output
if ($LASTEXITCODE -ne 0) {
    throw "vgamepad build failed with exit code $LASTEXITCODE"
}

adb push $Output /data/local/tmp/vgamepad | Out-Null
adb push $Runner /data/local/tmp/e2e.sh | Out-Null
adb shell chmod 755 /data/local/tmp/vgamepad /data/local/tmp/e2e.sh

Write-Host "Harness ready on device: /data/local/tmp/{vgamepad,e2e.sh}"
