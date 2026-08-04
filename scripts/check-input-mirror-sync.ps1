# Verify the committed daemon binary is the one the committed sources produce.
#
# The daemon is not built by Gradle. It is compiled out of band and its ELF is checked into the repo
# as an app asset, which means the code a reviewer reads and the code that ships are only related by
# convention — AGENTS.md and docs/TECHNICAL.md both have to ask for it in prose. This is the
# mechanical half of that promise, and the closest thing to CI a project that needs a handheld to
# test can have.
#
# Two checks, because they fail differently:
#   1. The binary matches its recorded hash. Catches a binary edited, truncated, or replaced.
#   2. No source is newer than the binary. Catches the real mistake: a .c change committed without
#      rebuilding, which ships behaviour nobody has seen.
#
# Exit 0 when in sync, 1 when not.

$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$NativeDir = Join-Path $Root "android/app/src/main/native"
$Binary = Join-Path $Root "android/app/src/main/assets/input_mirror/input_mirror"
$HashFile = "$Binary.sha256"

$problems = @()

if (-not (Test-Path $Binary)) {
    $problems += "the daemon binary is missing: $Binary"
} elseif (-not (Test-Path $HashFile)) {
    $problems += "no recorded hash beside the binary. Run scripts/build-input-mirror.ps1."
} else {
    $recorded = (Get-Content $HashFile -Raw).Trim().ToLower()
    $actual = (Get-FileHash -Algorithm SHA256 -Path $Binary).Hash.ToLower()
    if ($recorded -ne $actual) {
        $problems += "the binary does not match its recorded hash.`n    recorded $recorded`n    actual   $actual"
    }
}

if (Test-Path $Binary) {
    $builtAt = (Get-Item $Binary).LastWriteTimeUtc
    $stale = Get-ChildItem -Path $NativeDir -Include *.c, *.h -Recurse |
        Where-Object { $_.LastWriteTimeUtc -gt $builtAt }
    if ($stale) {
        $names = ($stale | ForEach-Object { $_.Name }) -join ", "
        $problems += "these sources are newer than the committed binary: $names"
    }
}

if ($problems.Count -gt 0) {
    Write-Host "input_mirror is OUT OF SYNC with its sources:" -ForegroundColor Red
    foreach ($p in $problems) { Write-Host "  - $p" -ForegroundColor Red }
    Write-Host ""
    Write-Host "Rebuild and stage the result in the SAME commit as the source change:"
    Write-Host "  .\scripts\build-input-mirror.ps1"
    exit 1
}

Write-Host "input_mirror is in sync with its sources." -ForegroundColor Green
exit 0
