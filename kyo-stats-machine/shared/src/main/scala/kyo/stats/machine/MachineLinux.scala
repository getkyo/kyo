package kyo.stats.machine

import kyo.*
import kyo.ffi.*

/** The Linux host reader: the proc and cgroup files through the sampler's retained read handles, disk free
  * and total through the statvfs binding. cgroup (v1 and v2) and PSI are Linux-only and are read here.
  *
  * Everything a tick touches is retained and built once: one read handle per file, one decode callback per
  * file, and the cgroup layout, which is resolved a single time at construction. Every parse is total: a
  * malformed, truncated, non-numeric or missing field decodes to the primitive absent sentinel, which its
  * cell skips, so it is never recorded and never registered.
  */
final private[machine] class MachineLinux(h: MachineHandles, s: MachineSampler)(using AllowUnsafe) extends Machine:

    private val statSlot = s.openSlot(Path("/proc/stat"))
    private val memSlot  = s.openSlot(Path("/proc/meminfo"))
    private val loadSlot = s.openSlot(Path("/proc/loadavg"))

    private val decodeCpu: MachineSampler.Decode = new MachineSampler.Decode:
        def apply(b: Span[Byte], n: Int)(using AllowUnsafe): Unit = LinuxDecoders.cpu(b, n, jiffiesToNanos, h)

    private val decodeMeminfo: MachineSampler.Decode = new MachineSampler.Decode:
        def apply(b: Span[Byte], n: Int)(using AllowUnsafe): Unit = LinuxDecoders.meminfo(b, n, h)

    private val decodeLoad: MachineSampler.Decode = new MachineSampler.Decode:
        def apply(b: Span[Byte], n: Int)(using AllowUnsafe): Unit = LinuxDecoders.load(b, n, h)

    private val cgroup   = new LinuxCgroup(h, s)
    private val pressure = new LinuxPressure(h, s, cgroup)
    private val disk     = new LinuxDisk(h, s)

    def read()(using AllowUnsafe): Unit =
        discard(s.readInto(statSlot, decodeCpu))
        discard(s.readInto(memSlot, decodeMeminfo))
        discard(s.readInto(loadSlot, decodeLoad))
        cgroup.read()
        pressure.read()
    end read

    def readDisks()(using AllowUnsafe): Unit = disk.read(bindings)

    def close()(using AllowUnsafe): Unit = disk.close()

    /** The binding, loaded once and cached, the same shape the macOS and Windows readers use. A load failure
      * (no libc binding available on this platform) degrades every reading that needs it to absent.
      */
    private lazy val bindings: Maybe[LinuxBindings] =
        try Present(Ffi.load[LinuxBindings])
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => Absent

    /** The nanoseconds-per-jiffy scale from sysconf(_SC_CLK_TCK), resolved once. Falls back to the 100 Hz
      * Linux default when sysconf is unavailable or returns a non-positive value.
      */
    private lazy val jiffiesToNanos: Long =
        bindings match
            case Present(b) => MachineLinux.jiffiesFromBinding(b)
            case Absent     => MachineLinux.defaultJiffiesToNanos

end MachineLinux

private[machine] object MachineLinux:

    /** The jiffies-to-nanoseconds fallback used when sysconf is unavailable or non-positive (100 Hz, the
      * Linux default).
      */
    private[machine] val defaultJiffiesToNanos: Long = 10000000L

    /** The jiffies-to-nanoseconds computation from a binding, with the same throw-to-fallback bridge the
      * production path uses. Package-private so a test drives it with a throwing binding and exercises the
      * real catch rather than re-implementing it.
      */
    private[machine] def jiffiesFromBinding(bindings: LinuxBindings)(using AllowUnsafe): Long =
        try
            val hz = bindings.sysconf(LinuxBindings.ScClkTck)
            if hz > 0 then 1000000000L / hz else defaultJiffiesToNanos
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => defaultJiffiesToNanos

end MachineLinux
