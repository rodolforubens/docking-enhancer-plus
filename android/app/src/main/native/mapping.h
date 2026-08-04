// Applying a custom controller mapping to forwarded events.
//
// The table comes from the config; this module knows nothing about where it was stored or how it was
// captured, and nothing about the mirror's state beyond both controllers' axis travel. It turns one
// incoming event into the events the target should see.
#ifndef MAPPING_H
#define MAPPING_H

#include <linux/input.h>

#include "config.h"

// One absolute axis's travel, as the kernel reports it. Both devices are described this way so a
// remapped axis can be rescaled between them without the user ever being asked for a number.
struct axis_range {
    int present;
    int minimum;
    int maximum;
    int rest;  // the value the axis sits at untouched: mid-travel on a stick, minimum on a trigger
};

// Most bindings produce one event; the ceiling exists because several bindings may name the same
// source, and a source event must never be able to overrun the caller's buffer.
#define MAX_MAPPED_EVENTS 8

/*
 * The mapping in force, plus the little state that applying it needs.
 *
 * The state is what lets analog and digital cross. A stick sends a stream of values where a button
 * target wants two transitions, and two sources may drive opposite ends of the same axis — neither is
 * decidable from the event in hand alone, so each binding remembers how hard it is currently being
 * driven.
 */
struct mapping_table {
    struct binding entries[MAX_BINDINGS];
    int count;
    float drive[MAX_BINDINGS];           // 0..1, how hard each binding is currently driven
    unsigned char pressed[MAX_BINDINGS]; // for button targets: the edge detector's last answer
};

/** Adopt a binding table, clearing the drive state so nothing carries over from the old mapping. */
void mapping_load(struct mapping_table *table, const struct binding *entries, int count);

/**
 * Resolve one event from the source controller into what the target should be told.
 *
 * Writes up to `max_out` events into `out` and returns how many. An event no binding names is copied
 * through unchanged (returns 1) — that is what makes an unmapped control keep working. A bound event
 * that changes nothing returns 0: an analog source sends a stream of values, and a button target only
 * wants the two transitions in it.
 *
 * `bound` (optional) reports whether a binding claimed this event. Callers need it because a binding
 * is the user saying exactly where a control goes: a layout correction applied afterwards would be
 * second-guessing them, and in the face-button case would invert precisely what they just set.
 */
int mapping_apply(struct mapping_table *table,
                  const struct axis_range *source,
                  const struct axis_range *target,
                  const struct input_event *in,
                  struct input_event *out,
                  int max_out,
                  int *bound);

#endif  // MAPPING_H
