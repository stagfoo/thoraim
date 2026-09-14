// Finding the gamepad and the touchscreen, without hardcoding event numbers.
//
// Event node numbers move. The reference implementation for this device ships
// three different fallbacks in three different files (event3, event4, event8)
// precisely because they did — InputPlumber, a reboot or a plugged controller
// reshuffles them. So nothing here is hardcoded: devices are found by what they
// can do, which is stable.

#include "thoraim.h"

#include <ctype.h>
#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static int bit_set(const unsigned long *bits, int code) {
    return (bits[code / LONG_BITS] >> (code % LONG_BITS)) & 1UL;
}

// Reads one capability bitmap off an open device.
static int caps(int fd, int type, unsigned long *bits, size_t words) {
    memset(bits, 0, words * sizeof(unsigned long));
    return ioctl(fd, EVIOCGBIT(type, words * sizeof(unsigned long)), bits) >= 0;
}

int dev_name(int fd, char *out, size_t len) {
    if (ioctl(fd, EVIOCGNAME(len), out) < 0) {
        snprintf(out, len, "?");
        return 0;
    }
    out[len - 1] = 0;
    return 1;
}

// A gamepad is anything with a right stick on it. Matching on the name is how
// you end up supporting one controller: "Xbox Controller" today, something else
// after a firmware update.
static int looks_like_gamepad(int fd) {
    unsigned long ev[NLONGS(EV_MAX)], abs[NLONGS(ABS_MAX)], key[NLONGS(KEY_MAX)];
    if (!caps(fd, 0, ev, NLONGS(EV_MAX))) return 0;
    if (!bit_set(ev, EV_ABS) || !bit_set(ev, EV_KEY)) return 0;
    if (!caps(fd, EV_ABS, abs, NLONGS(ABS_MAX))) return 0;
    if (!caps(fd, EV_KEY, key, NLONGS(KEY_MAX))) return 0;

    int has_right_stick = bit_set(abs, ABS_RX) && bit_set(abs, ABS_RY);
    // Some pads report the right stick as Z/RZ instead. Both are accepted, and
    // which one a device used is remembered rather than guessed at again later.
    int has_z_stick = bit_set(abs, ABS_Z) && bit_set(abs, ABS_RZ);
    int has_buttons = bit_set(key, BTN_SOUTH) || bit_set(key, BTN_A);

    return (has_right_stick || has_z_stick) && has_buttons;
}

// A direct touchscreen, as opposed to a touchpad: INPUT_PROP_DIRECT is the
// kernel saying "this surface is the screen", which is exactly the difference.
static int looks_like_touchscreen(int fd) {
    unsigned long ev[NLONGS(EV_MAX)], abs[NLONGS(ABS_MAX)];
    unsigned long props[NLONGS(INPUT_PROP_MAX)];

    if (!caps(fd, 0, ev, NLONGS(EV_MAX))) return 0;
    if (!bit_set(ev, EV_ABS)) return 0;
    if (!caps(fd, EV_ABS, abs, NLONGS(ABS_MAX))) return 0;
    if (!bit_set(abs, ABS_MT_POSITION_X) || !bit_set(abs, ABS_MT_POSITION_Y)) {
        return 0;
    }

    memset(props, 0, sizeof(props));
    if (ioctl(fd, EVIOCGPROP(sizeof(props)), props) >= 0) {
        if (bit_set(props, INPUT_PROP_POINTER)) return 0;  // a touchpad
    }
    return 1;
}

static int axis_range(int fd, int axis, struct input_absinfo *info) {
    return ioctl(fd, EVIOCGABS(axis), info) >= 0;
}

int find_devices(struct found_devices *out, int prefer_touch_width) {
    DIR *dir = opendir("/dev/input");
    if (!dir) return 0;

    memset(out, 0, sizeof(*out));
    out->gamepad_fd = -1;
    out->touch_fd = -1;

    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strncmp(entry->d_name, "event", 5) != 0) continue;

        char path[64];
        snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;

        char name[128];
        dev_name(fd, name, sizeof(name));

        if (out->gamepad_fd < 0 && looks_like_gamepad(fd)) {
            unsigned long abs[NLONGS(ABS_MAX)];
            caps(fd, EV_ABS, abs, NLONGS(ABS_MAX));

            out->gamepad_fd = fd;
            snprintf(out->gamepad_path, sizeof(out->gamepad_path), "%s", path);
            snprintf(out->gamepad_name, sizeof(out->gamepad_name), "%s", name);

            if (bit_set(abs, ABS_RX) && bit_set(abs, ABS_RY)) {
                out->axis_x = ABS_RX;
                out->axis_y = ABS_RY;
            } else {
                out->axis_x = ABS_Z;
                out->axis_y = ABS_RZ;
            }
            axis_range(fd, out->axis_x, &out->range_x);
            axis_range(fd, out->axis_y, &out->range_y);
            continue;
        }

        if (looks_like_touchscreen(fd)) {
            struct input_absinfo x, y;
            if (!axis_range(fd, ABS_MT_POSITION_X, &x) ||
                !axis_range(fd, ABS_MT_POSITION_Y, &y)) {
                close(fd);
                continue;
            }
            int width = x.maximum - x.minimum + 1;

            // On a two-screen handheld there are two of these, and the injected
            // touches have to land on the one the game is on. Which is which
            // cannot be asked, so it is configured by width — and with nothing
            // configured, the larger panel wins, since that is the one a game
            // is on.
            int better = out->touch_fd < 0;
            if (!better && prefer_touch_width > 0) {
                int have = out->touch_x.maximum - out->touch_x.minimum + 1;
                better = abs_int(width - prefer_touch_width) <
                         abs_int(have - prefer_touch_width);
            } else if (!better) {
                better = width > (out->touch_x.maximum - out->touch_x.minimum + 1);
            }

            if (better) {
                if (out->touch_fd >= 0) close(out->touch_fd);
                out->touch_fd = fd;
                out->touch_x = x;
                out->touch_y = y;
                snprintf(out->touch_path, sizeof(out->touch_path), "%s", path);
                snprintf(out->touch_name, sizeof(out->touch_name), "%s", name);
                // Kept open only long enough to copy its shape; a grab here
                // would take the real screen away from the game.
                continue;
            }
        }

        close(fd);
    }

    closedir(dir);
    return out->gamepad_fd >= 0;
}

int abs_int(int v) { return v < 0 ? -v : v; }
