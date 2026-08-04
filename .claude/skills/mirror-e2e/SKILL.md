---
name: mirror-e2e
description: >
  End-to-end test suite for the Docking Enhancer mirror, run against synthetic controllers on a
  real handheld. Covers event forwarding, hiding and restoring the external pad, orphan recovery,
  the owner watchdog, and the refusal to unlink the wrong device. Use when changing input_mirror.c,
  the hide/restore or heal paths, the supervisor lifecycle, or before shipping a release — and
  whenever a robustness fix needs proof it works rather than proof it compiles.
---

# Mirror e2e suite

Unit tests cover the pure logic; this covers everything that only breaks on real hardware — evdev
grabs, `/dev/input` node lifetimes, SELinux labels, and a root daemon outliving its app. Those are
exactly the failures that leave a user with a **controller that no longer works**, so they deserve
proof, not inspection.

## Prerequisites

- The Odin connected over adb, **unlocked and awake**.
- The app installed (the suite copies and tests *its* daemon binary, not a fresh build, so what runs
  is what ships).
- `ANDROID_NDK_HOME`, or an NDK under `%LOCALAPPDATA%\Android\Sdk\ndk` (the build script finds it).

No physical external controller is needed. That is the point of the harness.

## Running it

```powershell
.\.claude\skills\mirror-e2e\scripts\build-harness.ps1
adb shell sh /data/local/tmp/e2e.sh
```

The runner prints `PASS`/`FAIL` per assertion and exits non-zero if anything failed. It force-stops
the app first (its supervisor would otherwise fight the suite for the controllers) and cleans up
after itself. **Relaunch the app afterwards** if you want the mirror back.

From Git Bash, invoke adb through PowerShell — Git Bash mangles `/data/local/tmp/...` into a Windows
path.

## What each test proves

| Test | Failure it would catch |
|---|---|
| T1 forwarding | Events dropped, reordered, or missing SYN framing — the mirror silently mangling input. Includes a 40-frame burst, which is what exercises the batched reads. |
| T2 hide/restore | The external pad not disappearing while mirroring, or worse, not coming back on a clean stop. |
| T3 orphan heal | A hard-killed daemon leaving a node hidden that `--heal` cannot recover. |
| T4 owner watchdog | A daemon outliving its app (uninstall, cleared data) and keeping the pad grabbed and hidden forever. |
| T5 identity refusal | The daemon unlinking an unrelated device after event numbers renumber. |
| T6 hot reload | A settings change silently not taking effect, a malformed config being adopted, or the daemon restarting to apply one — which would release the grab and flash the pad visible. |
| T7 capture | The mapping wizard seeing nothing, or worse, the controller still driving the game while the user maps it — pressing A to bind it would also press A in whatever is on screen. |
| T8 remap | A saved mapping not reaching the forwarding path, or a control the user skipped being changed anyway. |
| T9 axis→button | A trigger bound to a button slot firing on noise, or never releasing once past the threshold. |
| T10 button→axis | A button bound to a stick direction arriving at partial deflection, or sticking at full. |
| T11 capture expiry | A wizard that died mid-step leaving the pad permanently mute, with no way to revive it from the controller itself. |
| T12 virtual mouse | The pointer failing to appear on Select+R3, the pad still driving the game while the cursor is up, or — worst — a uinput pointer outliving the daemon with nothing left to destroy it. |

## Measuring what the mirror costs

```
adb shell sh /data/local/tmp/latency.sh 300
```

Runs the shipped daemon between two synthetic pads and times a button press round trip. Timing lives
inside the injecting process because the source cannot be read while the daemon holds it grabbed, and
timing across two processes would mean correlating two clock domains.

It reports a **control** first — the same round trip with no daemon in between — because the raw
figure includes uinput, the input core and the harness's own wake-up. The mirror's real contribution
is the difference. Measured on an Odin 2 Portal, 300 samples: control median 21µs, mirrored median
59–61µs, so the daemon adds **roughly 0.04ms** (p95 ≈ 0.11ms). Well under a tenth of a percent of one
60Hz frame, against the 10–25ms a Bluetooth pad already spends on radio.

Treat that number as device-and-moment specific rather than a constant. An earlier run of this same
harness recorded ~0.09ms, and re-measuring the *older* daemon binary on the same device the same
afternoon gave ~0.05ms — so the gap was measurement conditions (thermal state, governor), not a code
change. Which is the point of the control run: compare the two figures **from the same session**, and
never a fresh mirrored figure against a remembered one.

Re-run it after touching the forwarding path. A regression there is invisible to every other test in
this suite, which only checks that events arrive, never how fast.

## Design rules

**The product is tested through its no-root path.** `su` appears in the runner only as *scaffolding*
— copying the binary out of the app's private dir, killing a daemon to stage an orphan, cleaning up.
The daemon itself is exercised the same way the app runs it. If a test ever needs root to make a
product behaviour work, that is a finding, not a fix.

**Assert on the filesystem and the framework, not on log lines.** A node is restored when
`/dev/input/eventN` is back *and* `dumpsys input` lists it — the second half is what catches a
missing SELinux label, which is invisible to `ls`.

## Device quirks this harness learned the hard way

- **The handheld hijacks foreign controllers.** Create a uinput gamepad with any vendor other than
  `0x2020` and the firmware republishes it as a `0x2020` twin and *deletes the original node*. A
  foreign-vendor pad therefore never keeps a `/dev` entry. This is the same mechanism behind the real
  external pad having no node of its own, and it is why T5's decoy is `--plain` (a non-controller the
  firmware ignores) instead of a foreign-vendor pad.
