# Technical notes

Design detail, hard-won platform behaviour and build instructions. The [README](../README.md) covers
what the app does; this covers how, and why it is built the way it is.

## Architecture

Android multi-module project under `android/`:

- **`:app`** — Compose UI, the foreground supervisor service, the boot receiver
- **`:data`** — repositories, the privileged shell (`PServerShell`/`PServerExec`), device resolution
- **`:domain`** — pure Kotlin use cases, models, interfaces

Dependency direction is `app -> data -> domain`.

The mirroring itself is a small native C binary, [`input_mirror.c`](../android/app/src/main/native/input_mirror.c),
shipped as an app asset:

1. Opens the **source** (external controller) read-only and grabs it exclusively (`EVIOCGRAB`) so its
   input no longer reaches the system directly.
2. Opens the **target** (internal controller) read-write and replays every event onto it, so the
   whole system sees the external pad as the internal one.
3. Unlinks the external controller's `/dev/input` node, which makes Android drop it from the device
   list. The node is recreated on exit.
4. Writes `pid` and `heartbeat` files the app polls to track liveness.

## Running without root

The stock firmware ships a privileged `PServerBinder` service. The app obtains it by reflection and
drives it with a raw binder transaction that executes a shell command as root — no Magisk, no `su`.
Same technique as ClusterTune and PULSE.

Two properties of the service shape the implementation:

- It **returns only the first line of stdout and no exit code**, so reads stage their full output
  through a file the app reads back, and status checks use a token in stdout rather than an exit
  code.
- It **cannot service overlapping transactions**, so every call is serialized process-wide.

The daemon is launched in the foreground inside a backgrounded `sh` script — never `setsid` or an
inline `&`, because the binary handles `SIGHUP` and would otherwise exit the moment the transaction
returns.

### The Odin 2 Mini

The Mini publishes `PServerBinder` — the handle resolves and reports itself alive — but every
transaction against it throws `DeadObjectException`, because the process behind the service is gone.
Confirmed on firmware `V1.0.0.117_20240626`.

Nothing in the app can work around a service that isn't running, so availability is decided by
whether the service **answers a real transaction**, not by whether it resolves. Devices that fail
that check are told so rather than left with an app that installs fine and silently does nothing. If
a newer firmware brings the service back, the app should work with no changes.

## Hiding the external controller

Two platform details make this possible:

- An **open file descriptor outlives `unlink()`**, so deleting the node cuts the framework off
  without costing the daemon its input stream.
- Android's `EventHub` **watches `/dev/input` with inotify** and drops a device the moment its entry
  vanishes.

Restoring it is the fiddly half. The node is rebuilt (`mknod` + owner `root:input` + `0666` + the
`u:object_r:input_device:s0` SELinux label) in a staging path **outside** `/dev/input`, then
hard-linked into place — so `EventHub` only ever sees a fully-labelled node and never races a
half-built one.

Before unlinking anything the daemon confirms the node's identity via `EVIOCGID`: event numbers get
recycled across reconnects, so a path resolved a moment ago may point at a different device by the
time the daemon acts on it. What it hid is recorded to a state file, so a crash can be healed rather
than leaving a controller invisible. The supervisor heals orphans on its next tick.

## Changing a setting without restarting

Options used to be fixed at launch, so every toggle restarted the daemon — and a restart has to
release the exclusive `EVIOCGRAB` before the next session can take it, leaving the pad visible to
the whole system for about a second. Instead:

1. The app serialises the live options to `input_mirror.config.json` in its own directory, writing a
   temp file and `rename()`-ing it into place. Rename within one filesystem is atomic, so the daemon
   can never read a half-written document and neither side needs a lock.
2. The app writes `reload` to a fifo it created itself with `Os.mkfifo`. **This deliberately does not
   go through PServer**, whose transactions are serialized process-wide — the app owns both the
   config and the fifo, so a toggle never queues behind anything.
