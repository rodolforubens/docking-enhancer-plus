/*
 * vgamepad — a scriptable synthetic gamepad for the Docking Enhancer e2e suite.
 *
 * Why this exists: every meaningful test of the mirror needs a controller, and driving one by hand
 * makes the suite unrepeatable — the physical pad sleeps mid-run, its event node renumbers, and no
 * script can press its buttons. This creates a uinput gamepad the daemon can mirror FROM (or INTO),
 * with an exact, reproducible event stream.
 *
 * It needs no root: /dev/uinput is world read/write on the Odin and `shell` is in the uhid group.
 *
 * Usage:
 *   vgamepad --path-file PATH [--name NAME] [--vendor HEX] [--product HEX] [--plain]
 *
 * Creates the device, writes its /dev/input/eventN path to --path-file (so a test can wait for the
 * file rather than guess a delay), then executes one command per line of stdin:
 *
 *   key <code> <value>     press/release a key code (e.g. `key 304 1`)
 *   abs <code> <value>     set an absolute axis
 *   syn                    emit SYN_REPORT, closing the current frame
 *   burst <n>              n frames of ABS_X wiggle — exercises multi-event batched reads
 *   sleep <ms>             pause
 *   quit                   destroy the device and exit (EOF does the same)
 *
 * The default vendor is the Odin quirk id (0x2020) because that is what the mirror's hide path
 * requires: hide_node refuses to unlink anything else. Pass --vendor to test that refusal.
 */
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/poll.h>
#include <time.h>
#include <unistd.h>

#define ODIN_VENDOR_ID 0x2020

// Mirror a real pad's capability set. The kernel drops any event a device did not declare, so a
// thin set here would silently swallow injected events and read as a forwarding bug.
static const int KEYS[] = {
    BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST,
    BTN_TL, BTN_TR, BTN_TL2, BTN_TR2,
    BTN_SELECT, BTN_START, BTN_MODE,
    BTN_THUMBL, BTN_THUMBR,
};

static const int AXES[] = { ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ };

static int emit(int fd, unsigned short type, unsigned short code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    return write(fd, &ev, sizeof(ev)) == (ssize_t)sizeof(ev) ? 0 : -1;
}

// Resolve the event node uinput just created. Asking the kernel for the sysfs name (UI_GET_SYSNAME)
// and reading the node out of it is race-free; diffing /dev/input before and after would attribute
// the wrong node whenever a real controller connects mid-test.
static int resolve_event_path(int fd, char *out, size_t out_len) {
    char sysname[64];
    memset(sysname, 0, sizeof(sysname));
    if (ioctl(fd, UI_GET_SYSNAME(sizeof(sysname)), sysname) < 0) {
        fprintf(stderr, "vgamepad: UI_GET_SYSNAME failed\n");
        return -1;
    }

    char dirpath[128];
    snprintf(dirpath, sizeof(dirpath), "/sys/class/input/%s", sysname);
    DIR *dir = opendir(dirpath);
    if (dir == NULL) {
        fprintf(stderr, "vgamepad: cannot open %s\n", dirpath);
        return -1;
    }

    int found = -1;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strncmp(entry->d_name, "event", 5) == 0) {
            snprintf(out, out_len, "/dev/input/%s", entry->d_name);
            found = 0;
            break;
        }
    }
    closedir(dir);
    if (found != 0) {
        fprintf(stderr, "vgamepad: no event node under %s\n", dirpath);
    }
    return found;
}

