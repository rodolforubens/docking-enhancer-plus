#include "config.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

// Largest config file we will read. The document is a handful of flags written by the app; anything
// past this is not a config we wrote, so refusing it is safer than parsing a prefix of it.
#define CONFIG_MAX_BYTES 8192

// Widest tuple the scanner below will read. Six is what a binding row needs: kind, code and
// direction on each end.
#define JSON_MAX_TUPLE_WIDTH 6

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

/*
 * Read a "key": [[a, b], [c, d], ...] list of fixed-width integer tuples into `out`.
 *
 * Returns how many tuples were read. A list that goes bad partway yields the entries before the
 * damage rather than failing the document: the option flags alongside it are still perfectly good,
 * and half a mapping beats dropping every setting on the floor.
 */
static int json_tuples(const char *json, const char *key, int width, int *out, int max_tuples) {
    if (width < 1 || width > JSON_MAX_TUPLE_WIDTH) {
        return 0;
    }
    const char *at = json_value_of(json, key);
    if (at == NULL || *at != '[') {
        return 0;
    }
    at++;

    int count = 0;
    while (count < max_tuples) {
        while (*at == ' ' || *at == '\t' || *at == '\n' || *at == '\r' || *at == ',') {
            at++;
        }
        if (*at != '[') {
            break;
        }
        at++;

        int values[JSON_MAX_TUPLE_WIDTH];
        int read = 0;
        while (read < width) {
            char *end = NULL;
            long parsed = strtol(at, &end, 10);
            if (end == at) {
                break;
            }
            values[read++] = (int)parsed;
            at = end;
            while (*at == ' ' || *at == '\t' || *at == '\n' || *at == '\r' || *at == ',') {
                at++;
            }
        }
        if (read != width || *at != ']') {
            break;
        }
        at++;

        for (int i = 0; i < width; i++) {
            out[count * width + i] = values[i];
        }
        count++;
    }
    return count;
}

// A code is only meaningful within its own kind's namespace: BTN_SOUTH and ABS_HAT0X are both small
// numbers, and reading one as the other would drive a completely unrelated control.
static int kind_holds_code(int kind, int code) {
    if (code < 0) {
        return 0;
    }
    if (kind == BIND_KEY) {
        return code < KEY_CNT;
    }
    if (kind == BIND_AXIS || kind == BIND_HALF) {
        return code < ABS_CNT;
    }
    return 0;
}

/*
 * Read the binding table. Absent is the normal case (no custom mapping), not an error.
 *
 * A row naming an unknown kind or a code outside its range is dropped on its own — one bad row must
 * not cost the user the rows around it, and a row we cannot honour is better dropped than applied to
 * whatever control happens to share that number.
 */
static void parse_mapping(const char *json, struct config *out) {
    int raw[MAX_BINDINGS * 6];
    int tuples = json_tuples(json, "bindings", 6, raw, MAX_BINDINGS);
    out->binding_count = 0;

    for (int i = 0; i < tuples; i++) {
        const int *row = &raw[i * 6];
        int source_kind = row[0];
        int source_code = row[1];
        int target_kind = row[3];
        int target_code = row[4];
        if (!kind_holds_code(source_kind, source_code) ||
            !kind_holds_code(target_kind, target_code)) {
            continue;
        }

        struct binding *b = &out->bindings[out->binding_count++];
        b->source_kind = (unsigned char)source_kind;
        b->source_code = (unsigned short)source_code;
        // A direction only means something on an axis, and only ever points one of two ways; folding
        // it to 0 for buttons keeps the resolver from having to ask twice.
        b->source_dir = source_kind == BIND_KEY ? 0 : (row[2] < 0 ? -1 : 1);
        b->target_kind = (unsigned char)target_kind;
        b->target_code = (unsigned short)target_code;
        b->target_dir = target_kind == BIND_KEY ? 0 : (row[5] < 0 ? -1 : 1);
    }
}

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
    parse_mapping(buffer, out);
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
        return -1;
    }

    // It has to actually BE a fifo. A regular file at this path reports POLLIN forever and never
    // yields anything to read, which would spin this loop at full speed — on a niced root process
    // holding an exclusive grab on the user's controller. The app creates the fifo and replaces
    // anything that isn't one, so this is the daemon refusing to depend on that being true.
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISFIFO(st.st_mode)) {
        fprintf(stderr, "control: %s is not a fifo; running without a control channel\n", path);
        close(fd);
        return -1;
    }
    return fd;
}
