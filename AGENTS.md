# AGENTS

## Stack
- Native Android (Kotlin) with Jetpack Compose + Material 3. No React Native.
- Gradle 8.11.1, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Java/JVM 17.
- Compose BOM 2024.10.01, activity-compose 1.9.3, lifecycle 2.8.7.
- No root: privileged commands run through the stock firmware's PServerBinder service (reflection + binder transact). minSdk 28, target/compileSdk 35.
- App id is `com.odininputmirror`. App label is `Docking Enhancer` (`strings.xml`).

## Repo layout that matters
- Android multi-module project under `android/`.
- `:app` — Compose UI + `MainActivity` (ComponentActivity) + `MainApplication` (plain Application; starts the supervisor only when `isPServerSupported()`) + supervisor service + boot receiver. UI lives in `android/app/src/main/java/com/odininputmirror/ui/`.
- `:data` — Android/process impl (`InputMirrorGraph`, repositories, `MirrorShell` → `PServerShell` over PServerBinder, `PServerExec`).
- `:domain` — pure Kotlin use cases + interfaces + models.
- Dependency direction is `app -> data -> domain`.
- `MirrorViewModel` (in `:app` `ui`) wraps `InputMirrorGraph`, exposes a `MirrorUiState` StateFlow, and runs the 2s/6s status poll. `MirrorScreen` renders it.

## Commands (source of truth: Gradle files)
- Build debug APK (from repo root): `.\android\gradlew.bat -p .\android :app:assembleDebug`
- Install: `adb install -r .\android\app\build\outputs\apk\debug\app-debug.apk`
- Unit tests: from `android/`, run `./gradlew :domain:test :data:testDebugUnitTest`. `:domain` is a
  plain JVM module, so its task is `test` — `:domain:testDebugUnitTest` does not exist.
- Compile without packaging (fastest sanity check): `./gradlew :app:compileDebugKotlin`
- Build native mirror binary: `powershell -ExecutionPolicy Bypass -File scripts/build-input-mirror.ps1`
- Release APK: `./gradlew :app:assembleRelease`. Left UNSIGNED unless `android/keystore.properties`
  exists — deliberate, so a missing keystore never silently ships a debug-signed build.

## Bringing the project up
- Needs JDK 17, Android SDK (compileSdk 35), and an NDK for the native binary. Build scripts read
  `ANDROID_NDK_HOME` and otherwise fall back to the newest NDK under `%LOCALAPPDATA%\Android\Sdk\ndk`.
- Needs a physical handheld exposing `PServerBinder`. **There is no emulator path**: no such service,
  no internal controller node to write into, and no external pad to grab.
- `FORCE_DOCK_MODE_FOR_DEV` in `android/app/build.gradle` makes a debug build behave as if docked, so
  the mirror runs without an external display. Set it back to `false` before committing.
- Debug and release are signed with different keys, so switching between them forces an uninstall,
  which wipes settings (`allowBackup="false"`). Expect to re-enable the toggles afterwards; only
  `autoMirrorEnabled` defaults back to true.
- From Git Bash, call adb through PowerShell. Git Bash rewrites `/data/local/tmp/...` into a Windows
  path, and the command fails with a `No such file or directory` that points nowhere near the cause.

## Native binary flow (non-obvious)
- Core mirroring is native C: `android/app/src/main/native/input_mirror.c`.
- Build script `scripts/build-input-mirror.ps1` requires `ANDROID_NDK_HOME` and compiles with `aarch64-linux-android23-clang`.
- Output must be `android/app/src/main/assets/input_mirror/input_mirror` (tracked intentionally via `.gitignore` exceptions).
- At runtime Kotlin copies that asset to app files dir and launches it via the no-root PServer shell (`RootMirrorProcessRepository` → `MirrorShell.launchDaemon`). The daemon runs foreground inside a backgrounded `sh` script — never `setsid`/inline `&` (input_mirror catches SIGHUP and would exit).

