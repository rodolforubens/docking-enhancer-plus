# Virtual Mouse Mode Design

Date: 2026-07-09

## Goal

Add a virtual mouse to Docking Enhancer, like the one AYN ships on the Odin 2: while the mirror is
running, a controller combo turns the external controller into a mouse (moving a real Android
cursor) instead of a gamepad, and toggles back. This lets a docked user navigate Android UI that a
gamepad can't reach, without a touchscreen.

A throwaway spike already validated the hard unknown on-device (Odin2, July 2026): a self-created
uinput pointer, launched with no root via PServer, produces a visible, moving Android cursor from a
controller stick. The spike also surfaced the key constraint this design must solve — the mirror
holds an exclusive `EVIOCGRAB` on the source controller, so a second process reading the same node
gets no events. This feature therefore folds the mouse into the mirror rather than running a
separate daemon.

## Current Context

Android multi-module project, dependency direction `app -> data -> domain`. No root: privileged
commands run through the stock firmware's PServerBinder service (`PServerShell`).

- Core mirroring is native C in `android/app/src/main/native/input_mirror.c`. It `EVIOCGRAB`s the
  source controller, then forwards each event to an existing target evdev node
  (`/dev/input/eventX`). It already handles a Home-as-Back rewrite and a `Select+Start` hold-3s
  "close app" combo, catches SIGHUP, and writes its own pid/heartbeat files.
- The daemon is launched foreground inside a backgrounded `sh` via `MirrorShell.launchDaemon`
  (PServer backend) from `RootMirrorProcessRepository`.
- Boolean mirror options (`homeAsBack`, `comboHoldKillApp`) follow a fixed pattern: a persisted
  `MirrorSettings` flag → a `Set…EnabledUseCase` → surfaced in `MirrorStatus` → rendered as a
  `SettingRow` in `MirrorScreen`, toggled by a `MirrorViewModel` method that **only persists** the
  setting. The flag reaches the binary as a CLI argument built in `RootMirrorProcessRepository`.
  It takes effect on the next mirror (re)start driven by the supervisor; the UI switches are gated
  (disabled) while auto-mirror is enabled.
- The pservice SELinux domain can open `/dev/uinput` O_RDWR (validated); the device is SELinux
  permissive.

## User-Approved Behavior

- The virtual mouse is a **mode of the mirror**, not a separate daemon: one `EVIOCGRAB`, one
  process. `input_mirror` alternates between forwarding to the target (gamepad mode) and emitting
  to its own uinput mouse (mouse mode).
- **Activation gesture:** `Select + R3` (BTN_SELECT + BTN_THUMBR) held ~0.5s toggles mouse mode
  on/off. Distinct from the `Select + Start` hold-3s close-app combo (different second button).
- **Mapping while in mouse mode:**
  - Left stick (`ABS_X`/`ABS_Y`) → cursor motion (`REL_X`/`REL_Y`).
  - Right stick → vertical scroll (`REL_WHEEL`).
  - A (`BTN_SOUTH`) → left click (`BTN_LEFT`).
  - B (`BTN_EAST`) → right click (`BTN_RIGHT`).
- **Governance:** an opt-in persisted setting *"Virtual mouse (Select+R3)"* in the UI, alongside
  the other combos. When off, the combo does nothing (the `--virtual-mouse` flag is not passed and
  the mouse code stays inert). When on, the combo is armed.
- **Mode is runtime, never persisted:** the mirror always starts in gamepad mode.
- **Clean transition:** entering mouse mode releases everything currently held on the target
  (buttons up, sticks to neutral, including the Select/R3 of the activating combo) so the game sees
  no stuck inputs while events stop being forwarded. Leaving mouse mode releases any held mouse
  buttons.
- The mouse mode only exists while the mirror runs. The setting takes effect on the next mirror
  (re)start, consistent with `homeAsBack`/`comboHoldKillApp`.

## Native changes (`input_mirror.c`)

- **New CLI flag `--virtual-mouse`.** When present, the daemon creates a uinput pointer at startup
  (`EV_REL`: `REL_X`, `REL_Y`, `REL_WHEEL`; `EV_KEY`: `BTN_LEFT`, `BTN_RIGHT`; `UI_DEV_SETUP` +
  `UI_DEV_CREATE`) and keeps it for the session, destroying it (`UI_DEV_DESTROY`) on exit. Without
  the flag, none of the mouse code path runs (current behavior unchanged).
- **New loop state:** `mouse_mode` (starts 0), `thumbr_pressed` (added alongside the existing
  `select_pressed`/`start_pressed`), raw left/right stick positions, and sub-pixel residual
  accumulators (deadzone + squared response + carry, ported from the spike).
