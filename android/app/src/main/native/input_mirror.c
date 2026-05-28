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

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "Usage: %s /dev/input/eventSOURCE /dev/input/eventTARGET\n", argv[0]);
        return EXIT_FAILURE;
    }

    const char *source_path = argv[1];
    const char *target_path = argv[2];

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
    return EXIT_SUCCESS;
}
