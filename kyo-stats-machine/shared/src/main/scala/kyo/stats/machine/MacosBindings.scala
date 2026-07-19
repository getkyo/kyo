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

    /** Enumerates the mounted filesystems into the CALLER's buffer as NUL-separated
      * `<mount-point>\0<fstype>\0` pairs, and returns the mount count (or -1 when the buffer is too small).
      * The buffer belongs to the caller, which is what keeps the call re-entrant: getmntinfo returns a
      * pointer into libc-owned static memory that every caller in the process shares.
      */
    def mounts(out: Buffer[Byte], cap: Int)(using AllowUnsafe): Int

    /** statfs(path) projected to [total, free] bytes. Returns 0 on ok. */
    def statfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int
end MacosBindings

private[machine] object MacosBindings
    extends Ffi.Config(library = "machine_macos", symbolPrefix = "machine_macos_", nativeBundled = true)
