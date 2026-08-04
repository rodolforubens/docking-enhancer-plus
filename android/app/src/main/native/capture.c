#include "capture.h"

#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

// Capture expires this long after the last command. Long enough that a user reading the prompt is
// never cut off, short enough that a crashed app does not leave the pad mute for a whole session.
static const long long CAPTURE_IDLE_TIMEOUT_MS = 30000;

// How far an axis must move from rest to count as pressed, as a fraction of its FULL travel. A stick
// pushed to its limit moves half of it; a trigger pressed fully moves all of it. Anything below this
// is drift or a neighbouring axis twitching, which would otherwise capture the wrong control.
static const double CAPTURE_DEFLECTION = 0.35;

// Append one result. Opened per line because capture is not a hot path — nothing is being forwarded
// while it is open — and appending is what makes a fast double press impossible to lose.
static void capture_emit(const struct capture *c, const char *line) {
    if (c->log_path == NULL) {
        return;
    }
    int fd = open(c->log_path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    if (fd < 0) {
        return;
    }
    (void)!write(fd, line, strlen(line));
    close(fd);
    chmod(c->log_path, 0644);
}

void capture_begin(struct capture *c, enum capture_want want, long long now_ms) {
    c->want = want;
    c->latched = 0;
    c->first_axis = -1;
    c->first_axis_sign = 0;
    c->last_command_ms = now_ms;
}

void capture_end(struct capture *c) {
    c->want = CAPTURE_OFF;
    c->latched = 0;
    c->first_axis = -1;
    c->first_axis_sign = 0;
}

int capture_expired(const struct capture *c, long long now_ms) {
    return c->want != CAPTURE_OFF && now_ms - c->last_command_ms >= CAPTURE_IDLE_TIMEOUT_MS;
}

int capture_feed(struct capture *c,
                 const struct input_event *event,
                 const struct axis_range *axes,
                 const int *neutral,
                 long long now_ms) {
    (void)now_ms;
    if (c->want == CAPTURE_OFF) {
        return 0;
    }
    // Everything is swallowed while a step is open, including the events we ignore below: the point
    // is that the controller drives the wizard and nothing else.
    if (c->latched) {
        return 1;
    }

    if (event->type == EV_KEY) {
        // Only the press. A release would immediately satisfy the following step too.
        if (event->value == 1) {
            char line[64];
            snprintf(line, sizeof(line), "button %u\n", (unsigned)event->code);
            capture_emit(c, line);
            c->latched = 1;
        }
        return 1;
    }

    if (event->type != EV_ABS || event->code >= ABS_CNT) {
        return 1;
    }

    const struct axis_range *range = &axes[event->code];
    if (!range->present) {
        return 1;
    }
    long span = (long)range->maximum - (long)range->minimum;
    if (span <= 0) {
        return 1;
    }
    long moved = (long)event->value - (long)neutral[event->code];
    long distance = moved < 0 ? -moved : moved;
    if ((double)distance < (double)span * CAPTURE_DEFLECTION) {
        return 1;
    }
    int sign = moved < 0 ? -1 : 1;

    if (c->want == CAPTURE_BUTTON) {
        // A trigger the other controller reports as a button still fills a button slot; the sign
        // says which way it had to travel to count as pressed.
        char line[64];
        snprintf(line, sizeof(line), "axis_button %u %d\n", (unsigned)event->code, sign);
        capture_emit(c, line);
        c->latched = 1;
        return 1;
    }

    // Stick: the first axis is remembered in silence and the second, different one completes the
    // pair. That is what lets the prompt be "roll the stick around" instead of asking for one
    // direction at a time.
    if (c->first_axis < 0) {
        c->first_axis = (int)event->code;
        c->first_axis_sign = sign;
        return 1;
    }
    if (c->first_axis == (int)event->code) {
        return 1;
    }

    char line[96];
    snprintf(line, sizeof(line), "stick %d %u %d %d\n",
             c->first_axis, (unsigned)event->code, c->first_axis_sign, sign);
    capture_emit(c, line);
    c->latched = 1;
    return 1;
}
