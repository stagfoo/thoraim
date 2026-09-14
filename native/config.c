// Tuning values, read from a file the app writes.
//
// A file rather than a socket or a channel: every one of these numbers needs to
// be found by feel against real gameplay, and a file can be re-read on SIGHUP
// mid-fight without dropping the finger that is currently down.

#include "thoraim.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

void config_defaults(struct config *c) {
    memset(c, 0, sizeof(*c));
    c->deadzone = 0.12;
    // Above 1 so a small tilt moves slowly. A shooter needs both a nudge to
    // correct aim and a shove to whip round, and a linear stick gives neither
    // well.
    c->curve = 2.0;
    // Screen widths per second at full tilt.
    c->max_speed = 2.2;
    c->invert_y = 0;

    // The whole screen, because travel is the whole problem: a stroke ends at
    // the region edge, so anything smaller than the screen is throwing away
    // swing for nothing.
    c->region_left = 0.0;
    c->region_top = 0.0;
    c->region_right = 1.0;
    c->region_bottom = 1.0;

    c->anchor_bias = 0.85;
    c->hold_ms = 220;
    c->poll_hz = 120;
    c->toggle_button = BTN_THUMBR;
    c->start_enabled = 1;
}

static void set(struct config *c, const char *key, const char *value) {
    double number = atof(value);
    if (!strcmp(key, "deadzone")) c->deadzone = number;
    else if (!strcmp(key, "curve")) c->curve = number;
    else if (!strcmp(key, "max_speed")) c->max_speed = number;
    else if (!strcmp(key, "invert_y")) c->invert_y = (int)number;
    else if (!strcmp(key, "region_left")) c->region_left = number;
    else if (!strcmp(key, "region_top")) c->region_top = number;
    else if (!strcmp(key, "region_right")) c->region_right = number;
    else if (!strcmp(key, "region_bottom")) c->region_bottom = number;
    else if (!strcmp(key, "anchor_bias")) c->anchor_bias = number;
    else if (!strcmp(key, "hold_ms")) c->hold_ms = (int)number;
    else if (!strcmp(key, "poll_hz")) c->poll_hz = (int)number;
    else if (!strcmp(key, "toggle_button")) c->toggle_button = (int)number;
    else if (!strcmp(key, "start_enabled")) c->start_enabled = (int)number;
}

static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

int config_load(struct config *c, const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) return 0;

    char line[256];
    while (fgets(line, sizeof(line), f)) {
        char *hash = strchr(line, '#');
        if (hash) *hash = 0;
        char *eq = strchr(line, '=');
        if (!eq) continue;
        *eq = 0;

        char *key = line;
        char *value = eq + 1;
        while (*key == ' ' || *key == '\t') key++;
        char *end = key + strlen(key);
        while (end > key && (end[-1] == ' ' || end[-1] == '\t')) *--end = 0;

        set(c, key, value);
    }
    fclose(f);

    // Clamped after loading rather than trusted: these come from a file on
    // disk, and a zero poll rate or an inside-out region would be a hang or a
    // divide by zero rather than a bad setting.
    c->deadzone = clamp01(c->deadzone);
    if (c->deadzone > 0.9) c->deadzone = 0.9;
    if (c->curve < 0.2) c->curve = 0.2;
    if (c->curve > 6.0) c->curve = 6.0;
    if (c->max_speed < 0.05) c->max_speed = 0.05;
    if (c->max_speed > 20.0) c->max_speed = 20.0;

    c->region_left = clamp01(c->region_left);
    c->region_top = clamp01(c->region_top);
    c->region_right = clamp01(c->region_right);
    c->region_bottom = clamp01(c->region_bottom);
    if (c->region_right - c->region_left < 0.05) {
        c->region_left = 0.0;
        c->region_right = 1.0;
    }
    if (c->region_bottom - c->region_top < 0.05) {
        c->region_top = 0.0;
        c->region_bottom = 1.0;
    }

    c->anchor_bias = clamp01(c->anchor_bias);
    if (c->hold_ms < 0) c->hold_ms = 0;
    if (c->hold_ms > 5000) c->hold_ms = 5000;
    if (c->poll_hz < 30) c->poll_hz = 30;
    if (c->poll_hz > 250) c->poll_hz = 250;
    return 1;
}
