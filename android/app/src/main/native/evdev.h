#ifndef EVDEV_H
#define EVDEV_H

#include <linux/input.h>
#include <stddef.h>

/*
 * The handful of primitives every part of the daemon needs to put an event somewhere.
 *
 * Split out when the virtual mouse moved to its own translation unit: both it and the forwarding path
 * write events, and the alternative was either a duplicate copy of these five lines or publishing the
 * whole mirror state so one file could reach into the other. Neither is worth it for "write this many
 * bytes, and retry the interruptions".
 */

/** Milliseconds from a monotonic clock. Never wall time: this measures holds and cadences. */
long long now_ms(void);

/** write(2) that finishes the buffer, retrying only EINTR. Returns 0, or -1 with errno set. */
int write_full(int fd, const void *buffer, size_t length);

/** Build one input_event and write it. Returns 0, or -1. */
int emit_event(int fd, unsigned short type, unsigned short code, int value);

/**
 * Write one event, retrying briefly.
 *
 * For the TARGET specifically — the internal controller, which almost never truly goes away — so a
 * momentary hiccup costs a few milliseconds rather than the whole mirror. Returns 0, or -1 once the
 * retries are spent.
 */
int write_event_tolerant(int fd, const struct input_event *event);

#endif  // EVDEV_H
