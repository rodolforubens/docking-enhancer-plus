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
  Asserting on the wrong alias reads as a forwarding bug that is not there.
- **Absolute axes dedupe.** The input core drops an ABS event repeating its current value, so any
  synthetic burst must alternate values or it arrives half empty.
- **A physical pad sleeps mid-run.** The reason the suite uses synthetic devices at all.

## Not covered — verify by hand

The suite deliberately stops at the daemon boundary. These still need a person:

- **App/supervisor flows**: dock gating, auto-start on connect, restart after a toggle change,
  settings persistence. Drive the real app and read `dumpsys input` / the notification state.
- **Anything visual**: that the mouse cursor actually *appears*, that the UI reads correctly.
- **The physical pad's own behaviour**: combos (Select+Start, Select+R3) and Home-as-Back need real
  presses on a real controller.
- **`link()` failing during restore.** The recovery path exists but cannot be forced without
  sabotaging the filesystem; it is covered by inspection, not by test.

There is no CI story: every test needs the handheld attached. That is a property of the product, not
a gap in the suite.

## Adding a test

Follow the existing shape in `scripts/e2e.sh`: stage the state with the synthetic pads, act, then
assert with `expect_exists` / `expect_missing` / `expect_contains`. Prefer asserting the *user-visible
consequence* (the node is back, the pad is listed) over an implementation detail — an assertion tied
to internals will fail on the next refactor without any behaviour having changed.

If a new test needs a device shape `vgamepad` cannot make, extend it there rather than scripting
raw uinput ioctls in shell.
