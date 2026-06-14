/*
 * SDL2 stub, ABI-compatible shims for a tiny subset of SDL2, enough to
 * exercise the kyo-ffi event-loop idiom (window create, event poll with a
 * transient callback, quit + destroy) without depending on SDL2 being
 * installed.
 *
 * The real-world user would swap this stub for a linked dependency:
 *   - remove `ffiCSources`
 *   - add `ffiLinkLibs := Seq("SDL2")`
 *   - keep the Scala trait unchanged (method names + signatures line up).
 *
 * Event representation is a simple (type, code) pair matching the shape of
 * SDL's discriminated-union event but flattened to two ints for ABI
 * simplicity, the binding exercises callback-per-event which is the
 * architecturally interesting part.
 */

#include <stdint.h>

#define KYO_SDL_EVENT_QUIT     0x100
#define KYO_SDL_EVENT_KEYDOWN  0x300
#define KYO_SDL_EVENT_KEYUP    0x301

/* Fake init. Real SDL_Init parses flags. Stub accepts anything, returns 0 = ok. */
int kyo_sdl_init(int flags) {
    (void)flags;
    return 0;
}

int kyo_sdl_quit(int unused) {
    (void)unused;
    return 0;
}

/* Fake window create. Returns a handle > 0 on success. */
int64_t kyo_sdl_create_window(int width, int height) {
    if (width <= 0 || height <= 0) return 0;
    return (int64_t)0xABC0000u + (int64_t)width; /* deterministic sentinel */
}

void kyo_sdl_destroy_window(int64_t window) {
    (void)window;
}

/* Drive a small scripted event pump: 3 keydowns, 3 keyups, 1 quit. The
 * callback is invoked once per event; returning non-zero aborts the pump. */
int kyo_sdl_run_events(int (*cb)(int event_type, int code)) {
    int processed = 0;
    int events[7][2] = {
        {KYO_SDL_EVENT_KEYDOWN, 'a'},
        {KYO_SDL_EVENT_KEYDOWN, 'b'},
        {KYO_SDL_EVENT_KEYDOWN, 'c'},
        {KYO_SDL_EVENT_KEYUP,   'a'},
        {KYO_SDL_EVENT_KEYUP,   'b'},
        {KYO_SDL_EVENT_KEYUP,   'c'},
        {KYO_SDL_EVENT_QUIT,    0}
    };
    for (int i = 0; i < 7; i++) {
        int rc = cb(events[i][0], events[i][1]);
        processed++;
        if (rc != 0) break;
    }
    return processed;
}
