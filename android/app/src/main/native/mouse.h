#ifndef MOUSE_H
#define MOUSE_H

#include <linux/input.h>

/*
 * Virtual mouse mode: the grabbed controller drives a self-created uinput pointer instead of the
 * target node.
 *
 * Everything here is the POINTER's business — the devices it needs, where the cursor is being pushed,
 * which clicks are down. What the target believes is held is deliberately not: releasing that is the
 * forwarding path's own state to unwind, and it does so before handing control over.
 */

// ~83 Hz cursor/scroll updates. Public because it also sets the mirror loop's poll timeout while the
// pointer is live — the frame has to come round even when the stick sends nothing new.
#define MOUSE_FRAME_INTERVAL_MS 12

/** One analog axis of the source controller, normalised so deflection reads as [-1, 1]. */
struct axis {
    unsigned short code;
    int present;
    int center;
    int half_range;
    int raw;  // last raw value seen
};

struct mouse_state {
    int uinput_fd;  // the pointer; exists only while [mode] is on
    int touch_fd;   // cursor-hiding touch helper, created once per run and kept
    int mode;       // the pointer is live and the source is driving it

    long long last_frame_ms;
    long long cursor_nudge_until_ms;  // keep jiggling the pointer until this instant so it shows up

    double residual_x;   // carry sub-pixel cursor motion between frames
    double residual_y;
    double wheel_accum;  // carry sub-click scroll between frames

    int left_click_down;
    int right_click_down;

    struct axis left_x, left_y, right_x, right_y;
};

/** What the caller must do about an event the pointer could not handle by itself. */
enum mouse_action {
    MOUSE_ACTION_NONE = 0,

    /**
     * R1 was pressed: toggle Android's notification shade.
     *
     * Reported rather than done here because it takes a shell command, and running one as root is
     * the daemon's business to own in one place — not something the pointer should reach for.
     */
    MOUSE_ACTION_TOGGLE_SHADE,
};

/** Zero the state and mark both devices absent. Call before anything else. */
void mouse_init(struct mouse_state *m);

/** Read the source's stick ranges. Must happen BEFORE the source is grabbed. */
void mouse_bind_source_axes(struct mouse_state *m, int source_fd);

/**
 * Create the touch helper that hides the cursor on the way out.
 *
 * Created on every mirror start regardless of the setting: its unavoidable "connected" toast doubles
 * as the mirror-activation notice, and creating it once per session keeps that toast from repeating
 * on every mouse-mode toggle. Returns 0, or -1 if it could not be created.
 */
int mouse_create_touch_device(struct mouse_state *m);

/**
 * Bring the pointer up. Returns 0, or -1 if the uinput device could not be created — in which case
 * nothing changed and the caller stays in gamepad mode.
 *
 * On success the caller must release whatever the target believes held: from here on nothing is
 * forwarded, so a key still down over there stays down for as long as the pointer is up.
 */
int mouse_enter(struct mouse_state *m);

/** Release held clicks, destroy the pointer, and flush the touch helper so the cursor hides at once. */
void mouse_leave(struct mouse_state *m);

/** Emit accumulated cursor/scroll motion, at most once per frame interval. */
void mouse_emit_frame(struct mouse_state *m);

/** Fold one source event into the pointer. */
enum mouse_action mouse_handle_event(struct mouse_state *m, const struct input_event *ev);

/** Tear down both devices at shutdown. Safe whether or not the pointer is up. */
void mouse_destroy(struct mouse_state *m);

#endif  // MOUSE_H
