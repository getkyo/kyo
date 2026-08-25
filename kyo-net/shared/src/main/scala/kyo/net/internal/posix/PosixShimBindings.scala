package kyo.net.internal.posix

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.ffi.Ffi

/** POSIX utility shims that wrap variadic C functions with non-variadic C shim wrappers.
  *
  * `fcntl(int fd, int cmd, ...)` is VARIADIC in C. Binding it non-variadically in the Scala FFI tier silently breaks on arm64 (Apple Silicon
  * and Linux aarch64) because the AAPCS64 calling convention passes variadic and fixed arguments through different code paths. A non-variadic
  * binding routes the third argument through the wrong register, so `F_SETFL` with `O_NONBLOCK` silently drops the flag and leaves the socket
  * blocking.
  *
  * The shims in `kyo_posix.c` call `fcntl` correctly at the C level (the C compiler knows the function is variadic from `<fcntl.h>`) and
  * expose a non-variadic interface the Scala bindings can call without ABI ambiguity on any architecture.
  *
  * Every method is part of the unsafe FFI tier and takes a trailing `(using AllowUnsafe)` clause: each native call is a side effect tracked by
  * the caller.
  */
private[net] trait PosixShimBindings extends Ffi:

    /** Set `O_NONBLOCK` on `fd` using `fcntl(fd, F_GETFL, 0)` + `fcntl(fd, F_SETFL, flags | O_NONBLOCK)`. Both calls are issued by the C
      * shim, which compiles them as correct variadic calls on every architecture. Returns 0 on success, -1 on failure.
      */
    def kyo_posix_set_nonblocking(fd: Int)(using AllowUnsafe): Int

    /** Read the current file-status flags for `fd` via `fcntl(fd, F_GETFL, 0)`. The shim wraps the variadic call correctly. Returns the flag
      * bitmask (>= 0) on success, -1 on failure.
      */
    def kyo_posix_get_flags(fd: Int)(using AllowUnsafe): Int

    /** `int open(const char* path, int flags)` via the C shim. `open(2)` is variadic in C, so the shim wraps it for correct argument passing on
      * every architecture (the same arm64 ABI hazard `fcntl` has). Pass `flags` from [[PosixConstants]] (`O_RDONLY` etc.); no mode is supplied, so
      * the path must already exist. Returns the raw POSIX fd (>= 0) on success or -1 on failure. Used by the posix-test helpers to obtain a real
      * regular-file fd for the driver-selection routing checks without any reflection.
      */
    def kyo_posix_open(path: String, flags: Int)(using AllowUnsafe): Int

    /** `int close(int fd)` via the C shim. Closes a raw fd opened with [[kyo_posix_open]]. Returns 0 on success or -1 on failure. */
    def kyo_posix_close(fd: Int)(using AllowUnsafe): Int

end PosixShimBindings

private[net] object PosixShimBindings extends Ffi.Config(
        library = "kyonet_posix_uring",
        headers = Chunk("fcntl.h"),
        nativeBundled = true
    )
