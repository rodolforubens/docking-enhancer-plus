#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <math.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/poll.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include "config.h"
#include "hide_nodes.h"

static volatile sig_atomic_t keep_running = 1;
static const long long COMBO_HOLD_KILL_APP_MS = 3000;
static const long long HEARTBEAT_INTERVAL_MS = 1000;
// Consecutive beats the heartbeat path may stay missing before we conclude this daemon has no owner
// left and shut down. The app deletes that path exactly when it wants us gone (it was uninstalled,
// its data was cleared, or it is abandoning a duplicate launch), so five seconds of absence is
// decisive without being twitchy.
static const int HEARTBEAT_MISSING_LIMIT = 5;
// Transient write errors to the target (the internal controller, which almost never truly
// disappears) are retried this many times before giving up — so a momentary hiccup doesn't tear
// down the whole mirror.
static const int TARGET_WRITE_RETRIES = 3;

// Events pulled from the source in a single read(). evdev returns whole events only, so one read
// coalesces a frame's worth of axis+button+SYN events instead of one syscall each. 32 is ample —
// a single frame rarely exceeds ~10 events; any overflow is drained by the next loop.
#define EVENT_BATCH_SIZE 32

// Virtual mouse mode (opt-in via --virtual-mouse). Select+R3 held this long toggles the mode; while
// on, the grabbed controller drives a self-created uinput pointer instead of the target node.
static const long long MOUSE_TOGGLE_HOLD_MS = 500;
static const long long MOUSE_FRAME_INTERVAL_MS = 12; // ~83 Hz cursor/scroll updates
static const double MOUSE_SPEED = 18.0;              // max cursor pixels per frame at full deflection
static const double MOUSE_DEADZONE = 0.18;           // fraction of stick travel ignored around center
static const double WHEEL_STEP_PER_FRAME = 0.30;     // scroll clicks accumulated per frame at full deflection
// Android only paints the pointer once it receives real motion, so entering mouse mode used to leave
// the cursor invisible until the stick was moved. We can't just emit one nudge on entry either: the
// uinput device was created microseconds ago and the framework still has to notice it via inotify
// and open it, so anything written before that is dropped. Instead nudge on every idle frame for
// this long, which covers the open latency and stops as soon as the window closes.
static const long long CURSOR_SHOW_NUDGE_MS = 300;

/*
 * In Odin mode we remap the four face buttons of a mirrored external controller to the
 * Nintendo layout the console uses by default: A and B (south/east) are swapped, and X and Y
 * (north/west) are swapped, so the physically-labelled button acts as its label rather than
 * its Xbox-position equivalent. This is keyed off the target device's USB id, so Xbox mode
 * (product 0x0112) passes through untouched.
 */
#ifndef BTN_SOUTH
#define BTN_SOUTH 0x130
#endif
#ifndef BTN_EAST
#define BTN_EAST 0x131
#endif
#ifndef BTN_NORTH
#define BTN_NORTH 0x133
#endif
#ifndef BTN_WEST
#define BTN_WEST 0x134
#endif
#ifndef BTN_THUMBR
#define BTN_THUMBR 0x13e
#endif
#ifndef BTN_TR
#define BTN_TR 0x137
#endif
#ifndef BTN_SELECT
#define BTN_SELECT 0x13a
#endif
#ifndef BTN_START
#define BTN_START 0x13b
#endif
#define ODIN_VENDOR_ID 0x2020
#define ODIN_NINTENDO_PRODUCT_ID 0x0111

static unsigned short swap_nintendo_face_button(unsigned short code) {
    switch (code) {
        case BTN_SOUTH:
            return BTN_EAST;
        case BTN_EAST:
            return BTN_SOUTH;
        case BTN_NORTH:
            return BTN_WEST;
        case BTN_WEST:
            return BTN_NORTH;
        default:
            return code;
    }
}

static void handle_signal(int signal_number) {
    (void)signal_number;
    keep_running = 0;
}

/*
 * Run a shell command WITHOUT blocking the mirror loop. A plain system() here would stall input
 * forwarding for the whole duration of the command — `input keyevent` spins up an app_process (JVM)
 * and `dumpsys`/`am` can take hundreds of ms — dropping controller events and, if long enough,
 * starving the heartbeat into a false "dead" state. Forking lets the parent keep polling the source.
 */
static void run_detached(const char *command) {
    pid_t pid = fork();
    if (pid < 0) {
        return;
    }
    if (pid == 0) {
        // Restore default SIGCHLD so system()'s internal waitpid works in the child.
        signal(SIGCHLD, SIG_DFL);
        _exit(system(command) == -1 ? EXIT_FAILURE : EXIT_SUCCESS);
    }
    // Parent: with SIGCHLD ignored (set in main) the child is auto-reaped; never wait on it.
}

static int write_full(int fd, const void *buffer, size_t length) {
    const unsigned char *cursor = (const unsigned char *)buffer;
    size_t remaining = length;

    while (remaining > 0) {
        ssize_t written = write(fd, cursor, remaining);
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }

        if (written == 0) {
            errno = EIO;
            return -1;
        }

        cursor += written;
        remaining -= (size_t)written;
    }

    return 0;
}

// Write one input_event to fd. Used for the uinput mouse and for target neutralisation.
static int emit_event(int fd, unsigned short type, unsigned short code, int value) {
    struct input_event event;
    memset(&event, 0, sizeof(event));
    event.type = type;
    event.code = code;
    event.value = value;
    return write_full(fd, &event, sizeof(event));
}

// Write one forwarded event to the target, tolerating a transient failure. write_full already
// retries EINTR; here we retry the whole write a few times with a short backoff so a momentary
// target error (the internal controller briefly busy) doesn't break the mirror loop. Gives up —
// returning -1 — only if it keeps failing, at which point the target is likely genuinely gone.
static int write_event_tolerant(int fd, const struct input_event *event) {
    for (int attempt = 0; ; attempt++) {
        if (write_full(fd, event, sizeof(*event)) == 0) {
            return 0;
        }
        if (attempt >= TARGET_WRITE_RETRIES) {
            return -1;
        }
        usleep(2000);
    }
}

static int is_home_button(unsigned short code) {
#ifdef KEY_HOMEPAGE
    if (code == KEY_HOMEPAGE) {
        return 1;
    }
#endif
#ifdef KEY_HOME
    if (code == KEY_HOME) {
        return 1;
    }
#endif
#ifdef BTN_MODE
    if (code == BTN_MODE) {
        return 1;
    }
#endif
    return code == 172;
}

