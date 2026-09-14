// Stick-to-touch aiming for the AYN Thor.
//
// A game like NIKKE aims by reading a finger dragging across the screen. It has
// no idea a gamepad exists, and no amount of button mapping helps, because
// there is no button to map to — the input it wants is a continuous drag. So
// this reads the right stick and *is* that finger: a virtual touchscreen on
// /dev/uinput, pressed down and dragged around under the game's nose.
//
// Two things decide whether it feels like aiming or like fighting the controls:
//
//   Deflection is velocity, not position. A stick that snapped the cursor to a
//   matching spot on the screen would make every small correction a jump.
//
//   A drag has an end. The finger can only travel to the edge of the region
//   before it has to lift and start again, and that hitch is the thing you feel.
//   So the region is the whole screen by default, and a stroke starts at the far
//   side of it rather than in the middle — which is worth as much again, because
//   starting centred throws away half the travel before you have begun.

#ifndef THORAIM_H
#define THORAIM_H

#include <linux/input.h>
#include <stddef.h>

#define LONG_BITS (sizeof(unsigned long) * 8)
#define NLONGS(x) (((x) + LONG_BITS - 1) / LONG_BITS)

#ifndef BTN_A
#define BTN_A 0x130
#endif

struct found_devices {
    int gamepad_fd;
    char gamepad_path[64];
    char gamepad_name[128];
    int axis_x;
    int axis_y;
    struct input_absinfo range_x;
    struct input_absinfo range_y;

    int touch_fd;
    char touch_path[64];
    char touch_name[128];
    struct input_absinfo touch_x;
    struct input_absinfo touch_y;
};

struct config {
    // Stick handling
    double deadzone;       // 0..1 of full deflection
    double curve;          // 1 linear; >1 gives fine control near centre
    double max_speed;      // screen widths per second at full tilt
    int invert_y;

    // The patch of screen the finger is allowed to use, in fractions of the
    // touchscreen. Defaults to all of it.
    double region_left, region_top, region_right, region_bottom;

    // 0 starts every stroke at the middle of the region; 1 starts it hard
    // against the far edge, which buys nearly the whole region of travel in the
    // direction you are actually swinging.
    double anchor_bias;

    // How long the finger stays down after the stick centres, in milliseconds.
    // Lifting the instant it centres turns a pause mid-swing into a recentre.
    int hold_ms;

    int poll_hz;
    int toggle_button;     // evdev code, 0 to disable
    int start_enabled;
};

void config_defaults(struct config *c);
int config_load(struct config *c, const char *path);

int find_devices(struct found_devices *out, int prefer_touch_width);
int dev_name(int fd, char *out, size_t len);
int abs_int(int v);

struct uinput_touch {
    int fd;
    int min_x, max_x, min_y, max_y;
    int down;
    int tracking_id;
};

int touch_create(struct uinput_touch *t, const struct found_devices *dev);
void touch_down(struct uinput_touch *t, int x, int y);
void touch_move(struct uinput_touch *t, int x, int y);
void touch_up(struct uinput_touch *t);
void touch_destroy(struct uinput_touch *t);

#endif