- **Axis detection at startup** (once, via `EVIOCGABS`): left stick is `ABS_X`/`ABS_Y` (universal).
  For the right stick (scroll), probe `ABS_RX`/`ABS_RY`; if absent, fall back to `ABS_Z`/`ABS_RZ`
  (e.g. the 8BitDo exposes the right stick as `ABS_Z`/`ABS_RZ`). Store min/center/half-range for
  each used axis to normalize deflection to [-1, 1].
- **Poll cadence:** the existing `poll()` timeout becomes ~12ms while `mouse_mode` is on (to emit
  proportional per-frame motion); outside mouse mode the current 1000ms / 50ms-during-kill-combo
  logic is unchanged. Heartbeat writing (~1s) is unaffected.
- **Event routing per iteration:**
  - Always detect the `Select+R3` toggle combo, the `Select+Start` kill-app combo, and Home-as-Back
    (as today), independent of mode.
  - `mouse_mode == 0` → `write_full` to the target with the existing Nintendo face-swap logic.
  - `mouse_mode == 1` → translate to the uinput mouse (left stick → REL motion on the frame timer,
    right stick → `REL_WHEEL`, A/B → `BTN_LEFT`/`BTN_RIGHT`); do **not** write to the target.
- **Toggle handling:** when the `Select+R3` hold threshold (~0.5s) is met, flip `mouse_mode`, mark a
  `triggered` guard (no repeat until release), and run the clean transition (release held target
  inputs when entering mouse mode, including Select/R3; release held mouse buttons when leaving).
- This replaces the spike's standalone `virtual_mouse.c`.

## Kotlin changes

Mirror the `homeAsBack` pattern end to end.

**domain:**
- `MirrorSettings`: add `virtualMouse: Boolean = false`.
- `MirrorStartRequest`: add `virtualMouse: Boolean`.
- `MirrorStatus`: add `virtualMouse: Boolean`.
- New `SetVirtualMouseEnabledUseCase` (clone of `SetHomeAsBackEnabledUseCase`).
- `StartMirrorUseCase` propagates the flag from settings into the request; `GetMirrorStatusUseCase`
  surfaces it from settings.

**data:**
- `InputMirrorConstants`: add `KEY_VIRTUAL_MOUSE`.
- `AndroidMirrorSettingsRepository`: persist/read the flag.
- `RootMirrorProcessRepository.start()`: append `--virtual-mouse` when `request.virtualMouse`.
- `InputMirrorGraph`: instantiate `setVirtualMouseEnabled`.

**app (UI):**
- `MirrorUiState`: add `virtualMouse: Boolean`.
- `MirrorViewModel`: `toggleVirtualMouse()` (clone of `toggleHomeAsBack` — only persists, honors the
  `autoMirrorEnabled || busy` gate); `applyStatus` reads `status.virtualMouse`.
- `MirrorScreen`: a new `SettingRow` *"Virtual mouse (Select+R3)"* describing the mapping, with the
  same gate as the other combos.

## Spike cleanup

Remove the spike scaffolding, replaced by the integrated feature:
- `android/app/src/main/native/virtual_mouse.c`
- `scripts/build-virtual-mouse.ps1`
- `android/data/.../VirtualMouseSpike.kt` and its `InputMirrorGraph` wiring
- Spike code in `MirrorViewModel` (`virtualMouseOn`, spike `toggleVirtualMouse`,
  `refreshVirtualMouse`) and the spike `SettingRow` in `MirrorScreen`
- The two `.gitignore` exceptions and the `assets/virtual_mouse/` binary
- Revert `FORCE_DOCK_MODE_FOR_DEV` to `false` in `app/build.gradle` (kept true only for local dev,
  out of the feature commit)

## Testing

- **Domain (unit), extend `MirrorUseCaseTest`:** `SetVirtualMouseEnabledUseCase` persists the flag;
  `StartMirrorUseCase` propagates it into the start request; `GetMirrorStatusUseCase` reflects it.
- **Native — on-device manual checklist (Odin 2):**
  1. Flag off: mirror behaves exactly as today (regression).
  2. `Select+R3` toggles mouse mode; left stick moves the cursor, right stick scrolls, A/B click.
  3. Clean passthrough: returning to gamepad leaves no stuck inputs (Select/R3/sticks neutral).
  4. The `Select+Start` hold-3s close-app combo still works independently.

## Non-Goals (YAGNI)

- No standalone mouse without the mirror running.
- No mouse acceleration curves/sensitivity settings beyond the fixed deadzone + squared response.
- No horizontal scroll, middle click, or D-pad-as-arrows in this iteration.
- No haptic/visual feedback for mode changes beyond the cursor itself appearing/moving.
