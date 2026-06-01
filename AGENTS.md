# AGENTS

## Identity mismatch (easy to break)
- Android package/app id is `com.odininputmirror` (`android/app/build.gradle`).
- RN main component name is `DockingEnhancer` (`app.json`, `MainActivity.kt`, `index.js`).
- Do not rename one side without the other; package id and RN component name are intentionally different.

## Repo layout that matters
- This is a mixed RN + Android multi-module project.
- RN entrypoints: `index.js` -> `App.tsx`.
- RN UI/state is now split into `App.tsx` (presentation shell) + `src/mirror/*` (hook/client/types) + `src/theme/tokens.ts`.
- Android modules: `:app` (RN bridge + supervisor service), `:data` (Android/root/process impl), `:domain` (pure Kotlin use cases + interfaces).
- Dependency direction is `app -> data -> domain` (`android/app/build.gradle`, `android/data/build.gradle`).

## Commands (source of truth: `package.json`, Gradle files)
- Install deps: `npm install`
- Run app on Android: `npm run android`
- Start Metro: `npm start`
- Build native mirror binary: `npm run build:input-mirror`
- Domain unit tests only: from `android/`, run `./gradlew :domain:test`
- TS typecheck (no npm script exists): `npx tsc --noEmit`

## Native binary flow (non-obvious)
- Core mirroring is native C: `android/app/src/main/native/input_mirror.c`.
- Build script `scripts/build-input-mirror.ps1` requires `ANDROID_NDK_HOME` and compiles with `aarch64-linux-android23-clang`.
- Output must be `android/app/src/main/assets/input_mirror/input_mirror` (tracked intentionally via `.gitignore` exceptions).
- At runtime Kotlin copies that asset to app files dir and executes it via `su` (`RootMirrorProcessRepository`).

## Runtime/ops constraints
- Root is required; mirroring writes directly to `/dev/input/eventX`.
- Event node paths are unstable across reconnects/reboots; restart logic resolves by GUID first, then path (`RestartMirrorIfNeededUseCase`).
- Auto-restart is handled by foreground service `InputMirrorSupervisorService` and only runs when `autoRestart` + `expectedRunning` are true.
- Liveness is heartbeat/pid based in data layer (`RootMirrorProcessRepository` + process files), not just a simple process-name check.
- Current expected behavior: toggling any of the 3 UI checks (`homeAsBack`, `comboHoldKillApp`, `autoRestart`) must only persist settings; mirror starts only via `startMirror`.

## Known install quirk on Odin
- `npm run android` may fail on install with `Failed to parse APK file` / `avc ... Permission denied` even when build succeeds.
- Fallback install command: `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`.

## Agent-local files
- `.agents/` and `skills-lock.json` are intentionally gitignored; do not re-add them to tracking.

## Existing instruction/config files
- No `opencode.json`, `.cursor` rules, or Copilot instructions are present in this repo.
