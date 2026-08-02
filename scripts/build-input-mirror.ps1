$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$NativeDir = Join-Path $Root "android/app/src/main/native"
# Every .c in the directory: the daemon is one binary split across a few translation units, so
# adding a module means dropping a file in, not editing this script.
$Sources = @(Get-ChildItem -Path $NativeDir -Filter *.c | ForEach-Object { $_.FullName })
if ($Sources.Count -eq 0) {
    throw "No .c sources found in $NativeDir"
}
$AssetDir = Join-Path $Root "android/app/src/main/assets/input_mirror"
$Output = Join-Path $AssetDir "input_mirror"

if (-not $env:ANDROID_NDK_HOME) {
    throw "ANDROID_NDK_HOME is not set. Example: `$env:ANDROID_NDK_HOME=`"$env:LOCALAPPDATA\Android\Sdk\ndk\26.3.11579264`""
}

$Clang = Join-Path $env:ANDROID_NDK_HOME "toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android23-clang.cmd"
if (-not (Test-Path $Clang)) {
    throw "Could not find clang at $Clang"
}

New-Item -ItemType Directory -Force -Path $AssetDir | Out-Null

& $Clang -O3 -Wall -Wextra -std=c11 -D_GNU_SOURCE @Sources -o $Output
if ($LASTEXITCODE -ne 0) {
    throw "NDK build failed with exit code $LASTEXITCODE"
}

Write-Host "Built $Output"
