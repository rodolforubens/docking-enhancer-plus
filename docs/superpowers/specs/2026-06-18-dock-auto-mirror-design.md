# Dock Auto Mirror Design

Date: 2026-06-18

## Goal

Make Docking Enhancer operate automatically for the normal docked use case: after initial setup and root permission, the Odin should mirror the first external controller to the internal Odin controls whenever an external display is connected, including after device reboot without manually opening the app.

The current low-level mirroring core stays in place. The change is focused on when the app starts/stops the mirror and how it chooses devices automatically.

## Current Context

The app is a React Native plus Android multi-module project.

Android modules follow `app -> data -> domain`:

- `:app`: React Native bridge and `InputMirrorSupervisorService`.
- `:data`: Android/root/process implementations.
- `:domain`: pure Kotlin models, repositories, and use cases.

The native mirror binary writes to `/dev/input/eventX` through `su`, so root remains required. Current auto-restart already resolves saved devices by GUID first and path second, and it only runs when `autoRestart` and `expectedRunning` are true.

## User-Approved Behavior

- Production dock condition is an external monitor/display connected to the Odin.
- A hidden internal development flag can force dock mode without physical dock/display.
- The app should select the Odin internal controller automatically as the local/target device.
- The app should select the first compatible external controller automatically as the source device.
- The external controller may be Bluetooth, USB, or dongle-based.
- If multiple external controllers are present, use the existing stable order, currently based primarily on `controllerNumber`.
- If the selected external controller disconnects and a different external controller is available, the app should attach to the new one automatically.
- If the external display disconnects, the mirror should stop.
- The automatic behavior should run after reboot without opening the app.
- Dock auto mirror is the default production behavior and must not require the user to manually press Start Mirror after every reboot.
- If root permission is not available, do not spam root prompts; show a notification asking the user to open the app to grant root.
- A persistent notification should show useful state, especially when the automatic mirror is active.

## Recommended Approach

Use a native Android supervisor as the owner of automatic behavior.

Rejected alternatives:

- React Native/UI-driven automation: simpler but cannot reliably run after boot or while the app is closed.
- Partial hybrid automation: less invasive but keeps too much manual setup and does not match the desired dock-and-play behavior.

The supervisor-based approach fits the core use case because it can run as a foreground service, start after boot, observe dock/display state, choose controllers, start/stop the root mirror, and update notifications independently of the UI.

## Architecture

### Domain

Add pure decision logic for automatic mirroring:

- Decide whether auto mirror is allowed from `dockActive` and current settings/state.
- Select the Odin internal controller as `target/local`.
- Select the first non-Odin compatible controller as `source/external`.
- Decide whether the supervisor should wait, start, keep running, restart, stop, or report root permission needed.

This logic should be unit-testable without Android framework dependencies.

### Data

Add or extend Android implementations:

- `DockStateRepository`: detects external display state using Android display APIs, primarily `DisplayManager` and non-default display presence.
- `InputDeviceRepository`: exposes enough metadata to classify controllers as internal Odin or external. It already parses `/proc/bus/input/devices` and Android `InputDevice`, so the design should extend that rather than create a parallel scanner.
- `RootMirrorProcessRepository`: remains responsible for installing/running/stopping the native binary via `su` and heartbeat/pid liveness.

### App

Evolve `InputMirrorSupervisorService` into the owner of automatic dock behavior:

- Start as a foreground service.
- Start on boot through a `BOOT_COMPLETED` receiver.
- Check external display state.
- Check connected controllers.
- Start mirror only when dock is active and both internal/external controllers are available.
- Stop mirror when dock becomes inactive.
- Restart mirror when the active external controller disappears and another external controller is available.
- Update foreground notification based on supervisor state.

React Native UI remains a control/status surface, but the primary flow no longer depends on manual controller selection.

The existing `autoRestart` setting should be folded into the new dock automation behavior instead of remaining the gate for the entire supervisor. The supervisor may still use persisted state for user options and status, but dock auto mirror should be able to run after boot without requiring a prior manual start in the same session.

## Automatic Flow

1. Android boots.
2. `BOOT_COMPLETED` receiver starts `InputMirrorSupervisorService`.
3. The service enters foreground mode with a notification.
4. The service checks whether dock mode is active.
5. If no external display is connected and the dev flag is disabled, the service stops any active mirror and waits.
6. If dock mode is active, the service scans connected controllers.
7. It selects the Odin internal controller as target/local.
8. It selects the first compatible non-Odin controller as source/external.
9. If both controllers are available and distinct, it starts the mirror.
10. If the mirror is running and the external controller changes, the service restarts the mirror with the new external controller.
11. If the external display disconnects, the service stops the mirror.
12. If `su` is unavailable or not granted, the service transitions to root-permission-needed state and shows a notification prompting the user to open the app.

## Supervisor States

