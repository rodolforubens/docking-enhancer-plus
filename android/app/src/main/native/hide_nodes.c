#include "hide_nodes.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/xattr.h>
#include <unistd.h>

// How many nodes are still hidden, i.e. still owed a restore. Drives whether the hidden-state file
// is kept for a later --heal or dropped as fully settled.
int count_active_hidden(const struct hidden_node *nodes, int count) {
    int active = 0;
    for (int i = 0; i < count; i++) {
        if (nodes[i].active) {
            active++;
        }
    }
    return active;
}

// Unlink a /dev/input node so Android's EventHub drops it from the device list (it watches
// /dev/input for IN_DELETE) — WITHOUT disturbing any process that already holds an open fd on it
// (POSIX: unlink removes the name, not the open description). The mirror never reads these nodes;
// they are duplicate representations of the source we simply want gone while mirroring. Records the
// device number so restore_hidden_nodes can recreate the node on exit.
void hide_node(const char *path, struct hidden_node *slot, unsigned short expected_vendor) {
    memset(slot, 0, sizeof(*slot));
    snprintf(slot->path, sizeof(slot->path), "%s", path);

    // Verify identity before unlinking. Event numbers renumber across reconnects, so between the app
    // resolving this path and us acting on it the node could point to a DIFFERENT device. Open it,
    // confirm it is the vendor the caller expects, and capture the device number from the SAME fd —
    // refuse to unlink anything else.
    int probe = open(path, O_RDONLY | O_CLOEXEC);
    if (probe < 0) {
        fprintf(stderr, "hide: cannot open %s to verify: %s\n", path, strerror(errno));
        return;
    }
    struct stat st;
    struct input_id id;
    if (fstat(probe, &st) != 0 || !S_ISCHR(st.st_mode) ||
        ioctl(probe, EVIOCGID, &id) != 0 || id.vendor != expected_vendor) {
        fprintf(stderr, "hide: %s failed identity check (vendor is not %04x), refusing\n",
                path, expected_vendor);
        close(probe);
        return;
    }
    close(probe);

    slot->rdev = st.st_rdev;
    if (unlink(path) == 0) {
        slot->active = 1;
    } else {
        fprintf(stderr, "hide: unlink %s failed: %s\n", path, strerror(errno));
    }
}

// Recreate the nodes hidden by hide_node so the framework re-enumerates them when the mirror stops.
// Only recreate when the underlying device still exists (/sys/dev/char/<maj>:<min>) — if the
// controller disconnected, its input_dev is gone and the framework already dropped it; a mknod then
// would leave a dead, unopenable node. The node is created OUTSIDE the watched dir with the correct
// SELinux label and hardlinked in: EventHub opens it on the resulting IN_CREATE, and a label set
// only after the node appears would lose that race and the device would never be listed.
void restore_hidden_nodes(struct hidden_node *nodes, int count) {
    const char *tmp = "/dev/.input_mirror_restore";
    const char *ctx = "u:object_r:input_device:s0";
    for (int i = 0; i < count; i++) {
        struct hidden_node *n = &nodes[i];
        if (!n->active) {
            continue;
        }
        char syschar[64];
        snprintf(syschar, sizeof(syschar), "/sys/dev/char/%u:%u",
                 major(n->rdev), minor(n->rdev));
        if (access(syschar, F_OK) != 0) {
            n->active = 0;  // device gone; nothing to restore
            continue;
        }
        unlink(tmp);
        if (mknod(tmp, S_IFCHR | 0666, n->rdev) != 0) {
            fprintf(stderr, "restore: mknod for %s failed: %s\n", n->path, strerror(errno));
            continue;
        }
        if (chown(tmp, 0, 1004) != 0) {  // root:input
            // Non-fatal: EventHub can still open a root-owned node; log and continue.
            fprintf(stderr, "restore: chown %s: %s\n", n->path, strerror(errno));
        }
        chmod(tmp, 0666);
        if (setxattr(tmp, "security.selinux", ctx, strlen(ctx) + 1, 0) != 0) {
            fprintf(stderr, "restore: setxattr %s: %s\n", n->path, strerror(errno));
        }
        if (link(tmp, n->path) != 0) {
            fprintf(stderr, "restore: link %s failed: %s\n", n->path, strerror(errno));
        }
        unlink(tmp);
        // Settle this node only once the name is really back — the filesystem, not the return code,
        // is the authority. A link() that failed with EEXIST means it returned on its own (fine);
        // any other failure means it is STILL hidden, and clearing the flag there would drop it from
        // the state file and strand the user's controller with nothing left to heal it.
        if (access(n->path, F_OK) == 0) {
            n->active = 0;
        }
    }
}

// Persist which nodes we have hidden (path + device number, one per line) so a crashed or KILL'd
// session — which never runs restore_hidden_nodes — can be healed later. Rewritten each time the
// hidden set changes; cleared on clean exit.
void write_hidden_state(const char *path, const struct hidden_node *nodes, int count) {
    if (path == NULL) {
        return;
    }
    FILE *f = fopen(path, "w");
    if (f == NULL) {
        return;
    }
    for (int i = 0; i < count; i++) {
        if (nodes[i].active) {
            fprintf(f, "%s %u %u\n", nodes[i].path, major(nodes[i].rdev), minor(nodes[i].rdev));
        }
    }
    fclose(f);
    chmod(path, 0644);
}

// Restore nodes recorded in a hidden-state file left by a previous session (orphaned by a crash),
// then remove the file. restore_hidden_nodes skips any whose device is gone, so a controller that
// disconnected meanwhile leaves no dead node behind.
void restore_from_state_file(const char *path) {
    if (path == NULL) {
        return;
    }
    FILE *f = fopen(path, "r");
    if (f == NULL) {
        return;
    }
    struct hidden_node nodes[MAX_HIDE_NODES];
    memset(nodes, 0, sizeof(nodes));
    int count = 0;
    char line[320];
    while (count < MAX_HIDE_NODES && fgets(line, sizeof(line), f) != NULL) {
        char parsed[256];
        unsigned int maj = 0;
        unsigned int min = 0;
        if (sscanf(line, "%255s %u %u", parsed, &maj, &min) == 3) {
            snprintf(nodes[count].path, sizeof(nodes[count].path), "%s", parsed);
            nodes[count].rdev = makedev(maj, min);
            nodes[count].active = 1;
            count++;
        }
    }
    fclose(f);
    restore_hidden_nodes(nodes, count);
    // Keep whatever refused to come back so the next heal tries again; a heal that restored
    // everything (or found the devices gone) has nothing left to record.
    if (count_active_hidden(nodes, count) > 0) {
        write_hidden_state(path, nodes, count);
    } else {
        unlink(path);
    }
}
