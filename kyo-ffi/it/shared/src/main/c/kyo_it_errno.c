#include <errno.h>
#include <stdint.h>

/* Bundled errno surface.
 *
 * Provides a function that unconditionally sets errno = EINVAL (22) and
 * returns -1. The binding declares `Ffi.Outcome[Int]` so the generated impl
 * captures errno into the returned `Ffi.Outcome[Int]` via Panama's
 * `captureCallState("errno")` on JVM, Scala Native's `errno` binding on
 * Native, and koffi's errno capture on JS. After the call the spec
 * asserts both the return value and the captured error code.
 *
 * EINVAL is 22 on Linux and macOS (and Windows, though Windows is not
 * currently targeted). We reference the POSIX symbolic constant rather
 * than a numeric literal to avoid platform drift; the spec hard-codes 22
 * to catch any host where this assumption breaks.
 */
int32_t kyo_it_always_fail(void) {
    errno = EINVAL;
    return -1;
}

/* Cross-platform errno-timing validation.
 *
 * Unconditionally clears errno to 0 and returns 1. Declared as a plain
 * `Int` return, since errno = 0, the auto-throw contract (FfiErrno on
 * non-zero errno) is satisfied and no exception is thrown.
 */
int32_t kyo_it_clear_errno(void) {
    errno = 0;
    return 1;
}