- **`getevent` labels `0x130` as `BTN_GAMEPAD`**, not `BTN_SOUTH`/`BTN_A` — all aliases of one code.
  Asserting on the wrong alias reads as a forwarding bug that is not there. The same trap sits on the
  virtual mouse: `0x110` prints as **`BTN_MOUSE`**, never `BTN_LEFT`. `BTN_RIGHT` (`0x111`) has no
  alias and prints as itself, so only the left click misleads.
- **Absolute axes dedupe.** The input core drops an ABS event repeating its current value, so any
  synthetic burst must alternate values or it arrives half empty.
- **A physical pad sleeps mid-run.** The reason the suite uses synthetic devices at all.

## Manual checklist — the app layer

The suite stops at the daemon boundary; the supervisor, the UI and the physical pad need a person.
Run these against a debug build with `FORCE_DOCK_MODE_FOR_DEV = true` (and set it back before
committing). Watch the system side with `pidof input_mirror`, `dumpsys input | grep -i 8bitdo`, and
`run-as com.odininputmirror cat files/input_mirror.hidden`.

1. **Auto-start** — with Automatic Mirror on, wake the pad. The mirror should start on its own, hide
   the pad, and beat its heartbeat.
2. **Rapid toggling** — hammer the three switches out of order for ~45s, then stop and leave the app
   alone. See the settled behaviour below for what "correct" looks like.
3. **Mouse mode** — Select+R3. The cursor must appear *without* moving the stick, then respond to
   both sticks, A/B clicks and the R1 shade toggle.
4. **Internal picker** — turn Automatic Mirror off, tap the Local Controller card. The pad currently
   acting as external must not be listed.
5. **Combos** — Select+Start for 3s force-stops the foreground app; Home acts as Back.
6. **Disconnect / reconnect** — power the pad off, wait, power it on. The daemon must exit restoring
   the node and clearing the state file, and a fresh mirror must start once the pad is back.

### Settled behaviour worth knowing (measured, not assumed)

- **Restarts flash the pad visible for ~0.5–1s.** `EVIOCGRAB` is exclusive, so the old daemon must
  release before the new one grabs, and a game open at that moment can briefly see two controllers.
  Inherent to a restart, not a defect. What changed is how often you pay it: settings no longer
  restart the daemon (they reload through the control fifo, covered by T6), so only a change of which
  devices are mirrored costs a restart now. **The rapid-toggling figures below were measured under
  the old restart-per-toggle behaviour and need re-measuring.**
- **Rapid toggling never stranded the pad.** Five restarts in 45s of hammering, and the dangerous
  state — hidden with no daemon running — did not occur once: each daemon restores before dying and
  the next one re-hides.
- **The 10s restart throttle is what keeps that sane**, holding five restarts instead of dozens.
  Flags read stale while a change waits out the throttle; they converge within one window (verified
  over 9 consecutive samples) once the toggling stops.
- **Reconnect takes ~5s** after the pad reappears — the supervisor's waiting-for-device poll.

## Still unverified

- **`link()` failing during restore.** The recovery path exists but cannot be forced without
  sabotaging the filesystem; covered by inspection, not by test.
- **Reconnect that renumbers.** Every observed reconnect reused the same `eventN` and device numbers,
  so the path where a pad returns under a *different* node has only been exercised synthetically
  (T5's identity check), never end to end.
Two of these closed together in a single reboot run:

- **Boot autostart.** The receiver brings the supervisor up on its own — confirmed with the app's UI
  never opened — and the mirror starts as soon as the pad reconnects.
- **Node renumbering.** That same reboot moved the pad from `event9` (13:73) to `event10` (13:74),
  and the mirror hid the right node regardless, because devices resolve by GUID before path. Trusting
  the stale path would have aimed the unlink at an unrelated device — the case T5 exists to refuse.

A third closed itself by accident: the suite SIGKILLs any live mirror at setup, which orphaned the
real pad's node. The state file kept the record and the supervisor healed it unprompted, exercising
T3's path against real hardware rather than a synthetic pad.

A fourth needed no rig at all: installing on an ordinary phone shows the unsupported notice, so the
`PServerBinder` gate is confirmed to turn a non-handheld away instead of half-running.

What is left is untestable here rather than unverified: a failing `link()` (unforceable without
sabotaging the filesystem) and two external pads at once.

### Testing against a real dock

The dock occupies the USB port adb uses, which is why the real `DisplayManager` path went unobserved
for so long — `FORCE_DOCK_MODE_FOR_DEV` returns before that code is ever reached, so a forced-dock
run proves nothing about it. Use wireless adb: while still on the cable run `adb tcpip 5555`, take
the device IP from `adb shell ip route`, then `adb connect <ip>:5555` and unplug. The link does not
survive a reboot, so re-pair over USB afterwards.

Verified end to end this way: docking is detected (a display with a non-default id, state ON), the
mirror starts and hides the pad, and undocking stops it and hands the pad back with no hidden-state
record left behind.

Use a **Bluetooth** pad for the undock half. With a 2.4GHz dongle plugged into the dock, undocking
disconnects the controller along with the display, and "the pad did not come back" becomes
indistinguishable from "the pad is gone" — a confound that costs a full test cycle to spot.

There is no CI story: every test here needs the handheld attached. That is a property of the
product, not a gap in the suite.

## Adding a test

Follow the existing shape in `scripts/e2e.sh`: stage the state with the synthetic pads, act, then
assert with `expect_exists` / `expect_missing` / `expect_contains`. Prefer asserting the *user-visible
consequence* (the node is back, the pad is listed) over an implementation detail — an assertion tied
to internals will fail on the next refactor without any behaviour having changed.

If a new test needs a device shape `vgamepad` cannot make, extend it there rather than scripting
raw uinput ioctls in shell.
