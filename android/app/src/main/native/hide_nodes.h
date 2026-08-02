// Hiding a controller's /dev/input node from the Android framework, and giving it back.
//
// Two platform details make this work: an open file descriptor outlives unlink(), so deleting the
// node cuts the framework off without costing the daemon its input stream; and Android's EventHub
// watches /dev/input with inotify, so it drops a device the moment its entry vanishes.
#ifndef HIDE_NODES_H
#define HIDE_NODES_H

#include <sys/types.h>

// Nodes hidden from the Android framework for the lifetime of one mirror (the external controller's
// firmware quirk twin). See hide_node / restore_hidden_nodes.
#define MAX_HIDE_NODES 8
struct hidden_node {
    char path[256];
    dev_t rdev;  // device number captured before unlink, used to recreate the node on exit
    int active;  // 1 once we successfully unlinked it (so restore should recreate it)
};

// How many nodes are still hidden, i.e. still owed a restore. Drives whether the hidden-state file
// is kept for a later --heal or dropped as fully settled.
int count_active_hidden(const struct hidden_node *nodes, int count);

// Unlink a /dev/input node so the framework drops it, WITHOUT disturbing any process holding an open
// fd on it. Refuses to unlink anything whose EVIOCGID vendor is not `expected_vendor`: event numbers
// are recycled across reconnects, so a path resolved a moment ago may point at a different device by
// the time we act on it. The caller owns that policy — this module does not know one vendor from
// another.
void hide_node(const char *path, struct hidden_node *slot, unsigned short expected_vendor);

// Recreate the nodes hidden by hide_node so the framework re-enumerates them when the mirror stops.
void restore_hidden_nodes(struct hidden_node *nodes, int count);

// Persist which nodes are hidden (path + device number) so a crashed session can be healed later.
void write_hidden_state(const char *path, const struct hidden_node *nodes, int count);

// Restore nodes recorded by a previous session that never got to clean up, then drop the record.
void restore_from_state_file(const char *path);

#endif  // HIDE_NODES_H