static int is_select_button(unsigned short code) {
#ifdef BTN_SELECT
    if (code == BTN_SELECT) {
        return 1;
    }
#endif
#ifdef KEY_SELECT
    if (code == KEY_SELECT) {
        return 1;
    }
#endif
    return code == 314;
}

static int is_start_button(unsigned short code) {
#ifdef BTN_START
    if (code == BTN_START) {
        return 1;
    }
#endif
#ifdef KEY_START
    if (code == KEY_START) {
        return 1;
    }
#endif
    return code == 315;
}

static long long now_ms(void) {
    struct timespec current_time;
    if (clock_gettime(CLOCK_MONOTONIC, &current_time) != 0) {
        return 0;
    }

    return ((long long)current_time.tv_sec * 1000LL) + ((long long)current_time.tv_nsec / 1000000LL);
}

static void force_stop_foreground_app(void) {
    run_detached(
        "pkg=$(dumpsys activity activities 2>/dev/null | sed -n 's/.*ResumedActivity: ActivityRecord{[^ ]* [^ ]* \\([^/ ]*\\)\\/.*/\\1/p' | head -n 1); "
        "task=$(dumpsys activity activities 2>/dev/null | sed -n 's/.*ResumedActivity: ActivityRecord{.* t\\([0-9][0-9]*\\)}.*/\\1/p' | head -n 1); "
        "[ -z \"$pkg\" ] && pkg=$(dumpsys window 2>/dev/null | sed -n 's/.*mFocusedApp=ActivityRecord{[^ ]* [^ ]* \\([^/ ]*\\)\\/.*/\\1/p' | head -n 1); "
        "[ -z \"$task\" ] && task=$(dumpsys window 2>/dev/null | sed -n 's/.*mFocusedApp=ActivityRecord{.* t\\([0-9][0-9]*\\)}.*/\\1/p' | head -n 1); "
        "case \"$pkg\" in ''|com.odininputmirror|com.android.systemui|com.android.launcher*|*launcher*) ;; *) [ -n \"$task\" ] && cmd activity stack remove \"$task\" 2>/dev/null; am force-stop \"$pkg\" ;; esac"
    );
}

static int write_pid_file(const char *pid_file_path) {
    if (pid_file_path == NULL) {
        return 0;
    }

    FILE *pid_file = fopen(pid_file_path, "w");
    if (pid_file == NULL) {
        fprintf(stderr, "Failed to open pid file %s: %s\n", pid_file_path, strerror(errno));
        return -1;
    }

    fprintf(pid_file, "%ld\n", (long)getpid());
    fclose(pid_file);
    // 644: the app only ever READS these root-owned files back (delete goes through the
    // app-owned parent directory, which needs no write bit on the file itself).
    chmod(pid_file_path, 0644);
    return 0;
}

// Open the heartbeat file once, to be rewritten in place for the life of the run. Keeping the fd
// avoids an open/close pair every second in the mirror loop.
static int open_heartbeat_file(const char *heartbeat_file_path) {
    if (heartbeat_file_path == NULL) {
        return -1;
    }

    int fd = open(heartbeat_file_path, O_WRONLY | O_CREAT | O_CLOEXEC, 0644);
    if (fd < 0) {
        fprintf(stderr, "Failed to open heartbeat file %s: %s\n", heartbeat_file_path, strerror(errno));
        return -1;
    }
    // Explicit, since umask can strip bits off the O_CREAT mode: the app reads this root-owned file.
    chmod(heartbeat_file_path, 0644);
    return fd;
}

// One analog axis of the source controller, normalised so deflection reads as [-1, 1].
struct axis {
    unsigned short code;
    int present;
    int center;
    int half_range;
    int raw; // last raw value seen
};

// Populate an axis from EVIOCGABS. present stays 0 if the source lacks it or reports a null range.
static void query_axis(int source_fd, struct axis *axis, unsigned short code) {
    struct input_absinfo info;
    axis->code = code;
    axis->present = 0;
    axis->center = 0;
    axis->half_range = 1;
    axis->raw = 0;
    if (ioctl(source_fd, EVIOCGABS(code), &info) == 0 && info.maximum > info.minimum) {
        axis->present = 1;
        axis->center = (info.maximum + info.minimum) / 2;
        axis->half_range = (info.maximum - info.minimum) / 2;
        if (axis->half_range < 1) {
            axis->half_range = 1;
        }
        axis->raw = info.value;
    }
}

// Normalise raw deflection to [-1, 1], apply the deadzone, and square the magnitude for finer
// control near center. Returns 0 inside the deadzone.
static double axis_normalised(const struct axis *axis) {
    if (!axis->present || axis->half_range < 1) {
        return 0.0;
    }
    double n = (double)(axis->raw - axis->center) / (double)axis->half_range;
    if (n > 1.0) n = 1.0;
    if (n < -1.0) n = -1.0;
    if (fabs(n) < MOUSE_DEADZONE) {
        return 0.0;
    }
    double sign = n < 0 ? -1.0 : 1.0;
    double mag = (fabs(n) - MOUSE_DEADZONE) / (1.0 - MOUSE_DEADZONE);
    return sign * mag * mag;
}

