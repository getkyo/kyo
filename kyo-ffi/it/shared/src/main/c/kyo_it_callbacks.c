#include <stdint.h>
#include <stdlib.h>
#include <stddef.h>

/* Bundled callback surface.
 *
 * Two callback lifetime categories, transient and retained, are exercised
 * here. Transient callbacks are invoked synchronously during the FFI call
 * and not held past return. Retained callbacks are stashed in process-global
 * state and invoked by a later FFI call; their Scala-side upcall stubs must
 * outlive the register call, which is enforced in the binding trait via a
 * `Ffi.Guard` parameter.
 */

/* ---------- Transient callback: qsort-like in-place sort ---------- */

typedef int32_t (*kyo_it_cmp_t)(int32_t, int32_t);

static kyo_it_cmp_t g_kyo_it_cmp;

static int kyo_it_qsort_adaptor(const void *a, const void *b) {
    int32_t ai = *(const int32_t *)a;
    int32_t bi = *(const int32_t *)b;
    int32_t r  = g_kyo_it_cmp(ai, bi);
    /* qsort expects a plain int; kyo-ffi's Int maps to int32_t so we return
     * the value directly. */
    return (int)r;
}

void kyo_it_sort_ints(int32_t *arr, int32_t n, kyo_it_cmp_t cmp) {
    g_kyo_it_cmp = cmp;
    qsort(arr, (size_t)n, sizeof(int32_t), kyo_it_qsort_adaptor);
}

/* ---------- Retained callback: register + fire ---------- */

typedef void (*kyo_it_listener_t)(int32_t);

static kyo_it_listener_t g_kyo_it_listener;

void kyo_it_register_listener(kyo_it_listener_t cb) {
    g_kyo_it_listener = cb;
}

void kyo_it_fire_listener(int32_t arg) {
    if (g_kyo_it_listener != NULL) g_kyo_it_listener(arg);
}
