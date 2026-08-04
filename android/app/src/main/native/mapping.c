#include "mapping.h"

#include <string.h>

// How far an analog source must travel before a button target counts as pressed, and how far it must
// fall back before it counts as released, as a fraction of the travel available in that direction.
// The gap between them is deliberate: a trigger resting exactly on a single threshold would chatter
// the button open and shut on sensor noise alone.
#define PRESS_ENTER 0.55f
#define PRESS_LEAVE 0.45f

void mapping_load(struct mapping_table *table, const struct binding *entries, int count) {
    if (count < 0) {
        count = 0;
    }
    if (count > MAX_BINDINGS) {
        count = MAX_BINDINGS;
    }
    memcpy(table->entries, entries, (size_t)count * sizeof(struct binding));
    table->count = count;
    // Deliberately cleared rather than carried over: the new table's row 3 has nothing to do with the
    // old one's, so keeping the drives would leave a target held by a binding that no longer exists.
    memset(table->drive, 0, sizeof(table->drive));
    memset(table->pressed, 0, sizeof(table->pressed));
}

static int binding_matches(const struct binding *b, const struct input_event *in) {
    if (b->source_kind == BIND_KEY) {
        return in->type == EV_KEY && b->source_code == in->code;
    }
    return in->type == EV_ABS && b->source_code == in->code;
}

/*
 * How hard this binding is being driven, 0..1.
 *
 * Measured from the axis's resting value toward the end the binding names, so one rule covers a stick
 * that rests centred and a trigger that rests at its minimum: both read 0 untouched and 1 at the
 * limit. Movement the other way is not this binding's business and reads 0.
 */
static float source_drive(const struct binding *b,
                          const struct axis_range *source,
                          const struct input_event *in) {
    if (b->source_kind == BIND_KEY) {
        return in->value != 0 ? 1.0f : 0.0f;
    }

    const struct axis_range *r = &source[b->source_code];
    if (!r->present) {
        return 0.0f;
    }
    int dir = b->source_dir < 0 ? -1 : 1;
    long available = dir > 0 ? (long)r->maximum - (long)r->rest : (long)r->rest - (long)r->minimum;
    if (available <= 0) {
        return 0.0f;
    }
    long travelled = ((long)in->value - (long)r->rest) * dir;
    if (travelled <= 0) {
        return 0.0f;
    }
    if (travelled >= available) {
        return 1.0f;
    }
    return (float)((double)travelled / (double)available);
}

/*
 * What an analog target should now read, given every binding pointing at it.
 *
 * Combining rather than overwriting is what makes opposite halves usable: bind L2 to "left stick
 * left" and R2 to "left stick right" and the two have to meet in the middle, which they cannot do if
 * whichever moved last simply wins. Whole-axis bindings are excluded because they carry a position
 * rather than a one-directional push, and are written straight through by the caller.
 */
static int combined_axis_value(const struct mapping_table *table,
                               const struct axis_range *target,
                               unsigned short code) {
    const struct axis_range *r = &target[code];
    float positive = 0.0f;
    float negative = 0.0f;

    for (int i = 0; i < table->count; i++) {
        const struct binding *b = &table->entries[i];
        if (b->target_kind == BIND_KEY || b->target_code != code) {
            continue;
        }
        if (b->source_kind == BIND_AXIS && b->target_kind == BIND_AXIS) {
            continue;
        }
        if (b->target_dir < 0) {
            if (table->drive[i] > negative) {
                negative = table->drive[i];
            }
        } else if (table->drive[i] > positive) {
            positive = table->drive[i];
        }
    }

    float net = positive - negative;
    if (net >= 0.0f) {
        return r->rest + (int)(net * (float)(r->maximum - r->rest) + 0.5f);
    }
    return r->rest - (int)(-net * (float)(r->rest - r->minimum) + 0.5f);
}

