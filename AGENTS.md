# AGENTS

## Stack
- Native Android (Kotlin) with Jetpack Compose + Material 3. No React Native.
- Gradle 8.11.1, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Java/JVM 17.
- Compose BOM 2024.10.01, activity-compose 1.9.3, lifecycle 2.8.7.
- Root via topjohnwu libsu 5.2.2 (JitPack). minSdk 28, target/compileSdk 35.
- App id is `com.odininputmirror`. App label is `Docking Enhancer` (`strings.xml`).

## Repo layout that matters
- Android multi-module project under `android/`.
- `:app` — Compose UI + `MainActivity` (ComponentActivity) + `MainApplication` (plain Application, configures libsu) + supervisor service + boot receiver. UI lives in `android/app/src/main/java/com/odininputmirror/ui/`.
- `:data` — Android/root/process impl (`InputMirrorGraph`, repositories, `Shell` wrapping libsu).
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
- At runtime Kotlin copies that asset to app files dir and executes it via libsu root shell (`RootMirrorProcessRepository`).

## Runtime/ops constraints
- Root is required; mirroring writes directly to `/dev/input/eventX`. libsu requests su on first shell command.
- Event node paths are unstable across reconnects/reboots; restart logic resolves by GUID first, then path (`RestartMirrorIfNeededUseCase`).
- Auto-restart is handled by foreground service `InputMirrorSupervisorService` and only runs when `autoRestart` + `expectedRunning` are true.
- Liveness is heartbeat/pid based in data layer (`RootMirrorProcessRepository` + process files), not just a simple process-name check.
- Toggling the UI switches (`homeAsBack`, `comboHoldKillApp`) must only persist settings; the mirror starts/stops via the supervisor and `startMirror`/`stopMirror`.

## Known install quirk on Odin
- Install may fail with `Failed to parse APK file` / `avc ... Permission denied` even when build succeeds.
- Fallback: `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`, then launch `com.odininputmirror/.MainActivity`.

## Agent-local files
- `.agents/` and `skills-lock.json` are intentionally gitignored; do not re-add them to tracking.
