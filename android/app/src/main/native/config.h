/*
 * Live configuration.
 *
 * Every option here used to be fixed at launch, so changing one meant restarting the daemon — which
 * costs the exclusive grab, and with it about a second of the controller being visible to the whole
 * system. Instead the app writes this document and pokes the control fifo; the daemon re-reads it
 * between two events.
 *
 * `generation` is the contract that makes the change observable: the app picks the next number,
 * writes the file, asks for a reload, and waits to see that number come back in the heartbeat. A
 * config we fail to parse is never adopted, so the previous one keeps running and the app sees its
 * generation stall rather than a toggle that silently lied.
 */
#ifndef CONFIG_H
#define CONFIG_H

struct config {
    int home_as_back;
    int combo_hold_kill_app;
    int virtual_mouse;
    long long generation;
};

/**
 * Read and parse the config, leaving `out` untouched unless the whole document is good. Values the
 * document omits fall back to `current`, so a partial config is a change to what it names and
 * nothing else. `generation` is mandatory: it is what makes a half-written or foreign file fail
 * loudly here rather than be adopted as a set of defaults. Returns 0 on success.
 */
int parse_config(const char *path, const struct config *current, struct config *out);

/**
 * Open the control fifo. Opened O_RDWR so the daemon is its own phantom writer: as the only reader
 * it would otherwise see POLLHUP on every gap between the app's writes and spin the loop at full
 * speed. Returns the fd, or -1.
 */
int open_control_fifo(const char *path);

#endif  // CONFIG_H
