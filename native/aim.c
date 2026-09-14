#include "aim.h"
#include "thoraim.h"

#include <math.h>

void aim_reset(struct aim_state *s) {
    s->x = 0.5;
    s->y = 0.5;
    s->down = 0;
    s->idle_since = -1;
}

double aim_normalise(int raw, int minimum, int maximum) {
    if (maximum <= minimum) return 0.0;
    double mid = (minimum + maximum) / 2.0;
    double half = (maximum - minimum) / 2.0;
    double v = (raw - mid) / half;
    return v < -1.0 ? -1.0 : (v > 1.0 ? 1.0 : v);
}

void aim_anchor(const struct config *c, double dx, double dy,
                double *out_x, double *out_y) {
    double cx = (c->region_left + c->region_right) / 2.0;
    double cy = (c->region_top + c->region_bottom) / 2.0;
    double half_w = (c->region_right - c->region_left) / 2.0;
    double half_h = (c->region_bottom - c->region_top) / 2.0;

    double mag = sqrt(dx * dx + dy * dy);
    if (mag < 1e-9) {
        *out_x = cx;
        *out_y = cy;
        return;
    }

    // Against the far side, opposite the way the stick points — not the middle.
    // Starting centred throws away half the region before the swing has begun,
    // so a sweep runs out of screen in half the distance it could have had.
    //
    // A margin is left so the press never lands on the very edge, where a game
    // is apt to have an edge gesture or a UI button waiting.
    const double margin = 0.04;
    *out_x = cx - (dx / mag) * half_w * c->anchor_bias * (1.0 - margin);
    *out_y = cy - (dy / mag) * half_h * c->anchor_bias * (1.0 - margin);
}

struct aim_out aim_step(struct aim_state *s, const struct config *c,
                        double sx, double sy, double dt, double now) {
    struct aim_out out = {AIM_NONE, s->x, s->y};

    if (c->invert_y) sy = -sy;

    double mag = sqrt(sx * sx + sy * sy);
    if (mag > 1.0) {
        sx /= mag;
        sy /= mag;
        mag = 1.0;
    }

    if (mag <= c->deadzone) {
        if (!s->down) return out;
        if (s->idle_since < 0) s->idle_since = now;
        // Held for a moment rather than lifted on the instant: pausing
        // mid-sweep to line a shot up should not cost a whole stroke, because
        // getting the stroke back costs a jump back to the anchor.
        if ((now - s->idle_since) * 1000.0 >= (double)c->hold_ms) {
            s->down = 0;
            s->idle_since = -1;
            out.action = AIM_LIFT;
        }
        return out;
    }
    s->idle_since = -1;

    // Rescaled past the deadzone, so the slowest movement is the first one
    // past the edge rather than a jump to whatever speed that edge sits at.
    double past = (mag - c->deadzone) / (1.0 - c->deadzone);
    if (past < 0) past = 0;
    double speed = c->max_speed * pow(past, c->curve);
    double dx = (sx / mag) * speed * dt;
    double dy = (sy / mag) * speed * dt;

    if (!s->down) {
        aim_anchor(c, sx, sy, &s->x, &s->y);
        s->down = 1;
        s->x += dx;
        s->y += dy;
        out.action = AIM_PRESS;
        out.x = s->x;
        out.y = s->y;
        return out;
    }

    double nx = s->x + dx;
    double ny = s->y + dy;

    if (nx < c->region_left || nx > c->region_right ||
        ny < c->region_top || ny > c->region_bottom) {
        // Out of screen to drag across. Lift, go back to the far side, press
        // again — the hitch you feel on a long sweep, and the whole reason the
        // region wants to be as large as the game will allow.
        aim_anchor(c, sx, sy, &s->x, &s->y);
        s->x += dx;
        s->y += dy;
        out.action = AIM_RESTART;
        out.x = s->x;
        out.y = s->y;
        return out;
    }

    s->x = nx;
    s->y = ny;
    out.action = AIM_MOVE;
    out.x = nx;
    out.y = ny;
    return out;
}
