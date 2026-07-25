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
- Domain unit tests: from `android/`, run `./gradlew :domain:test`
- Build native mirror binary: `powershell -ExecutionPolicy Bypass -File scripts/build-input-mirror.ps1`

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
