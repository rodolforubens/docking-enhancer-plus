#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

static volatile sig_atomic_t keep_running = 1;

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

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "Usage: %s /dev/input/eventSOURCE /dev/input/eventTARGET [--home-as-back] [--pid-file PATH]\n", argv[0]);
        return EXIT_FAILURE;
    }

    const char *source_path = argv[1];
    const char *target_path = argv[2];
    const char *pid_file_path = NULL;
    int home_as_back = 0;

    for (int index = 3; index < argc; index++) {
        if (strcmp(argv[index], "--home-as-back") == 0) {
            home_as_back = 1;
            continue;
        }

        if (strcmp(argv[index], "--pid-file") == 0 && index + 1 < argc) {
            pid_file_path = argv[++index];
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

    struct input_event event;
    ssize_t bytes_read;

    while (keep_running && (bytes_read = read(source_fd, &event, sizeof(event))) != 0) {
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

        if (home_as_back && event.type == EV_KEY && is_home_button(event.code)) {
            if (event.value == 0) {
                system("input keyevent 4");
            }
            continue;
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
    return EXIT_SUCCESS;
}