3. The daemon watches that fifo in the same `poll()` as the source, and swaps its config between two
   events. The forwarding path and the grab are untouched.

The fifo is opened `O_RDWR` by the daemon so it is its own phantom writer: as the only reader it
would otherwise get `POLLHUP` on every gap between writes and spin the loop. The app writes with
`O_NONBLOCK`, so a dead daemon fails immediately instead of blocking the writer forever.

Applying a config is a reconcile, not an assignment: a flag turning **off** may own live state that
only the old value knows how to unwind — dropping the virtual mouse while its pointer exists would
strand a uinput device and a visible cursor. Turning one **on** needs no such care.

The acknowledgement closes the loop. Each change advances a generation the daemon echoes in its
heartbeat, so the app can tell an applied change from one in flight. A config that fails to parse is
never adopted: the running one survives, and the generation the app is waiting for simply never
arrives. If the daemon can't be reached at all, the supervisor stops it and the next tick starts a
fresh one carrying the new options — the restart still exists, as the fallback rather than the
mechanism.

The one thing that still restarts the daemon is changing **which devices** are mirrored.

## Latency

**The mirror adds roughly 0.09 ms to a button press** (p95 ≈ 0.37 ms), measured on an Odin 2 Portal
over 300 presses.

The figure is the difference against a control run of the same round trip with no daemon in between,
so it excludes the measurement harness's own overhead. For scale, that is about half a percent of a
single 60Hz frame, against the 10–25 ms a Bluetooth pad already spends on radio. Forwarding costs one
read and one write per event, with the daemon at `nice -20`.

Reproduce it with the e2e suite's `latency.sh`.

## Known limitations

- Event node paths (`/dev/input/eventX`) are unstable across reconnects and reboots, so controllers
  are resolved by GUID first and path second.
- Controller GUIDs derive from vendor + product only, so two identical controllers of the same model
  share a GUID and can't be told apart.
- "Home as Back" injects a framework-level Back key. Apps reading controllers directly via evdev
  (some emulators) may not react to it.
- While hidden, the external pad is invisible to everything — it stays connected at the transport
  level (still paired in Bluetooth settings, still enumerated over USB) but is no longer an input
  device to anything but the daemon.
- The virtual mouse can't drag the notification shade open the way a finger swipe does, so **R1**
  toggles it via a system command. The app tracks that state itself, so closing the shade another way
  may cost one extra press to resync.
- The mirror writes directly to the internal controller's evdev node, which depends on the device
  exposing it that way. (The virtual mouse is the exception — it creates its own `uinput` pointer.)

## Building from source

**Prerequisites:** JDK 17, Android SDK (compileSdk 35), and an NDK only if you intend to change the C
core — a prebuilt binary is committed.

```powershell
# App
.\android\gradlew.bat -p .\android :app:assembleDebug
adb install -r .\android\app\build\outputs\apk\debug\app-debug.apk

# Native binary (only when input_mirror.c changes)
$env:ANDROID_NDK_HOME = "$env:LOCALAPPDATA\Android\Sdk\ndk\<version>"
.\scripts\build-input-mirror.ps1
```

The build script compiles for `arm64-v8a` and drops the result into
`android/app/src/main/assets/input_mirror/input_mirror`, which is tracked intentionally so users
don't need an NDK. **Rebuild and commit it in the same commit as any `input_mirror.c` change**, or
the repo describes one daemon and ships another.

Stack: Kotlin 2.0.21 · Compose + Material 3 (BOM 2024.10.01) · Gradle 8.11.1 · AGP 8.7.3 · JVM 17 ·
minSdk 28 · target/compileSdk 35.

## Testing

Unit tests cover the pure logic. Everything that only breaks on real hardware — evdev grabs, node
lifetimes, SELinux labels, a daemon outliving its app — is covered by an on-device e2e suite driven
by synthetic controllers, which lives in `.claude/skills/mirror-e2e/`.

There is no CI: every meaningful test needs the handheld attached.