## Runtime/ops constraints
- No root. Privileged commands go through PServerBinder (`PServerShell`), which returns only the first stdout line and no exit code — reads stage output through a file, status checks use a stdout token. On devices without the service, `isPServerSupported()` is false: the UI shows an "Unsupported device" dialog and the supervisor idles.
- Mirroring writes directly to `/dev/input/eventX` (the pservice SELinux domain can open input/uinput nodes).
- Event node paths are unstable across reconnects/reboots; device resolution matches by GUID first, then path (`findSavedControllerDevice`).
- Start/stop/restart is decided every tick by `ResolveAutoMirrorDecisionUseCase`, driven from the foreground service `InputMirrorSupervisorService`; it only acts while `autoMirrorEnabled` is true and the device is docked.
- Liveness is heartbeat/pid based in data layer (`RootMirrorProcessRepository` + process files), not just a simple process-name check.
- Toggling the UI switches (`homeAsBack`, `comboHoldKillApp`, `virtualMouse`) must only persist settings; the mirror starts/stops via the supervisor and `startMirror`/`stopMirror`. If the mirror is running with different flags, the supervisor detects it (persisted `started*` snapshot vs current settings) and restarts it automatically.

## Testing
- Unit tests cover the pure logic: use cases, device resolution, settings, process/file handling.
- Everything that only breaks on real hardware — evdev grabs, `/dev/input` node lifetimes, SELinux
  labels, a root daemon outliving its app — is covered by the on-device e2e suite in
  `.claude/skills/mirror-e2e/` (tracked; see its `SKILL.md`). Run it with:
  `.\.claude\skills\mirror-e2e\scripts\build-harness.ps1` then `adb shell sh /data/local/tmp/e2e.sh`.
- The suite drives synthetic uinput controllers, so no physical pad is needed and runs are
  reproducible. It force-stops the app first; relaunch the app afterwards.
- **No CI is possible** for any of that: every meaningful test needs the handheld attached. Unit
  tests and compilation are all a machine without a device can verify.
- A change to the daemon or the hide/restore paths is not done until the e2e suite passes on-device.
  Compiling is not evidence — these are failure paths, and they only fail on hardware.

## Commit conventions
- Conventional Commits: `<type>(<scope>): <imperative summary>`, subject ≤50 chars (hard cap 72), no
  trailing period.
- Body only when the "why" is not obvious from the diff. Explain intent and consequence, not a
  file-by-file recap of what the diff already shows.
- **Never** add a `Co-Authored-By: Claude` trailer, or any other AI attribution, to a commit.
- Rebuild and commit `assets/input_mirror/input_mirror` in the SAME commit as the `input_mirror.c`
  change it was built from. That binary is tracked and shipped; letting it drift from its source
  means the repo describes one daemon and runs another.

## Device realities that cost time
- The handheld republishes any external controller under its own vendor id (`0x2020`) and deletes the
  original node. That is why the real external pad often has no `/dev` entry of its own, and why a
  synthetic pad with a foreign vendor id never keeps one.
- `getevent` labels `0x130` as `BTN_GAMEPAD`; `BTN_SOUTH` and `BTN_A` are aliases of the same code.
  Asserting on the wrong alias reads as a forwarding bug that is not there.
- The input core drops an absolute event repeating its current value, so a synthetic axis burst must
  alternate values or half of it silently vanishes.
- Bluetooth pads sleep on their own mid-session. A mirror that "stopped working" is usually that.

## Known install quirk on Odin
- Install may fail with `Failed to parse APK file` / `avc ... Permission denied` even when build succeeds.
- Fallback: `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`, then launch `com.odininputmirror/.MainActivity`.

## Agent-local files
- `.agents/` is intentionally gitignored; do not re-add it to tracking.
- Third-party skills under `.claude/skills/` stay untracked, but `skills-lock.json` IS tracked so a
  collaborator can reproduce the same tooling with `npx skills` — vendoring the skills themselves
  would mean redistributing other authors' work under this repo's licence.
- `.claude/skills/mirror-e2e/` is the exception and is tracked: it is this project's own e2e suite
  (see its `SKILL.md`), not reinstallable tooling. Its `build/` output stays ignored.
