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

static volatile sig_atomic_t keep_running = 1;
static const long long COMBO_HOLD_KILL_APP_MS = 3000;
static const long long HEARTBEAT_INTERVAL_MS = 1000;

// Virtual mouse mode (opt-in via --virtual-mouse). Select+R3 held this long toggles the mode; while
// on, the grabbed controller drives a self-created uinput pointer instead of the target node.
static const long long MOUSE_TOGGLE_HOLD_MS = 500;
static const long long MOUSE_FRAME_INTERVAL_MS = 12; // ~83 Hz cursor/scroll updates
static const double MOUSE_SPEED = 18.0;              // max cursor pixels per frame at full deflection
static const double MOUSE_DEADZONE = 0.18;           // fraction of stick travel ignored around center
static const double WHEEL_STEP_PER_FRAME = 0.30;     // scroll clicks accumulated per frame at full deflection

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
    chmod(pid_file_path, 0666);
    return 0;
}

static int write_heartbeat_file(const char *heartbeat_file_path) {
    if (heartbeat_file_path == NULL) {
        return 0;
    }

    FILE *heartbeat_file = fopen(heartbeat_file_path, "w");
    if (heartbeat_file == NULL) {
        fprintf(stderr, "Failed to open heartbeat file %s: %s\n", heartbeat_file_path, strerror(errno));
        return -1;
    }

    fprintf(heartbeat_file, "%lld\n", now_ms());
    fclose(heartbeat_file);
    chmod(heartbeat_file_path, 0666);
    return 0;
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
    // and the cursor won't hide). Because a named device pops a "connected" toast, this device is
    // created ONCE at daemon startup and kept for the whole session, so the toast only appears once
    // (lost among the boot device-connect toasts) instead of on every mouse-mode toggle.
    snprintf(setup.name, sizeof(setup.name), "Docking Enhancer Touch");

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

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "Usage: %s /dev/input/eventSOURCE /dev/input/eventTARGET [--home-as-back] [--combo-hold-kill-app] [--virtual-mouse] [--pid-file PATH] [--heartbeat-file PATH]\n", argv[0]);
        return EXIT_FAILURE;
    }

    const char *source_path = argv[1];
    const char *target_path = argv[2];
    const char *pid_file_path = NULL;
    const char *heartbeat_file_path = NULL;
    int home_as_back = 0;
    int combo_hold_kill_app = 0;
    int virtual_mouse = 0;

    for (int index = 3; index < argc; index++) {
        if (strcmp(argv[index], "--home-as-back") == 0) {
            home_as_back = 1;
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

    int source_fd = open(source_path, O_RDONLY | O_CLOEXEC);
    if (source_fd < 0) {
        fprintf(stderr, "Failed to open source %s: %s\n", source_path, strerror(errno));
        return EXIT_FAILURE;
    }

    int target_fd = open(target_path, O_RDWR | O_CLOEXEC);
    if (target_fd < 0) {
        fprintf(stderr, "Failed to open target %s: %s\n", target_path, strerror(errno));
        close(source_fd);
        return EXIT_FAILURE;
    }

    int swap_nintendo_layout = 0;
    struct input_id target_id;
    if (ioctl(target_fd, EVIOCGID, &target_id) == 0) {
        swap_nintendo_layout =
            target_id.vendor == ODIN_VENDOR_ID &&
            target_id.product == ODIN_NINTENDO_PRODUCT_ID;
    }

    // Virtual mouse setup: resolve which axes to read. Left stick (ABS_X/ABS_Y) is universal; the
    // right stick used for scroll varies, so probe ABS_RX/RY and fall back to ABS_Z/RZ (e.g. 8BitDo
    // exposes the right stick as ABS_Z/ABS_RZ). The uinput pointer itself is created on demand when
    // entering mouse mode and destroyed on leaving, so Android drops the on-screen cursor as soon as
    // mouse mode is turned off (a still-connected mouse keeps the pointer visible in dock mode).
    int uinput_fd = -1;
    int touch_fd = -1;
    struct axis left_x, left_y, right_x, right_y;
    memset(&left_x, 0, sizeof(left_x));
    memset(&left_y, 0, sizeof(left_y));
    memset(&right_x, 0, sizeof(right_x));
    memset(&right_y, 0, sizeof(right_y));
    if (virtual_mouse) {
        query_axis(source_fd, &left_x, ABS_X);
        query_axis(source_fd, &left_y, ABS_Y);
        query_axis(source_fd, &right_x, ABS_RX);
        query_axis(source_fd, &right_y, ABS_RY);
        if (!right_x.present || !right_y.present) {
            query_axis(source_fd, &right_x, ABS_Z);
            query_axis(source_fd, &right_y, ABS_RZ);
        }
        // Create the touch-flush helper once now (kept for the whole session) so its "connected"
        // toast fires a single time at startup rather than on every mouse-mode toggle, and so it is
        // fully enumerated by the time we need it to hide the cursor.
        touch_fd = create_touch_device();
    }

    if (ioctl(source_fd, EVIOCGRAB, 1) < 0) {
        fprintf(stderr, "Failed to grab source %s: %s\n", source_path, strerror(errno));
        if (uinput_fd >= 0) {
            ioctl(uinput_fd, UI_DEV_DESTROY);
            close(uinput_fd);
        }
        close(target_fd);
        close(source_fd);
        return EXIT_FAILURE;
    }

    write_pid_file(pid_file_path);
    write_heartbeat_file(heartbeat_file_path);

    struct input_event event;
    int select_pressed = 0;
    int start_pressed = 0;
    int thumbr_pressed = 0;

    int combo_triggered = 0;
    long long combo_pressed_at_ms = 0;
    long long last_heartbeat_ms = 0;

    // Virtual mouse runtime state.
    int mouse_mode = 0;
    int mouse_combo_triggered = 0;
    long long mouse_combo_pressed_at_ms = 0;
    long long last_frame_ms = now_ms();
    double residual_x = 0.0; // carry sub-pixel cursor motion between frames
    double residual_y = 0.0;
    double wheel_accum = 0.0; // carry sub-click scroll between frames
    int left_click_down = 0;
    int right_click_down = 0;

    while (keep_running) {
        int timeout_ms = 1000;
        if (mouse_mode) {
            timeout_ms = (int)MOUSE_FRAME_INTERVAL_MS;
        } else if (
            combo_hold_kill_app &&
            select_pressed &&
            start_pressed &&
            !combo_triggered
        ) {
            timeout_ms = 50;
        }
        // A pending mouse-toggle hold needs a short timeout to catch the threshold without new events.
        if (virtual_mouse && select_pressed && thumbr_pressed && !mouse_combo_triggered && timeout_ms > 50) {
            timeout_ms = 50;
        }

        long long heartbeat_now_ms = now_ms();
        if (heartbeat_now_ms - last_heartbeat_ms >= HEARTBEAT_INTERVAL_MS) {
            write_heartbeat_file(heartbeat_file_path);
            last_heartbeat_ms = heartbeat_now_ms;
        }

        // Emit accumulated cursor/scroll motion on the frame cadence. Done at the top of the loop
        // (not only on poll timeout) because a moving stick streams ABS events, so poll rarely
        // times out; the time gate throttles the actual emit rate to ~83 Hz either way.
        if (mouse_mode) {
            long long tick = now_ms();
            if (tick - last_frame_ms >= MOUSE_FRAME_INTERVAL_MS) {
                last_frame_ms = tick;
                double nx = axis_normalised(&left_x);
                double ny = axis_normalised(&left_y);
                residual_x += nx * MOUSE_SPEED;
                residual_y += ny * MOUSE_SPEED;
                int dx = (int)residual_x;
                int dy = (int)residual_y;
                residual_x -= dx;
                residual_y -= dy;

                // Scroll: right stick up scrolls up (positive wheel). Stick up reads negative, so
                // invert the Y deflection.
                wheel_accum += (-axis_normalised(&right_y)) * WHEEL_STEP_PER_FRAME;
                int wheel = (int)wheel_accum;
                wheel_accum -= wheel;

                if (dx != 0 || dy != 0 || wheel != 0) {
                    if (dx != 0) emit_event(uinput_fd, EV_REL, REL_X, dx);
                    if (dy != 0) emit_event(uinput_fd, EV_REL, REL_Y, dy);
                    if (wheel != 0) emit_event(uinput_fd, EV_REL, REL_WHEEL, wheel);
                    emit_event(uinput_fd, EV_SYN, SYN_REPORT, 0);
                }
            }
        }

        struct pollfd source_poll;
        source_poll.fd = source_fd;
        source_poll.events = POLLIN;
        source_poll.revents = 0;

        int poll_result = poll(&source_poll, 1, timeout_ms);
        if (poll_result < 0) {
            if (errno == EINTR) {
                continue;
            }
            fprintf(stderr, "Poll error from %s: %s\n", source_path, strerror(errno));
            break;
        }

        // Fire the mouse toggle from a timeout too (the hold may complete with no new events).
        if (
            virtual_mouse &&
            select_pressed &&
            thumbr_pressed &&
            !mouse_combo_triggered &&
            now_ms() - mouse_combo_pressed_at_ms >= MOUSE_TOGGLE_HOLD_MS
        ) {
            mouse_combo_triggered = 1;
            if (!mouse_mode) {
                // Entering mouse mode: create the pointer now so Android shows a cursor, then
                // neutralise everything we hold on the target so the game sees no stuck combo
                // buttons or deflected sticks while we stop forwarding. If creation fails, stay in
                // gamepad mode.
                uinput_fd = create_uinput_mouse();
                if (uinput_fd < 0) {
                    fprintf(stderr, "Virtual mouse: could not create uinput device; staying in gamepad mode\n");
                } else {
                    mouse_mode = 1;
                    emit_event(target_fd, EV_KEY, BTN_SELECT, 0);
                    emit_event(target_fd, EV_KEY, BTN_START, 0);
                    emit_event(target_fd, EV_KEY, BTN_THUMBR, 0);
                    if (left_x.present) emit_event(target_fd, EV_ABS, left_x.code, left_x.center);
                    if (left_y.present) emit_event(target_fd, EV_ABS, left_y.code, left_y.center);
                    if (right_x.present) emit_event(target_fd, EV_ABS, right_x.code, right_x.center);
                    if (right_y.present) emit_event(target_fd, EV_ABS, right_y.code, right_y.center);
                    emit_event(target_fd, EV_SYN, SYN_REPORT, 0);
                    residual_x = residual_y = wheel_accum = 0.0;
                    left_click_down = right_click_down = 0;
                    last_frame_ms = now_ms();
                }
            } else {
                // Leaving mouse mode: release any held clicks, then destroy the pointer so Android
                // removes the on-screen cursor (a still-connected mouse would keep it visible).
                mouse_mode = 0;
                if (uinput_fd >= 0) {
                    if (left_click_down) emit_event(uinput_fd, EV_KEY, BTN_LEFT, 0);
                    if (right_click_down) emit_event(uinput_fd, EV_KEY, BTN_RIGHT, 0);
                    emit_event(uinput_fd, EV_SYN, SYN_REPORT, 0);
                    ioctl(uinput_fd, UI_DEV_DESTROY);
                    close(uinput_fd);
                    uinput_fd = -1;
                }
                left_click_down = right_click_down = 0;
                // Force the cursor off the screen immediately (switch Android to touch mode) instead
                // of waiting for its inactivity fade. The persistent touch device is kept alive so it
                // doesn't re-announce itself; the gesture is cancelled rather than destroyed.
                flush_touch_cancel(touch_fd);
            }
        }

        if (poll_result == 0) {
            if (
                combo_hold_kill_app &&
                select_pressed &&
                start_pressed &&
                !combo_triggered &&
                now_ms() - combo_pressed_at_ms >= COMBO_HOLD_KILL_APP_MS
            ) {
                force_stop_foreground_app();
                combo_triggered = 1;
            }
            continue;
        }

        ssize_t bytes_read = read(source_fd, &event, sizeof(event));
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

        if ((size_t)bytes_read != sizeof(event)) {
            fprintf(stderr, "Short read from %s: %zd bytes\n", source_path, bytes_read);
            continue;
        }

        // Track the combo button states unconditionally so both the kill-app and mouse-toggle
        // combos can read them regardless of which feature is enabled.
        if (event.type == EV_KEY) {
            if (is_select_button(event.code)) {
                if (event.value == 1) select_pressed = 1;
                else if (event.value == 0) select_pressed = 0;
            } else if (is_start_button(event.code)) {
                if (event.value == 1) start_pressed = 1;
                else if (event.value == 0) start_pressed = 0;
            } else if (event.code == BTN_THUMBR) {
                if (event.value == 1) thumbr_pressed = 1;
                else if (event.value == 0) thumbr_pressed = 0;
            }

            // Arm the mouse-toggle hold when Select+R3 first go down together; disarm on release.
            if (virtual_mouse) {
                if (event.value == 1 && select_pressed && thumbr_pressed) {
                    mouse_combo_pressed_at_ms = now_ms();
                    mouse_combo_triggered = 0;
                }
                if (event.value == 0 && (!select_pressed || !thumbr_pressed)) {
                    mouse_combo_triggered = 0;
                }
            }
        }

        if (home_as_back && event.type == EV_KEY && is_home_button(event.code)) {
            // Swallow the Home button entirely (press, repeat and release) so the target controller
            // never sees a Home-down that never gets its matching up. Inject Back once, on release.
            if (event.value == 0) {
                run_detached("input keyevent 4");
            }
            continue;
        }

        if (combo_hold_kill_app && event.type == EV_KEY && (is_select_button(event.code) || is_start_button(event.code))) {
            if (event.value == 1) {
                if (select_pressed && start_pressed) {
                    combo_pressed_at_ms = now_ms();
                    combo_triggered = 0;
                }
            }

            if (
                (event.value == 2 || event.value == 0) &&
                select_pressed &&
                start_pressed &&
                !combo_triggered &&
                now_ms() - combo_pressed_at_ms >= COMBO_HOLD_KILL_APP_MS
            ) {
                force_stop_foreground_app();
                combo_triggered = 1;
            }

            if (event.value == 0 && !select_pressed && !start_pressed) {
                combo_triggered = 0;
                combo_pressed_at_ms = 0;
            }
        }

        // In mouse mode the source drives the pointer instead of the target: update stick positions
        // (motion is emitted on the frame cadence above) and map A/B to clicks.
        if (mouse_mode) {
            if (event.type == EV_ABS) {
                if (event.code == left_x.code && left_x.present) left_x.raw = event.value;
                else if (event.code == left_y.code && left_y.present) left_y.raw = event.value;
                else if (event.code == right_x.code && right_x.present) right_x.raw = event.value;
                else if (event.code == right_y.code && right_y.present) right_y.raw = event.value;
            } else if (event.type == EV_KEY && event.code == BTN_SOUTH) {
                left_click_down = event.value ? 1 : 0;
                emit_event(uinput_fd, EV_KEY, BTN_LEFT, left_click_down);
                emit_event(uinput_fd, EV_SYN, SYN_REPORT, 0);
            } else if (event.type == EV_KEY && event.code == BTN_EAST) {
                right_click_down = event.value ? 1 : 0;
                emit_event(uinput_fd, EV_KEY, BTN_RIGHT, right_click_down);
                emit_event(uinput_fd, EV_SYN, SYN_REPORT, 0);
            }
            continue;
        }

        if (swap_nintendo_layout && event.type == EV_KEY) {
            event.code = swap_nintendo_face_button(event.code);
        }

        if (write_full(target_fd, &event, sizeof(event)) != 0) {
            fprintf(stderr, "Write error to %s: %s\n", target_path, strerror(errno));
            break;
        }
    }

    if (ioctl(source_fd, EVIOCGRAB, 0) < 0) {
        fprintf(stderr, "Failed to release source %s: %s\n", source_path, strerror(errno));
    }

    if (uinput_fd >= 0) {
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
    }
    if (touch_fd >= 0) {
        ioctl(touch_fd, UI_DEV_DESTROY);
        close(touch_fd);
    }
    close(target_fd);
    close(source_fd);
    if (pid_file_path != NULL) {
        unlink(pid_file_path);
    }
    if (heartbeat_file_path != NULL) {
        unlink(heartbeat_file_path);
    }
    return EXIT_SUCCESS;
}
