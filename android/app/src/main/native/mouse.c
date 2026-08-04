#include "mouse.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/uinput.h>
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include "evdev.h"

#ifndef BTN_SOUTH
#define BTN_SOUTH 0x130
#endif
#ifndef BTN_EAST
#define BTN_EAST 0x131
#endif
#ifndef BTN_TR
#define BTN_TR 0x137
#endif

static const double MOUSE_SPEED = 18.0;           // max cursor pixels per frame at full deflection
static const double MOUSE_DEADZONE = 0.18;        // fraction of stick travel ignored around center
static const double WHEEL_STEP_PER_FRAME = 0.30;  // scroll clicks per frame at full deflection
// Android only paints the pointer once it receives real motion, so entering mouse mode used to leave
// the cursor invisible until the stick was moved. We can't just emit one nudge on entry either: the
// uinput device was created microseconds ago and the framework still has to notice it via inotify
// and open it, so anything written before that is dropped. Instead nudge on every idle frame for
// this long, which covers the open latency and stops as soon as the window closes.
static const long long CURSOR_SHOW_NUDGE_MS = 300;

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
 * switches to touch mode). The device is created at mirror start so Android has fully enumerated and
 * opened it by the time we need it (a device created and used within the same instant is never read —
 * enumeration takes ~100ms+). On leaving mouse mode we push one touch-DOWN through it and cancel the
 * gesture, so Android switches to touch mode (cursor hidden immediately) with nothing dispatched.
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

void mouse_init(struct mouse_state *m) {
    memset(m, 0, sizeof(*m));
    m->uinput_fd = -1;
    m->touch_fd = -1;
    m->last_frame_ms = now_ms();
}

void mouse_bind_source_axes(struct mouse_state *m, int source_fd) {
    // Left stick (ABS_X/ABS_Y) is universal; the right stick used for scroll varies, so probe
    // ABS_RX/RY and fall back to ABS_Z/RZ (the 8BitDo pads expose the right stick there).
    query_axis(source_fd, &m->left_x, ABS_X);
    query_axis(source_fd, &m->left_y, ABS_Y);
    query_axis(source_fd, &m->right_x, ABS_RX);
    query_axis(source_fd, &m->right_y, ABS_RY);
    if (!m->right_x.present || !m->right_y.present) {
        query_axis(source_fd, &m->right_x, ABS_Z);
        query_axis(source_fd, &m->right_y, ABS_RZ);
    }
}

int mouse_create_touch_device(struct mouse_state *m) {
    m->touch_fd = create_touch_device();
    return m->touch_fd >= 0 ? 0 : -1;
}

int mouse_enter(struct mouse_state *m) {
    m->uinput_fd = create_uinput_mouse();
    if (m->uinput_fd < 0) {
        fprintf(stderr, "Virtual mouse: could not create uinput device; staying in gamepad mode\n");
        return -1;
    }
    m->mode = 1;
    m->residual_x = m->residual_y = m->wheel_accum = 0.0;
    m->left_click_down = m->right_click_down = 0;
    m->last_frame_ms = now_ms();
    m->cursor_nudge_until_ms = m->last_frame_ms + CURSOR_SHOW_NUDGE_MS;
    return 0;
}

void mouse_leave(struct mouse_state *m) {
    m->mode = 0;
    if (m->uinput_fd >= 0) {
        if (m->left_click_down) emit_event(m->uinput_fd, EV_KEY, BTN_LEFT, 0);
        if (m->right_click_down) emit_event(m->uinput_fd, EV_KEY, BTN_RIGHT, 0);
        emit_event(m->uinput_fd, EV_SYN, SYN_REPORT, 0);
        ioctl(m->uinput_fd, UI_DEV_DESTROY);
        close(m->uinput_fd);
        m->uinput_fd = -1;
    }
    m->left_click_down = m->right_click_down = 0;
    flush_touch_cancel(m->touch_fd);
}

