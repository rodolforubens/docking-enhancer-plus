#include "config.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

// Largest config file we will read. The document is a handful of flags written by the app; anything
// past this is not a config we wrote, so refusing it is safer than parsing a prefix of it.
#define CONFIG_MAX_BYTES 8192

// Point just past the colon following "key", skipping whitespace. NULL when the key is absent or
// isn't followed by a colon. Deliberately a scanner rather than a parser: this document has one flat
// level and we write both ends of it, so the cost of a real JSON parser buys nothing here.
static const char *json_value_of(const char *json, const char *key) {
    char needle[64];
    int length = snprintf(needle, sizeof(needle), "\"%s\"", key);
    if (length <= 0 || (size_t)length >= sizeof(needle)) {
        return NULL;
    }

    const char *at = strstr(json, needle);
    if (at == NULL) {
        return NULL;
    }
    at += length;

    while (*at == ' ' || *at == '\t' || *at == '\n' || *at == '\r') {
        at++;
    }
    if (*at != ':') {
        return NULL;
    }
    at++;
    while (*at == ' ' || *at == '\t' || *at == '\n' || *at == '\r') {
        at++;
    }
    return at;
}

// Accepts true/false and 1/0; anything else leaves the value alone, so an unknown spelling degrades
// to "keep what we had" instead of silently reading as off.
static int json_bool(const char *json, const char *key, int fallback) {
    const char *value = json_value_of(json, key);
    if (value == NULL) {
        return fallback;
    }
    if (strncmp(value, "true", 4) == 0 || *value == '1') {
        return 1;
    }
    if (strncmp(value, "false", 5) == 0 || *value == '0') {
        return 0;
    }
    return fallback;
}

static long long json_number(const char *json, const char *key, long long fallback) {
    const char *value = json_value_of(json, key);
    if (value == NULL) {
        return fallback;
    }
    char *end = NULL;
    long long parsed = strtoll(value, &end, 10);
    return end == value ? fallback : parsed;
}

// Read and parse the config, leaving `out` untouched unless the whole document is good. `generation`
// is mandatory: it is what makes a half-written or foreign file fail loudly here rather than be
// adopted as a set of defaults. Returns 0 on success.
int parse_config(const char *path, const struct config *current, struct config *out) {
    if (path == NULL) {
        return -1;
    }

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "config: cannot open %s: %s\n", path, strerror(errno));
        return -1;
    }

    char buffer[CONFIG_MAX_BYTES];
    ssize_t got = read(fd, buffer, sizeof(buffer) - 1);
    close(fd);
    if (got <= 0) {
        fprintf(stderr, "config: %s is empty or unreadable\n", path);
        return -1;
    }
    buffer[got] = '\0';

    long long generation = json_number(buffer, "generation", -1);
    if (generation < 0) {
        fprintf(stderr, "config: %s has no usable generation; ignoring it\n", path);
        return -1;
    }

    out->generation = generation;
    out->home_as_back = json_bool(buffer, "home_as_back", current->home_as_back);
    out->combo_hold_kill_app = json_bool(buffer, "combo_hold_kill_app", current->combo_hold_kill_app);
    out->virtual_mouse = json_bool(buffer, "virtual_mouse", current->virtual_mouse);
    return 0;
}

// Open the control fifo. O_RDWR matters: as the only reader we would otherwise see POLLHUP on every
// gap between the app's writes and spin the loop at full speed. Holding a writer end of our own —
// one we never write to — keeps the pipe permanently open and the poll quiet.
int open_control_fifo(const char *path) {
    if (path == NULL) {
        return -1;
    }
    int fd = open(path, O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "control: cannot open %s: %s\n", path, strerror(errno));
    }
    return fd;
}
