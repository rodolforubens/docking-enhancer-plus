#include "evdev.h"

#include <errno.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

// Transient write errors to the target (the internal controller, which almost never truly
// disappears) are retried this many times before giving up — so a momentary hiccup doesn't tear
// down the whole mirror.
static const int TARGET_WRITE_RETRIES = 3;

long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

int write_full(int fd, const void *buffer, size_t length) {
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
int emit_event(int fd, unsigned short type, unsigned short code, int value) {
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
int write_event_tolerant(int fd, const struct input_event *event) {
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