// Emit accumulated cursor/scroll motion on the frame cadence. A moving stick streams ABS events so
// poll rarely times out; the time gate throttles the actual emit rate to ~83 Hz either way.
void mouse_emit_frame(struct mouse_state *m) {
    long long tick = now_ms();
    if (tick - m->last_frame_ms < MOUSE_FRAME_INTERVAL_MS) {
        return;
    }
    m->last_frame_ms = tick;
    m->residual_x += axis_normalised(&m->left_x) * MOUSE_SPEED;
    m->residual_y += axis_normalised(&m->left_y) * MOUSE_SPEED;
    int dx = (int)m->residual_x;
    int dy = (int)m->residual_y;
    m->residual_x -= dx;
    m->residual_y -= dy;

    // Scroll: right stick up scrolls up (positive wheel). Stick up reads negative, so invert Y.
    m->wheel_accum += (-axis_normalised(&m->right_y)) * WHEEL_STEP_PER_FRAME;
    int wheel = (int)m->wheel_accum;
    m->wheel_accum -= wheel;

    if (dx != 0 || dy != 0 || wheel != 0) {
        if (dx != 0) emit_event(m->uinput_fd, EV_REL, REL_X, dx);
        if (dy != 0) emit_event(m->uinput_fd, EV_REL, REL_Y, dy);
        if (wheel != 0) emit_event(m->uinput_fd, EV_REL, REL_WHEEL, wheel);
        emit_event(m->uinput_fd, EV_SYN, SYN_REPORT, 0);
    } else if (tick < m->cursor_nudge_until_ms) {
        // Idle inside the reveal window: step one pixel out and straight back, as two separate
        // reports. Each is real motion so the framework paints (and keeps) the pointer, while the
        // pair nets to zero, leaving the cursor exactly where the user last had it.
        emit_event(m->uinput_fd, EV_REL, REL_X, 1);
        emit_event(m->uinput_fd, EV_SYN, SYN_REPORT, 0);
        emit_event(m->uinput_fd, EV_REL, REL_X, -1);
        emit_event(m->uinput_fd, EV_SYN, SYN_REPORT, 0);
    }
}

// In mouse mode the source drives the pointer instead of the target: track stick positions (motion
// is emitted on the frame cadence), map A/B to clicks, and R1 to the notification shade.
enum mouse_action mouse_handle_event(struct mouse_state *m, const struct input_event *ev) {
    if (ev->type == EV_ABS) {
        if (ev->code == m->left_x.code && m->left_x.present) m->left_x.raw = ev->value;
        else if (ev->code == m->left_y.code && m->left_y.present) m->left_y.raw = ev->value;
        else if (ev->code == m->right_x.code && m->right_x.present) m->right_x.raw = ev->value;
        else if (ev->code == m->right_y.code && m->right_y.present) m->right_y.raw = ev->value;
    } else if (ev->type == EV_KEY && ev->code == BTN_SOUTH) {
        m->left_click_down = ev->value ? 1 : 0;
        emit_event(m->uinput_fd, EV_KEY, BTN_LEFT, m->left_click_down);
        emit_event(m->uinput_fd, EV_SYN, SYN_REPORT, 0);
    } else if (ev->type == EV_KEY && ev->code == BTN_EAST) {
        m->right_click_down = ev->value ? 1 : 0;
        emit_event(m->uinput_fd, EV_KEY, BTN_RIGHT, m->right_click_down);
        emit_event(m->uinput_fd, EV_SYN, SYN_REPORT, 0);
    } else if (ev->type == EV_KEY && ev->code == BTN_TR && ev->value == 1) {
        // A mouse cannot drag the shade down, so R1 asks for it. The command itself is the caller's
        // to run.
        return MOUSE_ACTION_TOGGLE_SHADE;
    }
    return MOUSE_ACTION_NONE;
}

void mouse_destroy(struct mouse_state *m) {
    if (m->uinput_fd >= 0) {
        ioctl(m->uinput_fd, UI_DEV_DESTROY);
        close(m->uinput_fd);
        m->uinput_fd = -1;
    }
    if (m->touch_fd >= 0) {
        ioctl(m->touch_fd, UI_DEV_DESTROY);
        close(m->touch_fd);
        m->touch_fd = -1;
    }
}