static int create_device(const char *name, int vendor, int product, int plain) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        perror("vgamepad: open /dev/uinput");
        return -1;
    }

    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 || ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) {
        perror("vgamepad: UI_SET_EVBIT");
        close(fd);
        return -1;
    }

    // --plain builds something the handheld's mapping service will ignore. It matters because the
    // Odin firmware takes over every controller it sees that does not already carry its own vendor
    // id: it publishes a 0x2020 twin and DELETES the original node. A synthetic pad with a foreign
    // vendor therefore never keeps a /dev entry, which makes it useless for testing that the daemon
    // refuses to hide the wrong device. A non-controller (one obscure key, no axes, no gamepad
    // buttons) keeps its node and serves that test.
    if (plain) {
        if (ioctl(fd, UI_SET_KEYBIT, KEY_F24) < 0) {
            perror("vgamepad: UI_SET_KEYBIT");
            close(fd);
            return -1;
        }
    } else {
        if (ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0) {
            perror("vgamepad: UI_SET_EVBIT(EV_ABS)");
            close(fd);
            return -1;
        }
        for (size_t i = 0; i < sizeof(KEYS) / sizeof(KEYS[0]); i++) {
            if (ioctl(fd, UI_SET_KEYBIT, KEYS[i]) < 0) {
                perror("vgamepad: UI_SET_KEYBIT");
                close(fd);
                return -1;
            }
        }
        for (size_t i = 0; i < sizeof(AXES) / sizeof(AXES[0]); i++) {
            if (ioctl(fd, UI_SET_ABSBIT, AXES[i]) < 0) {
                perror("vgamepad: UI_SET_ABSBIT");
                close(fd);
                return -1;
            }
        }

        struct uinput_abs_setup abs;
        for (size_t i = 0; i < sizeof(AXES) / sizeof(AXES[0]); i++) {
            memset(&abs, 0, sizeof(abs));
            abs.code = (unsigned short)AXES[i];
            abs.absinfo.minimum = -32768;
            abs.absinfo.maximum = 32767;
            abs.absinfo.value = 0;
            if (ioctl(fd, UI_ABS_SETUP, &abs) < 0) {
                perror("vgamepad: UI_ABS_SETUP");
                close(fd);
                return -1;
            }
        }
    }

    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_USB;
    setup.id.vendor = (unsigned short)vendor;
    setup.id.product = (unsigned short)product;
    snprintf(setup.name, sizeof(setup.name), "%s", name);
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        perror("vgamepad: UI_DEV_SETUP/CREATE");
        close(fd);
        return -1;
    }
    return fd;
}

static long long now_us(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long)t.tv_sec * 1000000LL + t.tv_nsec / 1000;
}

static int compare_ll(const void *a, const void *b) {
    long long x = *(const long long *)a;
    long long y = *(const long long *)b;
    return (x > y) - (x < y);
}

// Read and discard whatever the target already has queued, so a sample times its OWN event.
static void drain(int fd) {
    struct input_event ev;
    while (read(fd, &ev, sizeof(ev)) == (ssize_t)sizeof(ev)) {
        // keep draining
    }
}

/*
 * Measure what the mirror adds to a button press, round trip, in one process and one clock.
 *
 * Timing the source against the target from outside would mean correlating two different clock
 * domains — and the source cannot even be read while the daemon holds it grabbed. Instead this
 * writes the event itself and waits for it to come back out of the target, so t1-t0 covers the
 * whole path: uinput -> kernel -> daemon read -> daemon write -> kernel -> our read. Our own
 * wake-up is inside that window too, which makes every number here an upper bound rather than a
 * flattering one.
 */
static void run_latency(int fd, const char *target_path, int samples) {
    if (samples <= 0 || samples > 2000) {
        printf("latency: sample count must be 1..2000\n");
        return;
    }
    int target = open(target_path, O_RDONLY | O_NONBLOCK);
    if (target < 0) {
        printf("latency: cannot open %s\n", target_path);
        return;
    }

    long long *deltas = calloc((size_t)samples, sizeof(long long));
    if (deltas == NULL) {
        close(target);
        return;
    }

    int collected = 0;
    int lost = 0;
    for (int i = 0; i < samples; i++) {
        drain(target);
        int value = (i % 2 == 0) ? 1 : 0;  // alternate press/release; a repeat would be dropped

        long long t0 = now_us();
        emit(fd, EV_KEY, BTN_SOUTH, value);
        emit(fd, EV_SYN, SYN_REPORT, 0);

        long long t1 = -1;
        while (t1 < 0) {
            struct pollfd p = { .fd = target, .events = POLLIN, .revents = 0 };
            if (poll(&p, 1, 250) <= 0) {
                break;  // never arrived; count it and move on
            }
            struct input_event ev;
            while (read(target, &ev, sizeof(ev)) == (ssize_t)sizeof(ev)) {
                if (ev.type == EV_KEY && ev.code == BTN_SOUTH) {
                    t1 = now_us();
                    break;
                }
            }
        }

        if (t1 < 0) {
            lost++;
        } else {
            deltas[collected++] = t1 - t0;
        }
        usleep(4000);  // let the pipeline settle so samples stay independent
    }

    if (collected == 0) {
        printf("latency: no samples got through (is the mirror running on this pair?)\n");
        free(deltas);
        close(target);
        return;
    }

    qsort(deltas, (size_t)collected, sizeof(long long), compare_ll);
    long long sum = 0;
    for (int i = 0; i < collected; i++) {
        sum += deltas[i];
    }
    printf("latency samples=%d lost=%d (microseconds)\n", collected, lost);
    printf("  min    %lld\n", deltas[0]);
    printf("  median %lld\n", deltas[collected / 2]);
    printf("  mean   %lld\n", sum / collected);
    printf("  p95    %lld\n", deltas[(collected * 95) / 100]);
    printf("  max    %lld\n", deltas[collected - 1]);
    fflush(stdout);

    free(deltas);
    close(target);
}

