// Recording which control the user pressed, for the mapping wizard.
//
// The app cannot do this itself: the mirror holds an exclusive grab on the controller and has
// unlinked its /dev/input node, so not one of its events reaches the framework. While a capture step
// is open the daemon stops forwarding and writes what it sees to a log the app reads.
#ifndef CAPTURE_H
#define CAPTURE_H

#include <linux/input.h>

#include "mapping.h"

// What the wizard is asking for. The same physical event means different things depending on this:
// an axis is a button when a button was asked for, and half of a stick when a stick was.
enum capture_want {
    CAPTURE_OFF = 0,
    CAPTURE_BUTTON,
    CAPTURE_STICK,
};

struct capture {
    enum capture_want want;
    int latched;           // this step already produced a result; ignore input until the next one
    int first_axis;        // stick capture: the axis seen first, waiting for a different second
    int first_axis_sign;
    long long last_command_ms;
    const char *log_path;
};

/** Open a capture step, clearing the latch and any half-captured stick. */
void capture_begin(struct capture *c, enum capture_want want, long long now_ms);

/** Close capture and resume normal forwarding. */
void capture_end(struct capture *c);

/**
 * Feed one event from the grabbed controller.
 *
 * Returns 1 when the event was consumed and must NOT be forwarded — which is every event while a
 * step is open, so mapping the A button does not also press A in whatever is on screen.
 *
 * `neutral` is each axis's resting value, sampled before the controller was grabbed. Deflection is
 * measured from there rather than from the middle of the range, which is what lets one rule cover a
 * stick that rests centred and a trigger that rests at its minimum.
 */
int capture_feed(struct capture *c,
                 const struct input_event *event,
                 const struct axis_range *axes,
                 const int *neutral,
                 long long now_ms);

/**
 * True once capture has been idle long enough that its owner is presumably gone. A daemon left
 * capturing forwards nothing, so the controller would go dead with no way to revive it from the
 * controller itself.
 */
int capture_expired(const struct capture *c, long long now_ms);

#endif  // CAPTURE_H
