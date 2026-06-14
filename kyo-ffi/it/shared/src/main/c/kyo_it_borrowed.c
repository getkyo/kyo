#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* IT fixture for the `Borrowed[Buffer[A]]` return path.
 *
 * `kyo_it_malloc_chunk` allocates a fresh `n`-byte region, fills every byte
 * with 0xAB, and returns a pointer the caller is expected to treat as
 * BORROWED (C-owned). The allocation is intentionally leaked, the test
 * only verifies borrowed-Buffer semantics (wrap, size, byte read) and
 * does not exercise any free path. Leaking keeps the contract simple and
 * avoids cross-platform pitfalls with cleanup hooks; the test process
 * exits promptly after the assertion so the leaked chunk has no observable
 * cost.
 *
 * Returning NULL on n <= 0 makes the "borrowed NULL-through" path explicit
 * for the borrowed String counterpart; the Buffer test covers the
 * non-null case with n = 16.
 */
void *kyo_it_malloc_chunk(int64_t n) {
    if (n <= 0) return 0;
    void *p = malloc((size_t)n);
    if (p == 0) return 0;
    memset(p, 0xAB, (size_t)n);
    return p;
}

/* --- Borrowed String fixtures ------------------------------------------- */

/* Returns a static non-empty ASCII string. The pointer is to read-only
 * static storage, the caller treats it as borrowed (must not free). */
static const char kyo_it_hello[] = "hello from C";

const char *kyo_it_borrowed_string(void) {
    return kyo_it_hello;
}

/* Returns NULL, exercises the null-through path for borrowed strings. */
const char *kyo_it_borrowed_string_null(void) {
    return 0;
}

/* Returns a pointer to an empty NUL-terminated string. */
static const char kyo_it_empty[] = "";

const char *kyo_it_borrowed_string_empty(void) {
    return kyo_it_empty;
}

/* Returns a UTF-8 multi-byte string. Contains:
 *   U+00E9 (é)    , 2-byte sequence (0xC3 0xA9)
 *   U+4E16 (世)   , 3-byte sequence (0xE4 0xB8 0x96)
 *   U+1F600 (😀)  , 4-byte sequence (0xF0 0x9F 0x98 0x80)
 */
static const char kyo_it_utf8[] = "\xC3\xA9\xE4\xB8\x96\xF0\x9F\x98\x80";

const char *kyo_it_borrowed_string_utf8(void) {
    return kyo_it_utf8;
}
