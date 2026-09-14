// Runs on a desktop, against numbers.
//
// Everything here is a claim about feel that can be stated as arithmetic: how
// far one swing travels, how slow the slowest useful movement is, how often a
// sweep has to restart. On-device testing is still needed to pick the
// constants, but not to find out whether the maths does what it says.

#include "aim.h"
#include "thoraim.h"

#include <math.h>
#include <stdio.h>
#include <string.h>

static int failures = 0;
static int checks = 0;

static void ok(int condition, const char *what) {
    checks++;
    if (!condition) {
        failures++;
        printf("  FAIL  %s\n", what);
    }
}

static void near(double got, double want, double tolerance, const char *what) {
    checks++;
    if (fabs(got - want) > tolerance) {
        failures++;
        printf("  FAIL  %s: got %.4f want %.4f\n", what, got, want);
    }
}

// Runs the stick held at (sx, sy) for `seconds`, reporting how far the finger
// travelled and how many times the stroke had to restart.
struct sweep {
    double distance;   // total, in fractions of a screen width
    double longest;    // the longest single unbroken stroke
    int restarts;
    int presses;
};

static struct sweep run(const struct config *c, double sx, double sy,
                        double seconds) {
    struct aim_state s;
    aim_reset(&s);

    struct sweep result;
    memset(&result, 0, sizeof(result));

    const double dt = 1.0 / c->poll_hz;
    double stroke = 0;
    double last_x = 0, last_y = 0;
    int have_last = 0;

    for (double t = 0; t < seconds; t += dt) {
        struct aim_out out = aim_step(&s, c, sx, sy, dt, t);
        switch (out.action) {
            case AIM_PRESS:
                result.presses++;
                stroke = 0;
                last_x = out.x;
                last_y = out.y;
                have_last = 1;
                break;
            case AIM_RESTART:
                result.restarts++;
                if (stroke > result.longest) result.longest = stroke;
                stroke = 0;
                last_x = out.x;
                last_y = out.y;
                break;
            case AIM_MOVE: {
                if (have_last) {
                    double step = hypot(out.x - last_x, out.y - last_y);
                    stroke += step;
                    result.distance += step;
                }
                last_x = out.x;
                last_y = out.y;
                have_last = 1;
                break;
            }
            default:
                break;
        }
    }
    if (stroke > result.longest) result.longest = stroke;
    return result;
}

static void test_deadzone(void) {
    printf("deadzone\n");
    struct config c;
    config_defaults(&c);
    struct aim_state s;
    aim_reset(&s);

    struct aim_out out = aim_step(&s, &c, 0.05, 0.0, 0.01, 0.0);
    ok(out.action == AIM_NONE, "a stick inside the deadzone does nothing");
    ok(!s.down, "and puts no finger down");

    // Drift is the reason this matters: a stick that rests at 0.05 would
    // otherwise aim slowly off target for ever.
    for (int i = 0; i < 500; i++) aim_step(&s, &c, 0.05, 0.03, 0.01, i * 0.01);
    ok(!s.down, "and still nothing after five seconds of drift");
}

static void test_first_movement_is_slow(void) {
    printf("response curve\n");
    struct config c;
    config_defaults(&c);

    // Just past the deadzone against full tilt: the point of the curve is that
    // these are nothing like each other.
    struct sweep nudge = run(&c, c.deadzone + 0.02, 0, 1.0);
    struct sweep shove = run(&c, 1.0, 0, 1.0);

    ok(nudge.distance > 0, "a nudge past the deadzone does move");
    ok(shove.distance > nudge.distance * 20,
       "full tilt is at least twenty times a nudge");
    printf("        nudge %.4f screens/s, full %.2f screens/s\n",
           nudge.distance, shove.distance);
}

static void test_full_tilt_speed_matches_config(void) {
    printf("top speed\n");
    struct config c;
    config_defaults(&c);
    // One second of full tilt should cover max_speed screens, give or take the
    // restarts, which cost nothing in distance.
    struct sweep s = run(&c, 1.0, 0, 1.0);
    near(s.distance, c.max_speed, c.max_speed * 0.05,
         "a second at full tilt covers max_speed screen widths");
}

static void test_stroke_uses_the_whole_region(void) {
    printf("travel per stroke\n");
    struct config c;
    config_defaults(&c);

    struct sweep s = run(&c, 1.0, 0, 3.0);
    // The whole complaint: a stroke that starts in the middle only ever gets
    // half the screen. Anchored against the far side it gets nearly all of it.
    ok(s.longest > 0.8,
       "one stroke crosses more than 80% of the screen");
    printf("        longest stroke %.3f screens, %d restarts in 3s\n",
           s.longest, s.restarts);

    struct config centred = c;
    centred.anchor_bias = 0.0;
    struct sweep middle = run(&centred, 1.0, 0, 3.0);
    printf("        centred anchor: longest %.3f, %d restarts\n",
           middle.longest, middle.restarts);

    ok(s.longest > middle.longest * 1.6,
       "anchoring to the far side beats starting centred by over half again");
    ok(s.restarts < middle.restarts,
       "and restarts less often over the same sweep");
}