// Create a uinput relative pointer (mouse). Returns the fd, or -1 on failure.
static int create_uinput_mouse(void) {
    int fd = open("/dev/uinput", O_RDWR | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) {
        fprintf(stderr, "open /dev/uinput failed: %s\n", strerror(errno));
        return -1;
    }

    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, BTN_LEFT) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_REL) < 0 ||
        ioctl(fd, UI_SET_RELBIT, REL_X) < 0 ||
        ioctl(fd, UI_SET_RELBIT, REL_Y) < 0 ||
        ioctl(fd, UI_SET_RELBIT, REL_WHEEL) < 0) {
        fprintf(stderr, "UI_SET_*BIT failed: %s\n", strerror(errno));
        close(fd);
        return -1;
    }

    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x2323;
    setup.id.product = 0x0001;
    setup.id.version = 1;
    snprintf(setup.name, sizeof(setup.name), "Docking Enhancer Mouse");

    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0) {
        fprintf(stderr, "UI_DEV_SETUP failed: %s\n", strerror(errno));
        close(fd);
        return -1;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        fprintf(stderr, "UI_DEV_CREATE failed: %s\n", strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

/*
 * A uinput touchscreen we keep alongside the mouse while in mouse mode, used to hide the cursor
 * instantly on the way out.
 *
 * Destroying our pointer isn't enough to hide the cursor: the Odin's own always-connected "ODIN
 * Station Virtual Mouse" keeps Android in "a mouse is present" state, so the cursor only fades after
 * its ~3s inactivity timeout. Android *does* hide it at once the moment a touch arrives (input
 * switches to touch mode). We create this device when ENTERING mouse mode so Android has fully
 * enumerated and opened it by the time we need it (a device created and used within the same
 * instant is never read — enumeration takes ~100ms+). On leaving mouse mode we push one touch-DOWN
 * through it and then destroy it mid-gesture: Android switches to touch mode (cursor hidden
 * immediately) and cancels the orphaned touch, so no tap/click is ever dispatched.
 */
static int create_touch_device(void) {
    int fd = open("/dev/uinput", O_RDWR | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) {
        return -1;
    }

    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);

    struct uinput_abs_setup abs;
    memset(&abs, 0, sizeof(abs));
    abs.code = ABS_MT_SLOT;
    abs.absinfo.maximum = 9;
    ioctl(fd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_TRACKING_ID;
    abs.absinfo.maximum = 65535;
    ioctl(fd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_POSITION_X;
    abs.absinfo.maximum = 32767;
    ioctl(fd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_POSITION_Y;
    abs.absinfo.maximum = 32767;
    ioctl(fd, UI_ABS_SETUP, &abs);

    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x2323;
    setup.id.product = 0x0002;
    setup.id.version = 1;
    // A real name is required or Android/the firmware ignores the device (the touch never registers
    // and the cursor won't hide). Because a named device unavoidably pops a "<name> connected" toast,
    // the name is chosen so that toast reads as an intentional mirror-activation message. The device
    // is created once per mirror start and kept for the whole session, so the toast shows once on
    // start (not on every mouse-mode toggle).
    snprintf(setup.name, sizeof(setup.name), "Docking Enhancer Mirror");

    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

// Push a touch-DOWN through the persistent touch device to switch Android into touch mode (hiding
// the mouse cursor immediately), then cancel the gesture so it is never completed as a tap/click —
// without destroying the device (which would re-trigger a "connected" toast on the next use).
//
// The cancel uses SYN_DROPPED: after the DOWN we set the device's state back to "no touch" but,
// instead of reporting it normally (which would be a tap), we emit SYN_DROPPED. Android's EventHub
// treats that as a lost-sync signal, discards the in-flight packet, re-reads the device state (now
// released) and cancels the active touch — no ACTION_UP, so nothing is clicked.
static void flush_touch_cancel(int touch_fd) {
    if (touch_fd < 0) {
        return;
    }
    // Touch DOWN at (0,0): coordinate is irrelevant because the gesture is cancelled.
    emit_event(touch_fd, EV_ABS, ABS_MT_SLOT, 0);
    emit_event(touch_fd, EV_ABS, ABS_MT_TRACKING_ID, 1);
    emit_event(touch_fd, EV_ABS, ABS_MT_POSITION_X, 0);
    emit_event(touch_fd, EV_ABS, ABS_MT_POSITION_Y, 0);
    emit_event(touch_fd, EV_KEY, BTN_TOUCH, 1);
    emit_event(touch_fd, EV_SYN, SYN_REPORT, 0);

    // Let Android read the DOWN (switch to touch mode → cursor hidden) before we cancel.
    usleep(40000);

    // Release the contact in the device state, then signal a dropped sync so Android discards this
    // packet and re-reads the (released) state as a cancel rather than a tap.
    emit_event(touch_fd, EV_ABS, ABS_MT_SLOT, 0);
    emit_event(touch_fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
    emit_event(touch_fd, EV_KEY, BTN_TOUCH, 0);
    emit_event(touch_fd, EV_SYN, SYN_DROPPED, 0);
    emit_event(touch_fd, EV_SYN, SYN_REPORT, 0);
}

// All state carried across mirror-loop iterations: the fds, the option flags, and the runtime state
// for the kill-app combo, the heartbeat, and virtual-mouse mode. Grouping it keeps the loop body a
// readable dispatcher and lets each feature live in its own handler.
struct mirror_state {
    int source_fd;
    int target_fd;
    int uinput_fd;  // virtual-mouse pointer (created on entering mouse mode)
    int touch_fd;   // cursor-hiding touch helper (created once per run)
    int control_fd; // command fifo the app pokes to ask for a config reload

    // Generation of the config currently in force, echoed in the heartbeat so the app can tell an
    // applied change from one still in flight.
    long long config_generation;

    const char *heartbeat_file_path;
    int heartbeat_fd;  // held open for the whole run; rewritten in place each beat
    int heartbeat_missing_beats;  // consecutive beats the heartbeat PATH has been gone
    int home_as_back;
    int combo_hold_kill_app;
    int virtual_mouse;
    int swap_nintendo_layout;
    // A Home press we swallowed and still owe a release for. Tracked so turning Home-as-Back off
    // mid-hold keeps swallowing that press to completion instead of handing the target a release
    // for a key it never saw go down.
    int home_swallowed;

    // Select+Start "close app" combo.
    int select_pressed;
    int start_pressed;
    int thumbr_pressed;
    int combo_triggered;
    long long combo_pressed_at_ms;

    long long last_heartbeat_ms;

    // Virtual-mouse runtime state.
    int mouse_mode;
    int mouse_combo_triggered;
    long long mouse_combo_pressed_at_ms;
    long long last_frame_ms;
    long long cursor_nudge_until_ms; // keep jiggling the pointer until this instant so it shows up
    double residual_x;  // carry sub-pixel cursor motion between frames
    double residual_y;
    double wheel_accum; // carry sub-click scroll between frames
    int left_click_down;
    int right_click_down;
    int shade_open;     // our view of whether R1 last opened the notification shade
    struct axis left_x, left_y, right_x, right_y;

    // Everything the TARGET currently believes held/deflected, tracked as events are forwarded, so
    // entering mouse mode can release all of it (see enter_mouse_mode).
    unsigned char key_down[KEY_CNT];
    int abs_present[ABS_CNT];
    int abs_neutral[ABS_CNT];
    int abs_last[ABS_CNT];
};

// Stamp the heartbeat by rewriting the held-open file in place. The watchdog on the app side only
// checks that the file is non-empty and its mtime is recent, and a write updates mtime — so this is
// equivalent to the old rewrite-from-scratch, minus an open/close pair per second. A write failure
// drops the fd so the next beat reopens, keeping the old code's self-healing behaviour.
static void write_heartbeat(struct mirror_state *s) {
    if (s->heartbeat_file_path == NULL) {
        return;
    }
    if (s->heartbeat_fd < 0) {
        s->heartbeat_fd = open_heartbeat_file(s->heartbeat_file_path);
    }

    // Owner watchdog. Deliberately tests the PATH, never the fd: unlinking a file does not disturb an
    // open description, so a daemon whose app was uninstalled goes on writing happily to an inode
    // nobody can reach — grabbing the user's controller and keeping its node hidden with no app left
    // to stop or heal it. The app deletes this path only when it wants this daemon gone, so treat a
    // sustained absence as our cue to leave through the normal shutdown, which restores what we hid.
    // Placed after the open above so the first beat CREATES the file instead of tripping on it.
    if (access(s->heartbeat_file_path, F_OK) != 0) {
        if (++s->heartbeat_missing_beats >= HEARTBEAT_MISSING_LIMIT) {
            fprintf(stderr, "heartbeat file %s missing for %d beats; shutting down\n",
                    s->heartbeat_file_path, s->heartbeat_missing_beats);
            keep_running = 0;
        }
        return;
    }
    s->heartbeat_missing_beats = 0;

    if (s->heartbeat_fd < 0) {
        return;
    }

    // Line 1 stays exactly the timestamp: the app judges liveness by this file's mtime and
    // non-emptiness, and nothing appended below may disturb that. Line 2 carries the generation of
    // the config actually in force, which is how a reload is acknowledged.
    char stamp[64];
    int length = snprintf(stamp, sizeof(stamp), "%lld\ngeneration %lld\n", now_ms(), s->config_generation);
    if (length <= 0 || (size_t)length >= sizeof(stamp)) {
        return;
    }

    if (pwrite(s->heartbeat_fd, stamp, (size_t)length, 0) != (ssize_t)length) {
        fprintf(stderr, "Failed to write heartbeat file %s: %s\n", s->heartbeat_file_path, strerror(errno));
        close(s->heartbeat_fd);
        s->heartbeat_fd = -1;
        return;
    }

    // Trim any tail left by a longer previous stamp. now_ms() is monotonic so this never actually
    // shortens in practice; keeping it makes the file exact regardless.
    if (ftruncate(s->heartbeat_fd, (off_t)length) != 0) {
        // Harmless: a stale tail byte doesn't affect the reader, which only checks mtime and size.
    }
}

// Poll timeout for the next iteration: fast while a hold combo is pending (to catch the threshold
// without new events) or in mouse mode (frame cadence), otherwise idle at 1s (heartbeat rate).
static int compute_loop_timeout(const struct mirror_state *s) {
    int timeout_ms = 1000;
    if (s->mouse_mode) {
        timeout_ms = (int)MOUSE_FRAME_INTERVAL_MS;
    } else if (s->combo_hold_kill_app && s->select_pressed && s->start_pressed && !s->combo_triggered) {
        timeout_ms = 50;
    }
    if (s->virtual_mouse && s->select_pressed && s->thumbr_pressed && !s->mouse_combo_triggered && timeout_ms > 50) {
        timeout_ms = 50;
    }
    return timeout_ms;
}

// Emit accumulated cursor/scroll motion on the frame cadence. A moving stick streams ABS events so
// poll rarely times out; the time gate throttles the actual emit rate to ~83 Hz either way.
static void emit_mouse_frame(struct mirror_state *s) {
    long long tick = now_ms();
    if (tick - s->last_frame_ms < MOUSE_FRAME_INTERVAL_MS) {
        return;
    }
    s->last_frame_ms = tick;
    s->residual_x += axis_normalised(&s->left_x) * MOUSE_SPEED;
    s->residual_y += axis_normalised(&s->left_y) * MOUSE_SPEED;
    int dx = (int)s->residual_x;
    int dy = (int)s->residual_y;
    s->residual_x -= dx;
    s->residual_y -= dy;

    // Scroll: right stick up scrolls up (positive wheel). Stick up reads negative, so invert Y.
    s->wheel_accum += (-axis_normalised(&s->right_y)) * WHEEL_STEP_PER_FRAME;
    int wheel = (int)s->wheel_accum;
    s->wheel_accum -= wheel;

    if (dx != 0 || dy != 0 || wheel != 0) {
        if (dx != 0) emit_event(s->uinput_fd, EV_REL, REL_X, dx);
        if (dy != 0) emit_event(s->uinput_fd, EV_REL, REL_Y, dy);
        if (wheel != 0) emit_event(s->uinput_fd, EV_REL, REL_WHEEL, wheel);
        emit_event(s->uinput_fd, EV_SYN, SYN_REPORT, 0);
    } else if (tick < s->cursor_nudge_until_ms) {
        // Idle inside the reveal window: step one pixel out and straight back, as two separate
        // reports. Each is real motion so the framework paints (and keeps) the pointer, while the
        // pair nets to zero, leaving the cursor exactly where the user last had it.
        emit_event(s->uinput_fd, EV_REL, REL_X, 1);
        emit_event(s->uinput_fd, EV_SYN, SYN_REPORT, 0);
        emit_event(s->uinput_fd, EV_REL, REL_X, -1);
        emit_event(s->uinput_fd, EV_SYN, SYN_REPORT, 0);
    }
}

// Enter mouse mode: create the pointer, then release everything the target believes held (every key
// forwarded as down and every axis away from its rest value) so the game sees no stuck buttons or
// deflected sticks while we stop forwarding. On creation failure, stay in gamepad mode.
static void enter_mouse_mode(struct mirror_state *s) {
    s->uinput_fd = create_uinput_mouse();
    if (s->uinput_fd < 0) {
        fprintf(stderr, "Virtual mouse: could not create uinput device; staying in gamepad mode\n");
        return;
    }
    s->mouse_mode = 1;
    for (int code = 0; code < KEY_CNT; code++) {
        if (s->key_down[code]) {
            emit_event(s->target_fd, EV_KEY, (unsigned short)code, 0);
            s->key_down[code] = 0;
        }
    }
    for (int code = 0; code < ABS_CNT; code++) {
        if (s->abs_present[code] && s->abs_last[code] != s->abs_neutral[code]) {
            emit_event(s->target_fd, EV_ABS, (unsigned short)code, s->abs_neutral[code]);
            s->abs_last[code] = s->abs_neutral[code];
        }
    }
    emit_event(s->target_fd, EV_SYN, SYN_REPORT, 0);
    s->residual_x = s->residual_y = s->wheel_accum = 0.0;
    s->left_click_down = s->right_click_down = 0;
    s->shade_open = 0;
    s->last_frame_ms = now_ms();
    s->cursor_nudge_until_ms = s->last_frame_ms + CURSOR_SHOW_NUDGE_MS;
}

// Leave mouse mode: release held clicks, destroy the pointer (so Android drops the on-screen cursor)
// and flush the touch helper to hide the cursor immediately instead of waiting for its fade.
static void leave_mouse_mode(struct mirror_state *s) {
    s->mouse_mode = 0;
    if (s->uinput_fd >= 0) {
        if (s->left_click_down) emit_event(s->uinput_fd, EV_KEY, BTN_LEFT, 0);
        if (s->right_click_down) emit_event(s->uinput_fd, EV_KEY, BTN_RIGHT, 0);
        emit_event(s->uinput_fd, EV_SYN, SYN_REPORT, 0);
        ioctl(s->uinput_fd, UI_DEV_DESTROY);
        close(s->uinput_fd);
        s->uinput_fd = -1;
    }
    s->left_click_down = s->right_click_down = 0;
    flush_touch_cancel(s->touch_fd);
}

/*
 * Adopt a freshly parsed config.
 *
 * The reason this is not `*current = *next` is that a flag turning OFF may own live state only the
 * old value knows how to unwind. Mouse mode is the clear case: dropping --virtual-mouse while the
 * pointer exists would strand a uinput device and a visible cursor with nothing left that would ever
 * destroy them. So unwind first against the OLD values, then adopt.
 *
 * Turning a flag ON needs no such care — every feature here starts from an idle state.
 */
static void apply_config(struct mirror_state *s, const struct config *next) {
    if (s->virtual_mouse && !next->virtual_mouse) {
        if (s->mouse_mode) {
            leave_mouse_mode(s);
        }
        s->mouse_combo_triggered = 0;
        s->mouse_combo_pressed_at_ms = 0;
    }
    if (s->combo_hold_kill_app && !next->combo_hold_kill_app) {
        // Drop a hold in progress so re-enabling later doesn't inherit a stale, already-expired
        // timer and fire the moment both buttons are next seen down.
        s->combo_triggered = 0;
        s->combo_pressed_at_ms = 0;
    }

    s->home_as_back = next->home_as_back;
    s->combo_hold_kill_app = next->combo_hold_kill_app;
    s->virtual_mouse = next->virtual_mouse;
    s->config_generation = next->generation;
}

// Read whatever the app queued on the control fifo and report whether a reload was asked for.
// Commands are one per line and unknown ones are ignored, so a newer app talking to an older daemon
// degrades to "no-op" rather than to a parse error.
static int drain_control(int fd) {
    char buffer[256];
    int reload = 0;

    for (;;) {
        ssize_t got = read(fd, buffer, sizeof(buffer) - 1);
        if (got <= 0) {
            break;
        }
        buffer[got] = '\0';
        // A command is a short word written in one call, and a write below PIPE_BUF is atomic, so a
        // command never arrives split across two reads.
        if (strstr(buffer, "reload") != NULL) {
            reload = 1;
        }
    }
    return reload;
}

// Flip mouse mode if Select+R3 has been held past the threshold. Checked every loop because the hold
// may complete on a poll timeout with no new event.
static void maybe_toggle_mouse_mode(struct mirror_state *s) {
    if (!s->virtual_mouse || !s->select_pressed || !s->thumbr_pressed || s->mouse_combo_triggered ||
        now_ms() - s->mouse_combo_pressed_at_ms < MOUSE_TOGGLE_HOLD_MS) {
        return;
    }
    s->mouse_combo_triggered = 1;
    if (!s->mouse_mode) {
        enter_mouse_mode(s);
    } else {
        leave_mouse_mode(s);
    }
}

// Fire the kill-app combo when it completes on a poll timeout (no new event to carry it over).
static void maybe_kill_combo_on_timeout(struct mirror_state *s) {
    if (s->combo_hold_kill_app && s->select_pressed && s->start_pressed && !s->combo_triggered &&
        now_ms() - s->combo_pressed_at_ms >= COMBO_HOLD_KILL_APP_MS) {
        force_stop_foreground_app();
        s->combo_triggered = 1;
    }
}

// Track Select/Start/R3 press state (read by both combos) and (re)arm the mouse-toggle hold. Only a
// combo key's own press may (re)arm the timer, so another button pressed while both are held can't
// reset an in-progress hold. Called for every EV_KEY event.
static void update_combo_tracking(struct mirror_state *s, const struct input_event *ev) {
    if (is_select_button(ev->code)) {
        if (ev->value == 1) s->select_pressed = 1;
        else if (ev->value == 0) s->select_pressed = 0;
    } else if (is_start_button(ev->code)) {
        if (ev->value == 1) s->start_pressed = 1;
        else if (ev->value == 0) s->start_pressed = 0;
    } else if (ev->code == BTN_THUMBR) {
        if (ev->value == 1) s->thumbr_pressed = 1;
        else if (ev->value == 0) s->thumbr_pressed = 0;
    }

    if (s->virtual_mouse) {
        int is_mouse_combo_key = is_select_button(ev->code) || ev->code == BTN_THUMBR;
        if (is_mouse_combo_key && ev->value == 1 && s->select_pressed && s->thumbr_pressed) {
            s->mouse_combo_pressed_at_ms = now_ms();
            s->mouse_combo_triggered = 0;
        }
        if (ev->value == 0 && (!s->select_pressed || !s->thumbr_pressed)) {
            s->mouse_combo_triggered = 0;
        }
    }
}

// Home-as-Back: swallow the Home button entirely (press, repeat and release) so the target never
// sees a Home-down without its up, and inject Back once, on release. Returns 1 if the event was
// consumed (must not be forwarded).
static int handle_home_as_back(struct mirror_state *s, const struct input_event *ev) {
    if (ev->type != EV_KEY || !is_home_button(ev->code)) {
        return 0;
    }
    // Keep swallowing a press we already swallowed even if the setting was turned off mid-hold: the
    // target never saw that key go down, so letting its release through would be a release out of
    // nowhere. The press finishes under the rules it started with.
    if (!s->home_as_back && !s->home_swallowed) {
        return 0;
    }
    if (ev->value == 1) {
        s->home_swallowed = 1;
    } else if (ev->value == 0) {
        s->home_swallowed = 0;
        run_detached("input keyevent 4");
    }
    return 1;
}

// Advance the Select+Start "close app" combo on a Select/Start event.
static void handle_kill_combo_event(struct mirror_state *s, const struct input_event *ev) {
    if (!s->combo_hold_kill_app || ev->type != EV_KEY ||
        !(is_select_button(ev->code) || is_start_button(ev->code))) {
        return;
    }
    if (ev->value == 1 && s->select_pressed && s->start_pressed) {
        s->combo_pressed_at_ms = now_ms();
        s->combo_triggered = 0;
    }
    if ((ev->value == 2 || ev->value == 0) && s->select_pressed && s->start_pressed &&
        !s->combo_triggered && now_ms() - s->combo_pressed_at_ms >= COMBO_HOLD_KILL_APP_MS) {
        force_stop_foreground_app();
        s->combo_triggered = 1;
    }
    if (ev->value == 0 && !s->select_pressed && !s->start_pressed) {
        s->combo_triggered = 0;
        s->combo_pressed_at_ms = 0;
    }
}

// In mouse mode the source drives the pointer instead of the target: track stick positions (motion
// is emitted on the frame cadence), map A/B to clicks, and R1 to the notification shade.
static void handle_mouse_event(struct mirror_state *s, const struct input_event *ev) {
    if (ev->type == EV_ABS) {
        if (ev->code == s->left_x.code && s->left_x.present) s->left_x.raw = ev->value;
        else if (ev->code == s->left_y.code && s->left_y.present) s->left_y.raw = ev->value;
        else if (ev->code == s->right_x.code && s->right_x.present) s->right_x.raw = ev->value;
        else if (ev->code == s->right_y.code && s->right_y.present) s->right_y.raw = ev->value;
    } else if (ev->type == EV_KEY && ev->code == BTN_SOUTH) {
        s->left_click_down = ev->value ? 1 : 0;
        emit_event(s->uinput_fd, EV_KEY, BTN_LEFT, s->left_click_down);
        emit_event(s->uinput_fd, EV_SYN, SYN_REPORT, 0);
    } else if (ev->type == EV_KEY && ev->code == BTN_EAST) {
        s->right_click_down = ev->value ? 1 : 0;
        emit_event(s->uinput_fd, EV_KEY, BTN_RIGHT, s->right_click_down);
        emit_event(s->uinput_fd, EV_SYN, SYN_REPORT, 0);
    } else if (ev->type == EV_KEY && ev->code == BTN_TR && ev->value == 1) {
        // R1 toggles Android's notification shade (a mouse can't drag it down). Commands are
        // idempotent enough that a stale shade_open just costs one extra press.
        if (s->shade_open) {
            run_detached("cmd statusbar collapse");
            s->shade_open = 0;
        } else {
            run_detached("cmd statusbar expand-notifications");
            s->shade_open = 1;
        }
    }
}

// Forward one event to the target: apply the Nintendo face-button swap, record what the target now
// believes held/deflected (for mouse-mode entry), and write it tolerantly. Returns 0, or -1 if the
// target write kept failing.
static int forward_event(struct mirror_state *s, struct input_event *ev) {
    if (s->swap_nintendo_layout && ev->type == EV_KEY) {
        ev->code = swap_nintendo_face_button(ev->code);
    }
    // Post-swap: this is what the TARGET believes.
    if (ev->type == EV_KEY && ev->code < KEY_CNT) {
        s->key_down[ev->code] = ev->value != 0;
    } else if (ev->type == EV_ABS && ev->code < ABS_CNT) {
        s->abs_last[ev->code] = ev->value;
    }
    return write_event_tolerant(s->target_fd, ev);
}

int main(int argc, char **argv) {
    // Heal mode (no mirroring): restore nodes a crashed session left hidden, then exit. Invoked as
    // `input_mirror --heal --hidden-state-file PATH`. Detected up front so the source/target
    // positional args aren't required.
    {
        int heal = 0;
        const char *state = NULL;
        for (int i = 1; i < argc; i++) {
            if (strcmp(argv[i], "--heal") == 0) {
                heal = 1;
            } else if (strcmp(argv[i], "--hidden-state-file") == 0 && i + 1 < argc) {
                state = argv[++i];
            }
        }
        if (heal) {
            restore_from_state_file(state);
            return EXIT_SUCCESS;
        }
    }

    if (argc < 3) {
        fprintf(stderr, "Usage: %s /dev/input/eventSOURCE /dev/input/eventTARGET [--home-as-back] [--combo-hold-kill-app] [--virtual-mouse] [--hide-node PATH]... [--hidden-state-file PATH] [--config-file PATH] [--control-fifo PATH] [--pid-file PATH] [--heartbeat-file PATH]\n       %s --heal --hidden-state-file PATH\n", argv[0], argv[0]);
        return EXIT_FAILURE;
    }

    const char *source_path = argv[1];
    const char *target_path = argv[2];
    const char *pid_file_path = NULL;
    const char *heartbeat_file_path = NULL;
    const char *hidden_state_path = NULL;
    const char *config_path = NULL;
    const char *control_fifo_path = NULL;
    int home_as_back = 0;
    int combo_hold_kill_app = 0;
    int virtual_mouse = 0;
    const char *hide_paths[MAX_HIDE_NODES];
    int hide_count = 0;

    for (int index = 3; index < argc; index++) {
        if (strcmp(argv[index], "--home-as-back") == 0) {
            home_as_back = 1;
            continue;
        }

        if (strcmp(argv[index], "--hidden-state-file") == 0 && index + 1 < argc) {
            hidden_state_path = argv[++index];
            continue;
        }

        if (strcmp(argv[index], "--hide-node") == 0 && index + 1 < argc) {
            if (hide_count < MAX_HIDE_NODES) {
                hide_paths[hide_count++] = argv[++index];
            } else {
                index++;  // drop the value; capacity reached
            }
            continue;
        }

        if (strcmp(argv[index], "--combo-hold-kill-app") == 0) {
            combo_hold_kill_app = 1;
            continue;
        }

        if (strcmp(argv[index], "--virtual-mouse") == 0) {
            virtual_mouse = 1;
            continue;
        }

        if (strcmp(argv[index], "--config-file") == 0 && index + 1 < argc) {
            config_path = argv[++index];
            continue;
        }

        if (strcmp(argv[index], "--control-fifo") == 0 && index + 1 < argc) {
            control_fifo_path = argv[++index];
            continue;
        }

        if (strcmp(argv[index], "--pid-file") == 0 && index + 1 < argc) {
            pid_file_path = argv[++index];
            continue;
        }

        if (strcmp(argv[index], "--heartbeat-file") == 0 && index + 1 < argc) {
            heartbeat_file_path = argv[++index];
            continue;
        }

        fprintf(stderr, "Unknown argument: %s\n", argv[index]);
        return EXIT_FAILURE;
    }

    signal(SIGINT, handle_signal);
    signal(SIGTERM, handle_signal);
    signal(SIGHUP, handle_signal);
    // Auto-reap the short-lived children spawned by run_detached so they never linger as zombies.
    signal(SIGCHLD, SIG_IGN);

    struct mirror_state s;
    memset(&s, 0, sizeof(s));
    s.uinput_fd = -1;
    s.touch_fd = -1;
    s.control_fd = -1;
    s.heartbeat_fd = -1;
    s.heartbeat_file_path = heartbeat_file_path;
    s.home_as_back = home_as_back;
    s.combo_hold_kill_app = combo_hold_kill_app;
    s.virtual_mouse = virtual_mouse;
    s.last_frame_ms = now_ms();

    s.source_fd = open(source_path, O_RDONLY | O_CLOEXEC);
    if (s.source_fd < 0) {
        fprintf(stderr, "Failed to open source %s: %s\n", source_path, strerror(errno));
        return EXIT_FAILURE;
    }

    s.target_fd = open(target_path, O_RDWR | O_CLOEXEC);
    if (s.target_fd < 0) {
        fprintf(stderr, "Failed to open target %s: %s\n", target_path, strerror(errno));
        close(s.source_fd);
        return EXIT_FAILURE;
    }

    struct input_id target_id;
    if (ioctl(s.target_fd, EVIOCGID, &target_id) == 0) {
        s.swap_nintendo_layout =
            target_id.vendor == ODIN_VENDOR_ID &&
            target_id.product == ODIN_NINTENDO_PRODUCT_ID;
    }

    // Virtual mouse setup: resolve which axes to read. Left stick (ABS_X/ABS_Y) is universal; the
    // right stick used for scroll varies, so probe ABS_RX/RY and fall back to ABS_Z/RZ (e.g. 8BitDo
    // exposes the right stick as ABS_Z/ABS_RZ). The abs_* arrays capture the source's rest state so
    // entering mouse mode can neutralise a held trigger/stick (enter_mouse_mode): a trigger's rest
    // value is its minimum, so a sampled-at-startup neutral avoids half-pressing it.
    //
    // Done unconditionally, even with the virtual mouse off. The setting can now be turned ON at
    // runtime through a config reload, and by then this is the only chance we had to read the
    // source's axis map — the pad is grabbed and its rest values long since moved.
    {
        query_axis(s.source_fd, &s.left_x, ABS_X);
        query_axis(s.source_fd, &s.left_y, ABS_Y);
        query_axis(s.source_fd, &s.right_x, ABS_RX);
        query_axis(s.source_fd, &s.right_y, ABS_RY);
        if (!s.right_x.present || !s.right_y.present) {
            query_axis(s.source_fd, &s.right_x, ABS_Z);
            query_axis(s.source_fd, &s.right_y, ABS_RZ);
        }

        unsigned long abs_bits[(ABS_CNT + 8 * sizeof(unsigned long) - 1) / (8 * sizeof(unsigned long))];
        memset(abs_bits, 0, sizeof(abs_bits));
        if (ioctl(s.source_fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits) >= 0) {
            for (int code = 0; code < ABS_CNT; code++) {
                size_t word = (size_t)code / (8 * sizeof(unsigned long));
                unsigned long bit = 1UL << ((size_t)code % (8 * sizeof(unsigned long)));
                if (!(abs_bits[word] & bit)) {
                    continue;
                }
                struct input_absinfo info;
                if (ioctl(s.source_fd, EVIOCGABS(code), &info) == 0) {
                    s.abs_present[code] = 1;
                    s.abs_neutral[code] = info.value;
                    s.abs_last[code] = info.value;
                }
            }
        }
    }

    // A config file, when present, is the authority; the command-line flags are only what to start
    // from if it is missing or unreadable. Reading it here also means a daemon restarted for an
    // unrelated reason (a reconnect, a crash) comes back with whatever the user last chose rather
    // than with the flags of whichever launch happened to create it.
    if (config_path != NULL) {
        struct config from_flags = {
            .home_as_back = s.home_as_back,
            .combo_hold_kill_app = s.combo_hold_kill_app,
            .virtual_mouse = s.virtual_mouse,
            .generation = 0,
        };
        struct config initial = from_flags;
        if (parse_config(config_path, &from_flags, &initial) == 0) {
            apply_config(&s, &initial);
        }
    }

    s.control_fd = open_control_fifo(control_fifo_path);

    // Create the touch helper on every mirror start, regardless of the virtual-mouse setting: it is
    // what hides the cursor on mouse-mode exit when enabled, and its unavoidable "connected" toast
    // ("Docking Enhancer Mirror connected") doubles as a consistent mirror-activation notification on
    // each start. Created once and kept for the whole session so the toast doesn't repeat per toggle.
    s.touch_fd = create_touch_device();

    if (ioctl(s.source_fd, EVIOCGRAB, 1) < 0) {
        fprintf(stderr, "Failed to grab source %s: %s\n", source_path, strerror(errno));
        if (s.touch_fd >= 0) {
            ioctl(s.touch_fd, UI_DEV_DESTROY);
            close(s.touch_fd);
        }
        close(s.target_fd);
        close(s.source_fd);
        return EXIT_FAILURE;
    }

    write_pid_file(pid_file_path);
    write_heartbeat(&s);

    // Heal anything a previous crashed session left hidden before we hide this session's set —
    // self-cleaning across hard kills, independent of which controller is now connected.
    restore_from_state_file(hidden_state_path);

    // Hide requested duplicate nodes now that the mirror is committed (source grabbed). Done after
    // the grab so a failed start never disturbs the framework's device list. Record the hidden set
    // so a crash before clean exit can be healed.
    struct hidden_node hidden[MAX_HIDE_NODES];
    memset(hidden, 0, sizeof(hidden));
    for (int i = 0; i < hide_count; i++) {
        hide_node(hide_paths[i], &hidden[i], ODIN_VENDOR_ID);
    }
    write_hidden_state(hidden_state_path, hidden, hide_count);

    while (keep_running) {
        int timeout_ms = compute_loop_timeout(&s);

        long long heartbeat_now_ms = now_ms();
        if (heartbeat_now_ms - s.last_heartbeat_ms >= HEARTBEAT_INTERVAL_MS) {
            write_heartbeat(&s);
            s.last_heartbeat_ms = heartbeat_now_ms;
        }

        if (s.mouse_mode) {
            emit_mouse_frame(&s);
        }

        // Watch the source and the control fifo together. Adding the fifo here rather than checking
        // it on a timer is what keeps a settings change effectively instant while leaving the grab,
        // and the forwarding path, completely untouched.
        struct pollfd fds[2];
        memset(fds, 0, sizeof(fds));
        fds[0].fd = s.source_fd;
        fds[0].events = POLLIN;
        int poll_count = 1;
        int control_index = -1;
        if (s.control_fd >= 0) {
            fds[1].fd = s.control_fd;
            fds[1].events = POLLIN;
            control_index = 1;
            poll_count = 2;
        }

        int poll_result = poll(fds, (nfds_t)poll_count, timeout_ms);
        if (poll_result < 0) {
            if (errno == EINTR) {
                continue;
            }
            fprintf(stderr, "Poll error from %s: %s\n", source_path, strerror(errno));
            break;
        }

        // The mouse toggle may complete on a poll timeout with no new event, so check it every loop.
        maybe_toggle_mouse_mode(&s);

        // Settings first: applying them before this batch of events means the events are handled
        // under the config the user has already asked for, never under the one they just replaced.
        if (control_index >= 0 && (fds[control_index].revents & POLLIN) && drain_control(s.control_fd)) {
            struct config running = {
                .home_as_back = s.home_as_back,
                .combo_hold_kill_app = s.combo_hold_kill_app,
                .virtual_mouse = s.virtual_mouse,
                .generation = s.config_generation,
            };
            struct config next = running;
            if (parse_config(config_path, &running, &next) == 0) {
                apply_config(&s, &next);
            } else {
                // Keep running exactly as we were. The app notices because the generation it is
                // waiting for never shows up in the heartbeat, and can put its toggle back.
                fprintf(stderr, "reload: keeping the running config\n");
            }
        }

        if (!(fds[0].revents & POLLIN)) {
            maybe_kill_combo_on_timeout(&s);
            continue;
        }

        // Drain every event the source has queued in one read(). The mode is decided once per
        // batch (maybe_toggle_mouse_mode above); a batch spans microseconds, far below the 500ms
        // toggle hold, so processing it under a single mode matches the old event-at-a-time loop.
        struct input_event events[EVENT_BATCH_SIZE];
        ssize_t bytes_read = read(s.source_fd, events, sizeof(events));
        if (bytes_read == 0) {
            break;
        }
        if (bytes_read < 0) {
            if (errno == EINTR) {
                continue;
            }
            fprintf(stderr, "Read error from %s: %s\n", source_path, strerror(errno));
            break;
        }
        size_t event_count = (size_t)bytes_read / sizeof(struct input_event);
        if (event_count == 0) {
            fprintf(stderr, "Short read from %s: %zd bytes\n", source_path, bytes_read);
            continue;
        }

        int fatal_write_error = 0;
        for (size_t i = 0; i < event_count; i++) {
            struct input_event *event = &events[i];

            // Track Select/Start/R3 for both combos, regardless of which feature is enabled.
            if (event->type == EV_KEY) {
                update_combo_tracking(&s, event);
            }

            if (handle_home_as_back(&s, event)) {
                continue;
            }

            handle_kill_combo_event(&s, event);

            if (s.mouse_mode) {
                handle_mouse_event(&s, event);
                continue;
            }

            if (forward_event(&s, event) != 0) {
                fprintf(stderr, "Write error to %s: %s\n", target_path, strerror(errno));
                fatal_write_error = 1;
                break;
            }
        }
        if (fatal_write_error) {
            break;
        }
    }

    // Restore the hidden nodes FIRST — highest-priority cleanup, so a KILL escalation racing this
    // shutdown is least likely to leave them orphaned. Then drop the state file: a clean exit has
    // nothing left to heal.
    restore_hidden_nodes(hidden, hide_count);
    if (hidden_state_path != NULL) {
        // Drop the file only on a restore that actually settled everything. If a node is still
        // hidden, leave the record behind: the supervisor's next heal is the user's only remaining
        // path back to a visible controller.
        if (count_active_hidden(hidden, hide_count) > 0) {
            write_hidden_state(hidden_state_path, hidden, hide_count);
        } else {
            unlink(hidden_state_path);
        }
    }

    if (ioctl(s.source_fd, EVIOCGRAB, 0) < 0) {
        fprintf(stderr, "Failed to release source %s: %s\n", source_path, strerror(errno));
    }

    // Shut mouse mode down the same way the user's own toggle does. Destroying the pointer device
    // is not enough on its own: Android goes on drawing the cursor until it fades, so stopping the
    // mirror from within mouse mode used to strand it on screen. leave_mouse_mode also flushes the
    // touch helper, which is what actually takes it away — hence this runs while touch_fd is still
    // alive, and before the plain destroy below (which is then a no-op).
    if (s.mouse_mode) {
        leave_mouse_mode(&s);
    }
    if (s.uinput_fd >= 0) {
        ioctl(s.uinput_fd, UI_DEV_DESTROY);
        close(s.uinput_fd);
    }
    if (s.touch_fd >= 0) {
        ioctl(s.touch_fd, UI_DEV_DESTROY);
        close(s.touch_fd);
    }
    if (s.control_fd >= 0) {
        close(s.control_fd);
    }
    close(s.target_fd);
    close(s.source_fd);
    if (pid_file_path != NULL) {
        unlink(pid_file_path);
    }
    if (s.heartbeat_fd >= 0) {
        close(s.heartbeat_fd);
    }
    if (heartbeat_file_path != NULL) {
        unlink(heartbeat_file_path);
    }
    return EXIT_SUCCESS;
}
