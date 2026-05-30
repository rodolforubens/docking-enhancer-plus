#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
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

static void handle_signal(int signal_number) {
    (void)signal_number;
    keep_running = 0;
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
    system(
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

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "Usage: %s /dev/input/eventSOURCE /dev/input/eventTARGET [--home-as-back] [--combo-hold-kill-app] [--pid-file PATH] [--heartbeat-file PATH]\n", argv[0]);
        return EXIT_FAILURE;
    }

    const char *source_path = argv[1];
    const char *target_path = argv[2];
    const char *pid_file_path = NULL;
    const char *heartbeat_file_path = NULL;
    int home_as_back = 0;
    int combo_hold_kill_app = 0;

    for (int index = 3; index < argc; index++) {
        if (strcmp(argv[index], "--home-as-back") == 0) {
            home_as_back = 1;
            continue;
        }

        if (strcmp(argv[index], "--combo-hold-kill-app") == 0) {
            combo_hold_kill_app = 1;
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

    int source_fd = open(source_path, O_RDONLY | O_CLOEXEC);
    if (source_fd < 0) {
        fprintf(stderr, "Failed to open source %s: %s\n", source_path, strerror(errno));
        return EXIT_FAILURE;
    }

    int target_fd = open(target_path, O_WRONLY | O_CLOEXEC);
    if (target_fd < 0) {
        fprintf(stderr, "Failed to open target %s: %s\n", target_path, strerror(errno));
        close(source_fd);
        return EXIT_FAILURE;
    }

    if (ioctl(source_fd, EVIOCGRAB, 1) < 0) {
        fprintf(stderr, "Failed to grab source %s: %s\n", source_path, strerror(errno));
        close(target_fd);
        close(source_fd);
        return EXIT_FAILURE;
    }

    write_pid_file(pid_file_path);
    write_heartbeat_file(heartbeat_file_path);

    struct input_event event;
    int select_pressed = 0;
    int start_pressed = 0;

    int combo_triggered = 0;
    long long combo_pressed_at_ms = 0;
    long long last_heartbeat_ms = 0;

    while (keep_running) {
        int timeout_ms = 1000;
        if (
            combo_hold_kill_app &&
            select_pressed &&
            start_pressed &&
            !combo_triggered
        ) {
            timeout_ms = 50;
        }

        long long heartbeat_now_ms = now_ms();
        if (heartbeat_now_ms - last_heartbeat_ms >= HEARTBEAT_INTERVAL_MS) {
            write_heartbeat_file(heartbeat_file_path);
            last_heartbeat_ms = heartbeat_now_ms;
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

        if (poll_result == 0) {
            if (
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

        if (event.type == EV_KEY && is_home_button(event.code)) {
            if (home_as_back && event.value == 0) {
                system("input keyevent 4");
                continue;
            }
        }

        if (combo_hold_kill_app && event.type == EV_KEY && (is_select_button(event.code) || is_start_button(event.code))) {
            int is_select = is_select_button(event.code);

            if (event.value == 1) {
                if (is_select) {
                    select_pressed = 1;
                } else {
                    start_pressed = 1;
                }

                if (select_pressed && start_pressed) {
                    combo_pressed_at_ms = now_ms();
                    combo_triggered = 0;
                }
            }

            if (event.value == 2) {
                if (
                    select_pressed &&
                    start_pressed &&
                    !combo_triggered &&
                    now_ms() - combo_pressed_at_ms >= COMBO_HOLD_KILL_APP_MS
                ) {
                    force_stop_foreground_app();
                    combo_triggered = 1;
                }
            }

            if (event.value == 0) {
                if (
                    select_pressed &&
                    start_pressed &&
                    !combo_triggered &&
                    now_ms() - combo_pressed_at_ms >= COMBO_HOLD_KILL_APP_MS
                ) {
                    force_stop_foreground_app();
                    combo_triggered = 1;
                }

                if (is_select) {
                    select_pressed = 0;
                } else {
                    start_pressed = 0;
                }
                if (!select_pressed && !start_pressed) {
                    combo_triggered = 0;
                    combo_pressed_at_ms = 0;
                }
            }
        }

        if (write_full(target_fd, &event, sizeof(event)) != 0) {
            fprintf(stderr, "Write error to %s: %s\n", target_path, strerror(errno));
            break;
        }
    }

    if (ioctl(source_fd, EVIOCGRAB, 0) < 0) {
        fprintf(stderr, "Failed to release source %s: %s\n", source_path, strerror(errno));
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
