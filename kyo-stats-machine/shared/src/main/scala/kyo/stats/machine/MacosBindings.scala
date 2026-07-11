package kyo.stats.machine

import kyo.AllowUnsafe
import kyo.ffi.*

/** macOS syscall binding over the `machine_macos.c` projection shim.
  *
  * A real `statfs`/`vm_statistics64`/`host_cpu_load_info` carries nested and array fields the FFI struct
  * layer rejects, so the shim projects only the wanted scalar fields into flat primitive out-params.
  * Every method is the unsafe FFI tier: a trailing `(using AllowUnsafe)`, a bare-value return, and failure
  * surfaced by a non-zero return code, which the Machine impl maps to Absent.
  */
private[machine] trait MacosBindings extends Ffi:
    /** host_cpu_load_info projected to [user, system, idle, nice] cumulative nanoseconds. Returns 0 on ok. */
    def hostCpuLoad(out: Buffer[Long])(using AllowUnsafe): Int

    /** vm_statistics64 + sysctl projected to [total, free, available] bytes. Returns 0 on ok. */
    def vmStatistics(out: Buffer[Long])(using AllowUnsafe): Int

    /** swap usage projected to [total, free] bytes. Returns 0 on ok. */
    def swapUsage(out: Buffer[Long])(using AllowUnsafe): Int

    /** getloadavg into [one, five, fifteen]; returns the number of samples written (3 on ok). */
    def getloadavg(out: Buffer[Double], n: Int)(using AllowUnsafe): Int

    /** getmntinfo count of mounted filesystems (snapshot taken by the shim). */
    def mountCount()(using AllowUnsafe): Int

    /** The i-th mount's path, copied out of the getmntinfo snapshot. The C side returns a `const char*` into
      * the snapshot it owns, so the return is `Ffi.Borrowed[String]`: the codegen copies the NUL-terminated
      * bytes into an independently-owned Scala String the caller reads with `.value`.
      */
    def mountPath(i: Int)(using AllowUnsafe): Ffi.Borrowed[String]

    /** The i-th mount's fstype name, copied out of the getmntinfo snapshot. Borrowed `const char*` return,
      * unwrapped with `.value` at the call site (see `mountPath`).
      */
    def mountFstype(i: Int)(using AllowUnsafe): Ffi.Borrowed[String]

    /** statfs(path) projected to [total, free] bytes. Returns 0 on ok. */
    def statfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int
end MacosBindings

private[machine] object MacosBindings
    extends Ffi.Config(library = "machine_macos", symbolPrefix = "machine_macos_", nativeBundled = true)
