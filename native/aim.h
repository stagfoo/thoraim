// The aiming itself: stick in, finger position out.
//
// Split out from the device plumbing on purpose. Everything that decides
// whether this feels like aiming — the deadzone, the response curve, where a
// stroke starts, when it has to restart — is arithmetic, and arithmetic can be
// checked on a desktop against numbers rather than by squinting at a handheld.
// The evdev and uinput halves cannot be, so they are kept out of here.

#ifndef THORAIM_AIM_H
#define THORAIM_AIM_H

struct config;

enum aim_action {
    AIM_NONE,     // nothing to do
    AIM_PRESS,    // put the finger down at x, y
    AIM_MOVE,     // drag it to x, y
    AIM_LIFT,     // take it off
    AIM_RESTART   // lift, then press again at x, y: travel ran out
};

struct aim_state {
    double x, y;        // finger position, in fractions of the screen
    int down;
    double idle_since;  // when the stick centred; negative while it is not
};

struct aim_out {
    enum aim_action action;
    double x, y;
};

void aim_reset(struct aim_state *s);

/// One tick. [sx], [sy] are the raw stick, already -1..1; [dt] seconds since
/// the last tick; [now] a monotonic clock in seconds.
struct aim_out aim_step(struct aim_state *s, const struct config *c,
                        double sx, double sy, double dt, double now);

/// Where a stroke begins for a stick pointing [dx], [dy]. Exposed so the choice
/// can be measured rather than argued about.
void aim_anchor(const struct config *c, double dx, double dy,
                double *out_x, double *out_y);

/// A raw axis reading as -1..1, given the range the device reports.
double aim_normalise(int raw, int minimum, int maximum);

#endif
