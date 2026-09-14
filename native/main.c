#include "aim.h"
#include "thoraim.h"

#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/timerfd.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t reload_wanted = 0;
static volatile sig_atomic_t stop_wanted = 0;

static void on_hup(int sig) { (void)sig; reload_wanted = 1; }
static void on_term(int sig) { (void)sig; stop_wanted = 1; }

static double now_seconds(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

static int to_pixels(double fraction, int lo, int hi) {
    double span = (double)(hi - lo);
    int v = (int)lround(lo + fraction * span);
    return v < lo ? lo : (v > hi ? hi : v);
}

int main(int argc, char **argv) {
    const char *config_path = argc > 1 ? argv[1] : "/data/local/tmp/thoraim.conf";
    int prefer_touch_width = argc > 2 ? atoi(argv[2]) : 0;

    setvbuf(stdout, NULL, _IOLBF, 0);
    signal(SIGHUP, on_hup);
    signal(SIGTERM, on_term);
    signal(SIGINT, on_term);
    signal(SIGPIPE, SIG_IGN);

    struct config cfg;
    config_defaults(&cfg);
    config_load(&cfg, config_path);

    struct found_devices dev;
    if (!find_devices(&dev, prefer_touch_width)) {
        fprintf(stderr, "thoraim: no gamepad found (is the controller awake?)\n");
        return 2;
    }
    printf("thoraim: gamepad %s (%s) axes %d/%d\n",
           dev.gamepad_path, dev.gamepad_name, dev.axis_x, dev.axis_y);
    if (dev.touch_fd >= 0) {
        printf("thoraim: screen %s (%s) %d..%d x %d..%d\n",
               dev.touch_path, dev.touch_name,
               dev.touch_x.minimum, dev.touch_x.maximum,
               dev.touch_y.minimum, dev.touch_y.maximum);
        close(dev.touch_fd);
        dev.touch_fd = -1;
    } else {
        fprintf(stderr, "thoraim: no touchscreen found; using 1080x1920\n");
    }

    struct uinput_touch touch;
    if (!touch_create(&touch, &dev)) {
        fprintf(stderr, "thoraim: cannot open /dev/uinput (%s) — need root\n",
                strerror(errno));
        return 3;
    }
    printf("thoraim: virtual touchscreen ready\n");

    int timer = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK);
    struct itimerspec tick;
    memset(&tick, 0, sizeof(tick));
    long period_ns = 1000000000L / cfg.poll_hz;
    tick.it_interval.tv_nsec = period_ns;
    tick.it_value.tv_nsec = period_ns;
    timerfd_settime(timer, 0, &tick, NULL);

    int poller = epoll_create1(0);
    struct epoll_event want;
    memset(&want, 0, sizeof(want));
    want.events = EPOLLIN;
    want.data.fd = dev.gamepad_fd;
    epoll_ctl(poller, EPOLL_CTL_ADD, dev.gamepad_fd, &want);
    want.data.fd = timer;
    epoll_ctl(poller, EPOLL_CTL_ADD, timer, &want);

    int raw_x = (dev.range_x.minimum + dev.range_x.maximum) / 2;
    int raw_y = (dev.range_y.minimum + dev.range_y.maximum) / 2;
    int enabled = cfg.start_enabled;
    struct aim_state aim;
    aim_reset(&aim);
    double last_tick = now_seconds();

    printf("thoraim: running (%s)\n", enabled ? "on" : "off");

    struct epoll_event events[4];
    while (!stop_wanted) {
        int count = epoll_wait(poller, events, 4, 1000);
        if (count < 0) {
            if (errno == EINTR) {
                if (reload_wanted) {
                    reload_wanted = 0;
                    config_load(&cfg, config_path);
                    printf("thoraim: config reloaded\n");
                }
                continue;
            }
            break;
        }

        for (int i = 0; i < count; i++) {
            if (events[i].data.fd == dev.gamepad_fd) {
                struct input_event in[64];
                ssize_t got;
                while ((got = read(dev.gamepad_fd, in, sizeof(in))) > 0) {
                    size_t n = (size_t)got / sizeof(struct input_event);
                    for (size_t k = 0; k < n; k++) {
                        if (in[k].type == EV_ABS) {
                            if (in[k].code == dev.axis_x) raw_x = in[k].value;
                            else if (in[k].code == dev.axis_y) raw_y = in[k].value;
                        } else if (in[k].type == EV_KEY && in[k].value == 1 &&
                                   cfg.toggle_button &&
                                   in[k].code == cfg.toggle_button) {
                            enabled = !enabled;
                            if (!enabled) {
                                // Let go immediately. Leaving a finger down on a
                                // menu is how a toggle turns into a stuck touch.
                                touch_up(&touch);
                                aim_reset(&aim);
                            }
                            printf("thoraim: %s\n", enabled ? "on" : "off");
                        }
                    }
                }
                continue;
            }

            if (events[i].data.fd != timer) continue;

            uint64_t ticks;
            ssize_t drained = read(timer, &ticks, sizeof(ticks));
            (void)drained;

            double now = now_seconds();
            double dt = now - last_tick;
            last_tick = now;
            if (dt <= 0 || dt > 0.25) dt = 1.0 / cfg.poll_hz;

            if (!enabled) continue;

            double sx = aim_normalise(raw_x, dev.range_x.minimum,
                                      dev.range_x.maximum);
            double sy = aim_normalise(raw_y, dev.range_y.minimum,
                                      dev.range_y.maximum);

            struct aim_out step = aim_step(&aim, &cfg, sx, sy, dt, now);
            int px = to_pixels(step.x, touch.min_x, touch.max_x);
            int py = to_pixels(step.y, touch.min_y, touch.max_y);

            switch (step.action) {
                case AIM_PRESS:
                    touch_down(&touch, px, py);
                    break;
                case AIM_MOVE:
                    touch_move(&touch, px, py);
                    break;
                case AIM_LIFT:
                    touch_up(&touch);
                    break;
                case AIM_RESTART:
                    touch_up(&touch);
                    touch_down(&touch, px, py);
                    break;
                case AIM_NONE:
                    break;
            }
        }

        if (reload_wanted) {
            reload_wanted = 0;
            config_load(&cfg, config_path);
            period_ns = 1000000000L / cfg.poll_hz;
            tick.it_interval.tv_nsec = period_ns;
            tick.it_value.tv_nsec = period_ns;
            timerfd_settime(timer, 0, &tick, NULL);
            printf("thoraim: config reloaded\n");
        }
    }

    printf("thoraim: stopping\n");
    touch_destroy(&touch);
    close(dev.gamepad_fd);
    close(timer);
    close(poller);
    return 0;
}
