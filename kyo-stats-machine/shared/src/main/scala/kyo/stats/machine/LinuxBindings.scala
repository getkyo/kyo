package kyo.stats.machine

import kyo.AllowUnsafe
import kyo.ffi.*

/** Linux syscall binding: `statvfs` for per-mount free/total and `sysconf(_SC_CLK_TCK)` for the
  * jiffies-to-nanoseconds scale. A system-library binding (`library = "c"`, the PosixBindings shape) with
  * no bundled C. Every method is the unsafe FFI tier: a trailing `(using AllowUnsafe)`, a bare-value
  * return, and failure surfaced by throwing or a non-zero return, which the Machine impl catches to Absent.
  */
private[machine] trait LinuxBindings extends Ffi:
    /** statvfs(path, out): writes f_frsize, f_blocks, f_bavail (and more) into the out buffer; returns 0
      * on success. The out buffer is a flat Long scratch the C ABI fills; a non-zero return is an error.
      */
    def statvfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int

    /** sysconf(name): the configured value for a system limit (here _SC_CLK_TCK, the jiffy Hz). */
    def sysconf(name: Int)(using AllowUnsafe): Long
end LinuxBindings

private[machine] object LinuxBindings extends Ffi.Config(library = "c"):
    /** The `_SC_CLK_TCK` sysconf REQUEST constant, whose enum value is 2 on Linux glibc and musl. It names
      * WHICH system limit `sysconf` returns (the clock-tick Hz), not the Hz itself: the actual scale is
      * resolved at runtime by `MachineLinux.jiffiesFromBinding` via `sysconf(ScClkTck)`, which falls back to
      * the 100 Hz Linux default when that call is unavailable or returns a non-positive result. This enum
      * value is not portable to other C libraries, which is the reason for that runtime fallback.
      */
    val ScClkTck: Int = 2
end LinuxBindings