// One ABS_X wiggle plus SYN per frame. Two alternating values because the input core drops an
// absolute event that repeats its current value, which would leave the batch half empty.
static void run_burst(int fd, int frames) {
    for (int i = 0; i < frames; i++) {
        emit(fd, EV_ABS, ABS_X, (i % 2 == 0) ? 12000 : -12000);
        emit(fd, EV_ABS, ABS_Y, (i % 2 == 0) ? -9000 : 9000);
        emit(fd, EV_SYN, SYN_REPORT, 0);
    }
}

int main(int argc, char **argv) {
    const char *name = "E2E Virtual Pad";
    const char *path_file = NULL;
    int vendor = ODIN_VENDOR_ID;
    int product = 0x0111;
    int plain = 0;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--plain") == 0) {
            plain = 1;
        } else if (strcmp(argv[i], "--name") == 0 && i + 1 < argc) {
            name = argv[++i];
        } else if (strcmp(argv[i], "--path-file") == 0 && i + 1 < argc) {
            path_file = argv[++i];
        } else if (strcmp(argv[i], "--vendor") == 0 && i + 1 < argc) {
            vendor = (int)strtol(argv[++i], NULL, 0);
        } else if (strcmp(argv[i], "--product") == 0 && i + 1 < argc) {
            product = (int)strtol(argv[++i], NULL, 0);
        } else {
            fprintf(stderr, "vgamepad: unknown argument %s\n", argv[i]);
            return 1;
        }
    }

    int fd = create_device(name, vendor, product, plain);
    if (fd < 0) {
        return 1;
    }

    char event_path[128];
    if (resolve_event_path(fd, event_path, sizeof(event_path)) != 0) {
        ioctl(fd, UI_DEV_DESTROY);
        close(fd);
        return 1;
    }

    printf("%s\n", event_path);
    fflush(stdout);
    if (path_file != NULL) {
        FILE *pf = fopen(path_file, "w");
        if (pf != NULL) {
            fprintf(pf, "%s\n", event_path);
            fclose(pf);
        }
    }

    char line[128];
    while (fgets(line, sizeof(line), stdin) != NULL) {
        int code = 0;
        int value = 0;
        if (strncmp(line, "quit", 4) == 0) {
            break;
        } else if (sscanf(line, "key %d %d", &code, &value) == 2) {
            emit(fd, EV_KEY, (unsigned short)code, value);
        } else if (sscanf(line, "abs %d %d", &code, &value) == 2) {
            emit(fd, EV_ABS, (unsigned short)code, value);
        } else if (strncmp(line, "syn", 3) == 0) {
            emit(fd, EV_SYN, SYN_REPORT, 0);
        } else if (sscanf(line, "burst %d", &value) == 1) {
            run_burst(fd, value);
        } else if (strncmp(line, "measure ", 8) == 0) {
            char path[128];
            int n = 0;
            if (sscanf(line, "measure %127s %d", path, &n) == 2) {
                run_latency(fd, path, n);
            }
        } else if (sscanf(line, "sleep %d", &value) == 1) {
            usleep((useconds_t)value * 1000);
        }
    }

    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    return 0;
}