// Whole axis onto whole axis: rescale between two controllers that disagree about their ranges (a
// trigger reporting 0..255 into one expecting -32767..32767, say). Worked in "distance from the
// source's minimum" so the arithmetic holds for a trigger resting at its minimum as well as a stick
// resting in the middle; flipping is then simply measuring that distance from the other end.
static int rescale_axis(const struct binding *b,
                        const struct axis_range *source,
                        const struct axis_range *target,
                        int value) {
    const struct axis_range *from = &source[b->source_code];
    const struct axis_range *to = &target[b->target_code];

    // Without a usable range on both sides there is nothing to scale against. Forwarding the raw
    // value under the new code is the honest fallback: the control reaches the right place, just
    // without the correction we could not compute.
    if (!from->present || !to->present) {
        return value;
    }
    long source_span = (long)from->maximum - (long)from->minimum;
    long target_span = (long)to->maximum - (long)to->minimum;
    if (source_span <= 0 || target_span <= 0) {
        return value;
    }

    long travelled = (long)value - (long)from->minimum;
    if (travelled < 0) {
        travelled = 0;
    }
    if (travelled > source_span) {
        travelled = source_span;
    }
    if (b->source_dir * b->target_dir < 0) {
        travelled = source_span - travelled;
    }
    return (int)((long)to->minimum + (travelled * target_span) / source_span);
}

// Resolve one binding. Returns how many events it produced (0 or 1). `out` has room for at least one.
static int apply_binding(struct mapping_table *table,
                         int index,
                         const struct axis_range *source,
                         const struct axis_range *target,
                         const struct input_event *in,
                         struct input_event *out) {
    const struct binding *b = &table->entries[index];

    // Button straight onto button: the value passes through untouched, which matters because the
    // kernel's autorepeat (value 2) is neither a press nor a release and thresholding would lose it.
    if (b->source_kind == BIND_KEY && b->target_kind == BIND_KEY) {
        *out = *in;
        out->code = b->target_code;
        return 1;
    }

    if (b->source_kind == BIND_AXIS && b->target_kind == BIND_AXIS) {
        *out = *in;
        out->code = b->target_code;
        out->value = rescale_axis(b, source, target, in->value);
        return 1;
    }

    float drive = source_drive(b, source, in);

    if (b->target_kind == BIND_KEY) {
        int held = table->pressed[index]
                       ? (drive > PRESS_LEAVE)
                       : (drive >= PRESS_ENTER);
        if (held == table->pressed[index]) {
            return 0;
        }
        table->pressed[index] = (unsigned char)held;
        *out = *in;
        out->type = EV_KEY;
        out->code = b->target_code;
        out->value = held;
        return 1;
    }

    // Analog target driven by something that only pushes one way — a button, a trigger, or one half
    // of a stick. A digital source gives full deflection; an analog one stays proportional.
    if (!target[b->target_code].present) {
        return 0;
    }
    table->drive[index] = drive;
    int value = combined_axis_value(table, target, b->target_code);
    *out = *in;
    out->type = EV_ABS;
    out->code = b->target_code;
    out->value = value;
    return 1;
}

int mapping_apply(struct mapping_table *table,
                  const struct axis_range *source,
                  const struct axis_range *target,
                  const struct input_event *in,
                  struct input_event *out,
                  int max_out,
                  int *bound) {
    if (bound != NULL) {
        *bound = 0;
    }
    if (max_out < 1) {
        return 0;
    }
    if (table->count == 0 || (in->type != EV_KEY && in->type != EV_ABS)) {
        out[0] = *in;
        return 1;
    }

    int matched = 0;
    int written = 0;
    for (int i = 0; i < table->count && written < max_out; i++) {
        if (!binding_matches(&table->entries[i], in)) {
            continue;
        }
        matched = 1;
        written += apply_binding(table, i, source, target, in, &out[written]);
    }

    // Untouched is the default on purpose: it is what makes a control the user skipped in the wizard
    // go on working exactly as it did, instead of going dead because nobody named it.
    if (!matched) {
        out[0] = *in;
        return 1;
    }
    if (bound != NULL) {
        *bound = 1;
    }
    return written;
}
