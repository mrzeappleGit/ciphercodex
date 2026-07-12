/* input_probe: print evdev ABS ranges and live events for the hardware audit.
 * Usage: input_probe /dev/input/event1 [seconds]
 * No dependencies beyond libc; cross-compiles with the SDK gcc alone.
 */
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static const char *abs_name(int code)
{
    switch (code) {
    case ABS_X: return "ABS_X";
    case ABS_Y: return "ABS_Y";
    case ABS_PRESSURE: return "ABS_PRESSURE";
    case ABS_DISTANCE: return "ABS_DISTANCE";
    case ABS_TILT_X: return "ABS_TILT_X";
    case ABS_TILT_Y: return "ABS_TILT_Y";
    case ABS_MT_POSITION_X: return "ABS_MT_POSITION_X";
    case ABS_MT_POSITION_Y: return "ABS_MT_POSITION_Y";
    case ABS_MT_PRESSURE: return "ABS_MT_PRESSURE";
    case ABS_MT_TRACKING_ID: return "ABS_MT_TRACKING_ID";
    case ABS_MT_SLOT: return "ABS_MT_SLOT";
    default: return NULL;
    }
}

static const char *key_name(int code)
{
    switch (code) {
    case BTN_TOOL_PEN: return "BTN_TOOL_PEN";
    case BTN_TOOL_RUBBER: return "BTN_TOOL_RUBBER";
    case BTN_TOUCH: return "BTN_TOUCH";
    case BTN_STYLUS: return "BTN_STYLUS";
    case BTN_STYLUS2: return "BTN_STYLUS2";
    default: return NULL;
    }
}

int main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: %s /dev/input/eventN [seconds]\n", argv[0]);
        return 2;
    }
    int fd = open(argv[1], O_RDONLY);
    if (fd < 0) { perror("open"); return 1; }

    char name[128] = "?";
    ioctl(fd, EVIOCGNAME(sizeof(name)), name);
    printf("device: %s (%s)\n", name, argv[1]);

    unsigned long absbits[(ABS_MAX + 1) / (8 * sizeof(long)) + 1];
    memset(absbits, 0, sizeof(absbits));
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits);
    for (int code = 0; code <= ABS_MAX; code++) {
        if (!(absbits[code / (8 * sizeof(long))] >> (code % (8 * sizeof(long))) & 1))
            continue;
        struct input_absinfo ai;
        if (ioctl(fd, EVIOCGABS(code), &ai) < 0)
            continue;
        const char *n = abs_name(code);
        printf("abs 0x%02x %-20s min=%d max=%d fuzz=%d flat=%d res=%d\n",
               code, n ? n : "?", ai.minimum, ai.maximum, ai.fuzz, ai.flat, ai.resolution);
    }

    int seconds = argc > 2 ? atoi(argv[2]) : 0;
    if (seconds <= 0)
        return 0;

    printf("--- live events for %ds ---\n", seconds);
    time_t end = time(NULL) + seconds;
    struct input_event ev;
    while (time(NULL) < end) {
        ssize_t n = read(fd, &ev, sizeof(ev));
        if (n != sizeof(ev))
            break;
        if (ev.type == EV_ABS) {
            const char *an = abs_name(ev.code);
            printf("%ld.%06ld ABS %-20s %d\n", (long)ev.input_event_sec,
                   (long)ev.input_event_usec, an ? an : "?", ev.value);
        } else if (ev.type == EV_KEY) {
            const char *kn = key_name(ev.code);
            printf("%ld.%06ld KEY %-20s %d\n", (long)ev.input_event_sec,
                   (long)ev.input_event_usec, kn ? kn : "?", ev.value);
        }
    }
    close(fd);
    return 0;
}
