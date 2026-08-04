# Custom Controller Mapping Design

Date: 2026-08-02

## Goal

Let a user correct a controller whose layout does not match what the mirror assumes. An editor lists
every slot with what it currently reads; picking one asks for a press and binds it. The result is
stored as a per-controller mapping the daemon applies while forwarding.

The case that motivates it: the mirror rewrites face buttons only through `swap_nintendo_face_button`,
which is keyed off the *target's* USB id and assumes the source reports standard evdev codes. A pad
that reports something else (8BitDo in Switch mode, and anything the firmware does not normalise) is
simply wrong, with nothing the user can do about it.

## Current Context

Android multi-module project, dependency direction `app -> data -> domain`. No root: privileged
commands run through the stock firmware's PServerBinder service.

The pieces this design builds on all landed in the hot-reload work (2026-08-02):

- The daemon reads `input_mirror.config.json` and re-reads it on a `reload` command written to
  `input_mirror.ctl`, a fifo the app creates with `Os.mkfifo` in its own directory. The fifo was
  designed as a **command channel**, not a signal, precisely so capture could join it.
- The daemon echoes a `generation` in its heartbeat as the ack. A config that fails to parse is never
  adopted.
- `apply_config` reconciles rather than assigns, because a setting turning off may own live state.
- The daemon holds an exclusive `EVIOCGRAB` on the source and unlinks its `/dev/input` node, so
  **the app cannot see a single event from the external pad**. Any capture must come from the daemon.

Two facts measured on 2026-07-31 shape the identity half:

- The firmware republishes every external controller as a `2020:0111` twin and deletes the original
  node. That twin's vendor+product is identical for *every* external pad, and collides with the
  built-in "ODIN Station Virtual Mouse".
- The real device keeps its own entry in `/proc/bus/input/devices` — with its true vendor:product
  (`2dc8:301b` for the 8BitDo) and its MAC — which the app already reads and currently discards
  because the node is gone.

## User-Approved Behavior

- The editor runs **while the mirror is active**, because that is when the user discovers the problem.
- Scope covers **buttons and axes**, in one release.
- For an axis the user configures **target axis and direction only**; ranges are derived from
  `EVIOCGABS` on both nodes.
- A mapping is stored **per real controller identity**, not per twin.
- The editor is a **list of slots**, each independently bindable and clearable. Revised 2026-08-03:
  the first cut was a linear walk, which got the default backwards — most slots on most pads are
  already right, and marching the user through all of them to fix two made the common case the
  expensive one.
- The target space is **wide**: any physical control can fill any slot, in either direction, with
  stick directions bindable in their own right. Approved 2026-08-03, widening the first cut.

## Design

### 1. Capture, as a daemon mode

Three new commands on the existing control fifo:

```
capture button
capture stick
capture off
```

While in capture mode the daemon keeps its grab but **stops forwarding to the target**, so pressing A
to map it does not also press A in whatever is on screen. Every qualifying event is appended to
`input_mirror.capture`; the app remembers its read offset and consumes only what is new. Appending
rather than rewriting is what makes a fast double press impossible to lose.

Interpretation is typed by what was asked for, not by what arrives:

| Asked for | `EV_KEY` arrives | `EV_ABS` arrives |
|---|---|---|
| `button` | `button <code>` | `axis_button <code> <sign>` once past half deflection |
| `stick` | falls back to `button` | first axis is remembered silently; the second *distinct* axis emits `stick <x> <y> <sign_x> <sign_y>` |

Falling back across types is what makes an analog trigger able to fill a button slot, and a button
able to fill a stick direction, without the editor having to ask which kind of control the user owns.

**The app only ever asks for `button`.** Every slot — including a stick direction — wants one control
pushed one way, which is exactly what that mode reports. `capture stick` survives in the daemon
because it costs nothing and is tested, but nothing calls it: once stick directions became slots of
their own, a gesture that captured two axes at once had no slot to put them in.

**A latch, not a stream.** Once a capture is satisfied the daemon stops emitting until the app asks
again. Without it the release event, or a second threshold crossing on the same stick, immediately
satisfies the next request too.

**Capture cannot strand the user.** If the app dies mid-capture, a daemon left in capture mode forwards
nothing: the pad goes dead with no way to recover it from the pad itself. Capture therefore expires
30 seconds after the last command, returning to normal forwarding. Same reasoning as the heartbeat
watchdog.

### 2. The mapping, as config

The mapping rides the config document that already hot-reloads:

```json
"mapping": {
  "bindings": [[0, 304, 0, 0, 305, 0], [2, 2, 1, 0, 307, 0], [0, 304, 0, 2, 3, 1]]
}
```

Each row is `[source_kind, source_code, source_dir, target_kind, target_code, target_dir]`, where the
kind is `0` a button, `1` a whole axis, `2` one half of an axis's travel. Saving a mapping is writing
the config and sending `reload` — no new IPC, and it inherits the generation ack and the refusal to
adopt a config that fails to parse.

**One table, wide targets.** The first cut had two tables — buttons to buttons, axes to axes — with
no way to cross between them. That is a real hole rather than a rough edge: a controller reporting its
triggers as axes could not fill a button slot at all, and the wizard had to detect the case, record
nothing, and apologise. Naming a *kind* on each end is what closes it, and it costs one table rather
than three: the crossings are not special cases, they are the same resolver reading a different pair
of kinds.

`forward_event` resolves an event against the one table and emits by target kind:

| source → target | behaviour |
|---|---|
| button → button | code rewritten, value passed through (autorepeat survives) |
| axis → axis | rescaled between the two devices' ranges, orientation applied |
| half-axis → button | pressed past 0.55 of the available travel, released below 0.45 |
| button / half-axis → half-axis | driven from rest toward the named end; a digital source gives full deflection, an analog one stays proportional |

A source absent from the table is forwarded untouched — which is exactly what the wizard's "skip"
promises.

Two details the crossings force, neither of which the axis-to-axis case needed:

- **Hysteresis, not a threshold.** A trigger resting on a single threshold chatters its button open
  and shut on sensor noise alone. Enter and leave are deliberately different numbers.
- **Contributions combine, they do not overwrite.** Bind L2 to "left stick left" and R2 to "left stick
  right" and the two have to meet in the middle. Each binding therefore remembers how hard it is being
  driven, and the axis is recomputed from all of them — whichever moved last simply winning would make
  opposite halves unusable.

Both ends of an analog binding need a **resting** value, not just a range: a stick rests mid-travel
and a trigger at its minimum, and "how far has this been pushed" is meaningless without knowing where
it started. `EVIOCGABS` already reports it, so `axis_range` carries it for both devices.

### 2b. Stick modes, as presets

A captured stick is bound one of three ways, chosen on the step itself because the mode decides what
the same gesture *becomes*:

| Mode | What it writes |
|---|---|
| `ANALOG` | two whole-axis bindings, orientation from the capture |
| `DPAD` | four half-axis → D-pad button bindings |
| `FACE` | four half-axis → face button bindings, laid out as they sit on the pad |

These are presets over the same table, not modes the daemon knows about. "As a D-pad" is four ordinary
bindings, so the daemon stays one resolver and every mode remains expressible by hand.

**Composition with the Nintendo swap.** The custom mapping runs first (pad → canonical), then the
existing target-side swap (canonical → what the target expects). A correctly-labelled pad therefore
behaves exactly as it does today, and the swap stops being a special case without having to be
deleted.

### 3. Identity

A mapping is keyed by the **real** device's `vendor:product`, resolved by matching the twin's name
against the non-quirk entry in `/proc/bus/input/devices`. Different models get different mappings.
Two identical pads share one — which is what their owner would want anyway, and is the limitation
already documented for GUIDs.

### 4. UI

The External Controller card gains a green border and a `CUSTOM MAPPING` badge, plus an entry point.
The wizard is a **screen, not a dialog**: the step is the only thing the user is doing, and the
controller is busy driving the wizard rather than the app behind it. It walks the controls in a fixed
order showing which one to press, a progress indicator, a per-control skip, and a reset that deletes
the stored mapping outright. Cancelling sends `capture off` and writes nothing.

Everything on the screen is reachable by D-pad, because the handheld's own pad is the only one free —
the external pad is answering the capture. Focus is anchored by retrying `requestFocus` across a few
frames after leaving touch input mode, and re-anchored on the way back out of the wizard.

**A source already bound is refused, not stolen.** Press a control the walk already took and the step
says which slot has it and waits for a different one. Letting the newer binding win silently is how a
user ends up with a control torn off a slot they set eight steps ago, with nothing on screen to
connect the two — and the daemon would have both bindings fighting over the same event anyway.

### Deliberately out of scope

- **Per-game scoping.** An emulator can key bindings to a game because it is the thing running it.
  The mirror sits under the whole system and has no reliable notion of a current game — the
  foreground package is not one, and asking for it would need a permission the app does not want.
- **Stick feel (curves, deadzones).** The mirror's job is to hand the target what the pad did. A pad
  with a bad deadzone is a pad problem, and every emulator worth using already exposes its own.
- **Per-player bindings.** One external pad mirrors onto one internal pad. There is no second player.

## Failure Modes

| Failure | Behaviour |
|---|---|
| App dies mid-capture | Daemon leaves capture mode after 30s and resumes forwarding |
| Config with a malformed mapping | Not adopted; running config survives; generation stalls |
| One malformed binding row | That row is dropped; the rows around it survive |
| A row naming an unknown kind | Dropped rather than guessed at — a code means nothing outside its own namespace |
| Control never pressed | User skips it; that code forwards unchanged |
| Control already bound | Step refuses it, names the slot that has it, and waits |
| Analog source resting on the threshold | Hysteresis keeps the button from chattering |
| Pad disconnects mid-wizard | Daemon exits as it does today; wizard sees a dead heartbeat and aborts |
| Two identical pads | They share one mapping, by design |

## Testing

Unit tests cover mapping serialisation (round trip, damaged rows, unknown kinds, direction folding)
and the stick presets — including that a pad wired backwards gets its *directions* swapped rather than
its axes, which is the failure that would otherwise ship silently as a stick that works but is
mirrored.

The e2e suite gains five tests, all against synthetic pads:

- **T7 capture** — entering capture stops forwarding and pressed codes reach the capture log.
- **T8 remap** — a mapping in the config makes a forwarded button arrive as a different code, and an
  unmapped code arrives unchanged.
- **T9 axis into a button slot** — a light touch does not fire; past the threshold the target sees the
  button, never the axis; and the release arrives so the button cannot stick down.
- **T10 button into a stick direction** — the target sees the axis at full deflection and back to
  rest, never the original button.
- **T11 abandoned capture** — the pad is genuinely mute while a step is open, and revives on its own
  after the timeout with nobody closing it. Costs the suite a real 30-second wait, which is the only
  honest way to test it.
