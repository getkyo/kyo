#include <stdint.h>

/* Bundled buffer surface.
 *
 * Round-trip patterns through `Buffer[A]`:
 *   - sum_ints: read-only consumer, sums an int32 buffer and returns int64.
 *   - fill_ints: write-only producer, fills an int32 buffer with a value.
 *   - copy_ints: buffer-to-buffer copy, exercises two Buffer[Int] params.
 *
 * All buffers are int32 arrays passed as `int32_t*` + explicit length. This
 * is the idiomatic C pattern for unowned views and matches how kyo-ffi
 * emits `Buffer[Int]` parameters on every target.
 */

int64_t kyo_it_sum_ints(const int32_t *buf, int64_t n) {
    int64_t total = 0;
    for (int64_t i = 0; i < n; i++) total += (int64_t)buf[i];
    return total;
}

void kyo_it_fill_ints(int32_t *buf, int64_t n, int32_t value) {
    for (int64_t i = 0; i < n; i++) buf[i] = value;
}

void kyo_it_copy_ints(int32_t *dst, const int32_t *src, int64_t n) {
    for (int64_t i = 0; i < n; i++) dst[i] = src[i];
}
