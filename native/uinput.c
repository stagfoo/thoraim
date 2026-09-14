// The virtual finger.
//
// A multitouch device rather than a single-touch one, and with the *same*
// absolute ranges as the real touchscreen: Android works out which display an
// input device belongs to partly from its dimensions, and a virtual screen with
// invented ranges is liable to be matched to the wrong panel — which on a
// two-screen handheld means aiming the other monitor.

#include "thoraim.h"

#include <fcntl.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

static void emit(int fd, int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = (unsigned short)type;
    ev.code = (unsigned short)code;
    ev.value = value;
    ssize_t written = write(fd, &ev, sizeof(ev));
    (void)written;
}

static void sync_now(int fd) { emit(fd, EV_SYN, SYN_REPORT, 0); }

int touch_create(struct uinput_touch *t, const struct found_devices *dev) {
    memset(t, 0, sizeof(*t));
    t->fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (t->fd < 0) return 0;

    t->min_x = dev->touch_x.minimum;
    t->max_x = dev->touch_x.maximum;
    t->min_y = dev->touch_y.minimum;
    t->max_y = dev->touch_y.maximum;
    if (t->max_x <= t->min_x) { t->min_x = 0; t->max_x = 1079; }
    if (t->max_y <= t->min_y) { t->min_y = 0; t->max_y = 1919; }

    ioctl(t->fd, UI_SET_EVBIT, EV_ABS);
    ioctl(t->fd, UI_SET_EVBIT, EV_KEY);
    ioctl(t->fd, UI_SET_EVBIT, EV_SYN);
    ioctl(t->fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

    ioctl(t->fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(t->fd, UI_SET_KEYBIT, BTN_TOOL_FINGER);

    ioctl(t->fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(t->fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(t->fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(t->fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(t->fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);
    ioctl(t->fd, UI_SET_ABSBIT, ABS_MT_PRESSURE);
    ioctl(t->fd, UI_SET_ABSBIT, ABS_X);
    ioctl(t->fd, UI_SET_ABSBIT, ABS_Y);

    struct uinput_abs_setup abs;
    memset(&abs, 0, sizeof(abs));

#define SETUP_ABS(CODE, MIN, MAX, RES)              \
    do {                                            \
        memset(&abs, 0, sizeof(abs));               \
        abs.code = (CODE);                          \
        abs.absinfo.minimum = (MIN);                \
        abs.absinfo.maximum = (MAX);                \
        abs.absinfo.resolution = (RES);             \
        ioctl(t->fd, UI_ABS_SETUP, &abs);           \
    } while (0)

    SETUP_ABS(ABS_MT_SLOT, 0, 9, 0);
    SETUP_ABS(ABS_MT_TRACKING_ID, 0, 65535, 0);
    SETUP_ABS(ABS_MT_POSITION_X, t->min_x, t->max_x, dev->touch_x.resolution);
    SETUP_ABS(ABS_MT_POSITION_Y, t->min_y, t->max_y, dev->touch_y.resolution);
    SETUP_ABS(ABS_MT_TOUCH_MAJOR, 0, 255, 0);
    SETUP_ABS(ABS_MT_PRESSURE, 0, 255, 0);
    SETUP_ABS(ABS_X, t->min_x, t->max_x, dev->touch_x.resolution);
    SETUP_ABS(ABS_Y, t->min_y, t->max_y, dev->touch_y.resolution);
#undef SETUP_ABS

    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_VIRTUAL;
    // Deliberately not pretending to be the real panel: a duplicate of the
    // hardware's own ids is how you end up with the system loading the real
    // screen's calibration for this one.
    setup.id.vendor = 0x1209;
    setup.id.product = 0x7a11;
    setup.id.version = 1;
    snprintf(setup.name, sizeof(setup.name), "thoraim virtual touchscreen");

    if (ioctl(t->fd, UI_DEV_SETUP, &setup) < 0) { close(t->fd); t->fd = -1; return 0; }
    if (ioctl(t->fd, UI_DEV_CREATE) < 0) { close(t->fd); t->fd = -1; return 0; }

    // The kernel needs a moment to publish the node before anything written to
    // it will be seen.
    struct timespec wait = {0, 300L * 1000000L};
    nanosleep(&wait, NULL);

    t->tracking_id = 1;
    return 1;
}

static int clamp(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

void touch_down(struct uinput_touch *t, int x, int y) {
    if (t->fd < 0 || t->down) return;
    x = clamp(x, t->min_x, t->max_x);
    y = clamp(y, t->min_y, t->max_y);

    emit(t->fd, EV_ABS, ABS_MT_SLOT, 0);
    emit(t->fd, EV_ABS, ABS_MT_TRACKING_ID, t->tracking_id);
    emit(t->fd, EV_ABS, ABS_MT_POSITION_X, x);
    emit(t->fd, EV_ABS, ABS_MT_POSITION_Y, y);
    emit(t->fd, EV_ABS, ABS_MT_TOUCH_MAJOR, 24);
    emit(t->fd, EV_ABS, ABS_MT_PRESSURE, 64);
    emit(t->fd, EV_KEY, BTN_TOUCH, 1);
    emit(t->fd, EV_KEY, BTN_TOOL_FINGER, 1);
    emit(t->fd, EV_ABS, ABS_X, x);
    emit(t->fd, EV_ABS, ABS_Y, y);
    sync_now(t->fd);

    t->down = 1;
}

void touch_move(struct uinput_touch *t, int x, int y) {
    if (t->fd < 0 || !t->down) return;
    x = clamp(x, t->min_x, t->max_x);
    y = clamp(y, t->min_y, t->max_y);

    emit(t->fd, EV_ABS, ABS_MT_SLOT, 0);
    emit(t->fd, EV_ABS, ABS_MT_POSITION_X, x);
    emit(t->fd, EV_ABS, ABS_MT_POSITION_Y, y);
    emit(t->fd, EV_ABS, ABS_X, x);
    emit(t->fd, EV_ABS, ABS_Y, y);
    sync_now(t->fd);
}

void touch_up(struct uinput_touch *t) {
    if (t->fd < 0 || !t->down) return;

    emit(t->fd, EV_ABS, ABS_MT_SLOT, 0);
    emit(t->fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
    emit(t->fd, EV_KEY, BTN_TOUCH, 0);
    emit(t->fd, EV_KEY, BTN_TOOL_FINGER, 0);
    sync_now(t->fd);

    t->down = 0;
    // A fresh id per stroke: reusing one makes the next press look to the
    // system like the same finger teleporting, which a game reads as one
    // enormous swipe.
    if (++t->tracking_id > 60000) t->tracking_id = 1;
}

void touch_destroy(struct uinput_touch *t) {
    if (t->fd < 0) return;
    touch_up(t);
    ioctl(t->fd, UI_DEV_DESTROY);
    close(t->fd);
    t->fd = -1;
}
