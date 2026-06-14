/* qsort-demo: sort an int array in-place using a Scala-provided comparator.
 * The callback is invoked during this call and not retained, classic
 * "transient callback" pattern. */
#include <stdlib.h>

typedef int (*cbe_cmp_t)(int, int);

static cbe_cmp_t g_cmp;

static int cbe_wrapper(const void *a, const void *b) {
    int ai = *(const int *)a;
    int bi = *(const int *)b;
    return g_cmp(ai, bi);
}

void cbe_qsort_demo(int *arr, int len, cbe_cmp_t cmp) {
    g_cmp = cmp;
    qsort(arr, (size_t)len, sizeof(int), cbe_wrapper);
}

/* Sum callbacks' results, simple pure-callback sanity. */
int cbe_sum_pairs(int (*cb)(int, int), int n) {
    int total = 0;
    for (int i = 0; i < n; i++) total += cb(i, i * 2);
    return total;
}

/* Retained-callback pair: `register` stashes the callback; `fire` invokes it
 * outside the original registration call. Mirrors the typical event-handler
 * retention pattern, the callback must live past the register call, pinned
 * by the Ffi.Guard that owns its upcall arena on JVM / zone on Native / koffi
 * registration on JS.
 */
typedef void (*cbe_handler_t)(int);

static cbe_handler_t g_handler;

void cbe_register_handler(cbe_handler_t cb) {
    g_handler = cb;
}

void cbe_fire_handler(int arg) {
    if (g_handler != NULL) g_handler(arg);
}