- `WaitingForDock`: no external display is present; mirror is stopped.
- `WaitingForInternalController`: dock is active but the Odin internal controller is not detected.
- `WaitingForExternalController`: dock is active and internal controller exists, but no external controller is available.
- `Starting`: both controllers are available and the service is starting the mirror.
- `Active`: mirror is running.
- `Restarting`: mirror needs to restart because the process stopped or the selected external controller changed.
- `RootPermissionNeeded`: root cannot be used; the notification asks the user to open the app.
- `Error`: unexpected failure; retry with a slower interval.

## Device Selection Rules

### Internal Odin Controller

The target/local controller is the Odin internal controller. Identification should use the existing Android input and `/proc/bus/input/devices` metadata. The current code already has Odin-specific constants and avoids treating Odin vendor/product matching as a generic external match, so the implementation should build on that knowledge.

The internal controller must not be selected as the external/source controller.

### External Controller

The source/external controller is the first compatible controller that is not the Odin internal controller.

Allowed external controller transports:

- Bluetooth.
- USB cable.
- USB dongle.

If several external controllers are present, use the existing stable ordering from the device repository. If that ordering is refined later, it should remain deterministic.

If the currently mirrored external controller disconnects, the supervisor should select the new first available external controller and restart the mirror.

## Dock Detection

Production dock detection is based on external display presence.

Implementation should use Android display APIs, with `DisplayManager` as the primary mechanism. The dock is active when at least one non-default display is present and usable as an external monitor.

The design intentionally does not require USB charging or generic USB connection state. For the first version, an external monitor is sufficient and avoids false positives from chargers or USB devices.

## Development Flag

Add a hidden internal build config constant named `FORCE_DOCK_MODE_FOR_DEV`.

- `false` in normal builds: external display is required.
- `true` for local development: supervisor behaves as if dock is active without a physical dock/display.

This flag is not exposed in the React Native UI.

## Root Permission Handling

Root remains required because the native mirror binary writes directly to `/dev/input/eventX`.

The app cannot force Magisk/SuperSU to grant permanent permission. The intended user flow is:

1. The app requests root only when it needs to start the mirror.
2. The user grants root and chooses the equivalent of always allow in their root manager.
3. Future automatic starts reuse that saved permission.

If the supervisor cannot use root after boot, it should avoid aggressive retry loops and show a notification: `Open app to grant root`. The notification should open the main app so the root manager prompt can be handled by the user.

## Notifications

The foreground service notification should communicate the current state:

- `Dock mirror active` when mirroring is running.
- `Waiting for external display` when no dock/display is present.
- `Waiting for Odin controller` when the internal controller is missing.
- `Waiting for external controller` when no external controller is available.
- `Open app to grant root` when root permission is needed.
- A generic error state only for unexpected failures.

The notification is part of the product behavior because the user wants confirmation that automatic startup worked.

## Error Handling And Retry

- No dock: stop mirror and wait with a low-frequency interval.
- Missing controller: wait with a moderate interval.
- Mirror heartbeat stale: restart with throttling.
- External controller changed: restart with throttling.
- Root unavailable: transition to `RootPermissionNeeded` and avoid repeated rapid prompts.
- Unexpected errors: keep the foreground service alive and retry slowly.

The existing supervisor timing can be reused initially, but restart attempts should remain throttled to avoid CPU/battery waste during unstable reconnects.

## UI Impact

> Written while the UI was still React Native. The app has since been rebuilt in Jetpack Compose and
> no React Native remains; the intent below carried over unchanged, only the toolkit did not. Left as
> written because this is a dated design note, not a maintained document.

The UI should no longer be required for the main dock-and-play path.

Expected UI evolution:

- Show current automatic state when opened.
- Show detected internal and external controllers.
- Keep settings such as `homeAsBack` and `comboHoldKillApp` available.
- Avoid requiring manual local/external selection for normal automatic use.

Manual start/stop can remain if useful for debugging, but it should not conflict with automatic dock behavior.

## Testing Strategy

### Unit Tests

Add domain tests for:

- No dock stops or waits without starting.
- Dock active with no internal controller waits.
- Dock active with no external controller waits.
- Dock active with both controllers starts mirror.
- First external controller is selected.
- Internal Odin controller is never selected as external.
- Existing external disconnects and new external is selected.
- Root failure produces `RootPermissionNeeded` without rapid retry behavior.

### Manual Verification On Odin

Verify these flows on hardware:

- Boot Odin without opening the app, connect dock/display, connect external controller, and confirm mirror starts.
- Connect display first, then external controller.
- Connect external controller first, then display.
- Disconnect external controller and connect a different one; mirror should reattach.
- Disconnect external display; mirror should stop.
- Reboot after root permission has been granted permanently; mirror should start automatically when dock/display and external controller are available.
- Reboot without root permission granted; notification should ask to open app to grant root.

## Scope Boundaries

In scope:

- Automatic dock/display-based supervisor behavior.
- Internal Odin default target selection.
- First external controller source selection.
- Boot startup.
- Hidden development dock-force flag.
- State notification updates.
- Domain tests for automatic decisions.

Out of scope:

- Removing root requirement.
- Supporting no-root mirroring.
- Complex user profiles for multiple preferred controllers.
- UI mockups or visual redesign beyond state/config updates needed for automatic mode.
- Cloud, analytics, or telemetry.
