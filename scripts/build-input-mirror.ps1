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

# -Werror because the artifact this produces is COMMITTED: a warning introduced in a change is
# invisible to anyone who doesn't happen to run this script, so the build has to be the thing that
# refuses. The hardening flags are not optional for a process that runs as root holding an exclusive
# grab on the user's controller — stack canaries, fortified libc calls, and a relocation table that
# is read-only by the time main() starts.
$CFlags = @(
    "-O3", "-Wall", "-Wextra", "-Werror", "-std=c11", "-D_GNU_SOURCE",
    "-D_FORTIFY_SOURCE=2",
    "-fstack-protector-strong",
    "-fPIE",
    "-Wl,-pie",
    "-Wl,-z,relro",
    "-Wl,-z,now",
    "-Wl,-z,noexecstack"
)

& $Clang @CFlags @Sources -o $Output
if ($LASTEXITCODE -ne 0) {
    throw "NDK build failed with exit code $LASTEXITCODE"
}

# Recorded next to the binary so a reviewer can tell at a glance whether the committed artifact is
# the one these sources produce. Checked by scripts/check-input-mirror-sync.ps1.
$Hash = (Get-FileHash -Algorithm SHA256 -Path $Output).Hash.ToLower()
Set-Content -Path "$Output.sha256" -Value $Hash -Encoding ascii -NoNewline

Write-Host "Built $Output"
Write-Host "  sha256 $Hash"