static void test_region_size_is_what_buys_travel(void) {
    printf("region size\n");
    struct config big;
    config_defaults(&big);

    struct config small = big;
    small.region_left = 0.3;
    small.region_right = 0.7;
    small.region_top = 0.3;
    small.region_bottom = 0.7;

    struct sweep wide = run(&big, 1.0, 0, 3.0);
    struct sweep narrow = run(&small, 1.0, 0, 3.0);

    ok(wide.longest > narrow.longest * 2,
       "the full screen gives more than twice the travel of a 40% box");
    ok(wide.restarts < narrow.restarts,
       "and hitches far less often");
    printf("        full screen %.3f (%d restarts) vs 40%% box %.3f (%d)\n",
           wide.longest, wide.restarts, narrow.longest, narrow.restarts);
}

static void test_anchor_faces_away_from_travel(void) {
    printf("anchor placement\n");
    struct config c;
    config_defaults(&c);

    double x, y;
    aim_anchor(&c, 1.0, 0.0, &x, &y);   // swinging right
    ok(x < 0.2, "swinging right starts near the left edge");
    near(y, 0.5, 0.01, "and vertically centred");

    aim_anchor(&c, -1.0, 0.0, &x, &y);  // swinging left
    ok(x > 0.8, "swinging left starts near the right edge");

    aim_anchor(&c, 0.0, 1.0, &x, &y);   // swinging down
    ok(y < 0.2, "swinging down starts near the top");
    near(x, 0.5, 0.01, "and horizontally centred");

    aim_anchor(&c, 0.0, 0.0, &x, &y);
    near(x, 0.5, 0.001, "no direction means the middle, not a divide by zero");
    near(y, 0.5, 0.001, "in both axes");
}

static void test_pause_does_not_cost_a_stroke(void) {
    printf("pausing mid-sweep\n");
    struct config c;
    config_defaults(&c);
    struct aim_state s;
    aim_reset(&s);

    double t = 0;
    const double dt = 1.0 / c.poll_hz;
    for (int i = 0; i < 20; i++, t += dt) aim_step(&s, &c, 0.6, 0, dt, t);
    ok(s.down, "the finger is down mid-sweep");

    // Centre the stick for less than hold_ms.
    for (int i = 0; i < 5; i++, t += dt) aim_step(&s, &c, 0, 0, dt, t);
    ok(s.down, "a brief pause keeps the finger down");

    // And now for longer than hold_ms.
    double until = t + (c.hold_ms / 1000.0) + 0.05;
    struct aim_out out = {AIM_NONE, 0, 0};
    for (; t < until; t += dt) out = aim_step(&s, &c, 0, 0, dt, t);
    ok(!s.down, "letting go properly does lift the finger");
    (void)out;
}

static void test_normalise(void) {
    printf("axis ranges\n");
    // Different pads report different ranges; the maths cannot assume one.
    near(aim_normalise(32767, -32768, 32767), 1.0, 0.001, "signed 16-bit, full");
    near(aim_normalise(0, -32768, 32767), 0.0, 0.001, "signed 16-bit, centre");
    near(aim_normalise(255, 0, 255), 1.0, 0.001, "unsigned byte, full");
    near(aim_normalise(128, 0, 255), 0.0, 0.01, "unsigned byte, centre");
    near(aim_normalise(0, 0, 0), 0.0, 0.001, "a broken range is not a crash");
    near(aim_normalise(99999, -32768, 32767), 1.0, 0.001, "out of range clamps");
}

static void test_diagonal_is_not_faster(void) {
    printf("diagonals\n");
    struct config c;
    config_defaults(&c);
    // A stick pushed into a corner reads (1,1), which is magnitude 1.41. Left
    // alone that makes diagonal aiming 40% faster than straight, which feels
    // like the stick is broken.
    struct sweep straight = run(&c, 1.0, 0.0, 1.0);
    struct sweep corner = run(&c, 1.0, 1.0, 1.0);
    near(corner.distance, straight.distance, straight.distance * 0.05,
         "a corner is no faster than a straight push");
}

static void test_config_clamping(void) {
    printf("config bounds\n");
    struct config c;
    config_defaults(&c);
    ok(c.poll_hz >= 30 && c.poll_hz <= 250, "default poll rate is sane");
    ok(c.region_right > c.region_left, "default region is not inside out");
    ok(c.deadzone > 0 && c.deadzone < 1, "default deadzone is a fraction");
}

int main(void) {
    test_deadzone();
    test_first_movement_is_slow();
    test_full_tilt_speed_matches_config();
    test_stroke_uses_the_whole_region();
    test_region_size_is_what_buys_travel();
    test_anchor_faces_away_from_travel();
    test_pause_does_not_cost_a_stroke();
    test_normalise();
    test_diagonal_is_not_faster();
    test_config_clamping();

    printf("\n%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
