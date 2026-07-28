/*
 * kyo_posix.c: POSIX utility shims for kyo-net.
 *
 * fcntl(int fd, int cmd, ...) is a VARIADIC C function. Binding it non-variadically
 * in Scala/Java FFI silently breaks on arm64 (Apple Silicon and Linux aarch64) because
 * the AAPCS64 calling convention uses separate code paths for fixed and variadic
 * arguments. A non-variadic binding passes the third argument in the wrong register,
 * so F_SETFL with O_NONBLOCK silently drops the flag and the socket stays blocking.
 *
 * This C shim solves it: the C compiler knows fcntl is variadic (from <fcntl.h>) and
 * emits the correct variadic call-site code on every architecture. The Scala binding
 * sees only a non-variadic single-argument function with no ABI ambiguity.
 *
 * Guarded on POSIX: fcntl.h is part of POSIX and present on Linux, macOS, and BSD.
 * The guard keeps the translation unit empty on non-POSIX hosts (e.g. Windows) so
 * the build does not fail where fcntl.h is absent.
 */

#if !defined(_WIN32)

#include <fcntl.h>
#include <unistd.h>

/*
 * kyo_posix_set_nonblocking: read the current file-status flags with F_GETFL, then
 * write them back with O_NONBLOCK added via F_SETFL. Returns 0 on success, -1 on
 * failure. Uses two correct variadic calls to fcntl so no argument is passed in the
 * wrong register on any architecture.
 */
int kyo_posix_set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return -1;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

/*
 * kyo_posix_get_flags: read the current file-status flags with F_GETFL. Returns the
 * flags on success (>= 0), -1 on failure. Wraps the variadic fcntl call correctly so
 * the result is reliable on arm64 as well as x86_64.
 */
int kyo_posix_get_flags(int fd) {
    return fcntl(fd, F_GETFL, 0);
}

/*
 * kyo_posix_open: open(path, flags) and return the raw POSIX fd (>= 0) or -1 on
 * failure. open(2) is VARIADIC in C (int open(const char*, int, ...)), so binding it
 * directly in the Scala FFI tier carries the same arm64 ABI hazard fcntl does: the
 * AAPCS64 convention routes the variadic mode argument through a different path than a
 * non-variadic binding would. The mode argument matters only when O_CREAT is set; this
 * shim passes no mode (it opens existing files only) and lets the C compiler emit the
 * correct variadic call site from <fcntl.h> on every architecture. The flag constants
 * (O_RDONLY and friends) are passed in by the caller so the OS-specific numeric values
 * stay in PosixConstants rather than being baked into the shim.
 */
int kyo_posix_open(const char* path, int flags) {
    return open(path, flags);
}

/*
 * kyo_posix_close: close(fd) and return 0 on success or -1 on failure. close(2) is not
 * variadic, but this pairs with kyo_posix_open so a caller that opened a raw fd through
 * the shim closes it through the matching shim symbol without reaching for a separate
 * binding.
 */
int kyo_posix_close(int fd) {
    return close(fd);
}

#else

/*
 * Non-POSIX (Windows): empty translation unit. ISO C forbids a file with no external
 * declarations, so emit one harmless typedef to keep the compiler quiet.
 */
typedef int kyo_posix_unavailable_t;

#endif
