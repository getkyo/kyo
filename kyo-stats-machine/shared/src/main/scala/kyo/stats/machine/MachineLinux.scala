package kyo.stats.machine

import kyo.*
import kyo.ffi.*

/** The Linux host reader: `/proc` and `/sys/fs/cgroup` files via the sampler-owned readScoped helper,
  * disk free/total via the statvfs binding. cgroup (v1 and v2) and PSI are Linux-only and read here.
  *
  * Every parse is total: a malformed, truncated, non-numeric, or wrong-order field routes to `Absent`
  * for that field, never a throw. cgroup files are read at the resolved process cgroup path from
  * `/proc/self/cgroup` (not the hierarchy root). System PSI (`/proc/pressure/{cpu,memory,io}`) and cgroup v2 PSI
  * (`*.pressure` under the resolved dir) are two distinct families.
  */
private[machine] object MachineLinux extends Machine:

    // Unsafe: the reader runs inside the sampler's tick and bridges the readScoped file reads and the
    // statvfs/sysconf FFI calls, all of which require the capability.
    import AllowUnsafe.embrace.danger

    def read(sampler: MachineSampler)(using AllowUnsafe): Machine.Reading =
        Machine.Reading(
            cpu = readCpu(sampler),
            memory = readMemory(sampler),
            swap = readSwap(sampler),
            disks = Chunk.empty,
            load = readLoad(sampler),
            cgroup = LinuxCgroup.read(sampler),
            pressure = LinuxPressure.readSystem(sampler),
            cgroupPressure = LinuxPressure.readCgroup(sampler)
        )

    override def readDisks(sampler: MachineSampler)(using AllowUnsafe): Chunk[Machine.DiskReading] =
        readDisksImpl(sampler)

    private def readCpu(s: MachineSampler)(using AllowUnsafe): Maybe[Machine.CpuReading] =
        s.readScoped(Path("/proc/stat"), (b, n) => LinuxDecoders.cpu(b, n, jiffiesToNanos)).flatten

    private def readMemory(s: MachineSampler)(using AllowUnsafe): Maybe[Machine.MemoryReading] =
        s.readScoped(Path("/proc/meminfo"), (b, n) => LinuxDecoders.memory(b, n)).flatten

    private def readSwap(s: MachineSampler)(using AllowUnsafe): Maybe[Machine.SwapReading] =
        s.readScoped(Path("/proc/meminfo"), (b, n) => LinuxDecoders.swap(b, n)).flatten

    private def readLoad(s: MachineSampler)(using AllowUnsafe): Maybe[Machine.LoadReading] =
        s.readScoped(Path("/proc/loadavg"), (b, n) => LinuxDecoders.load(b, n)).flatten

    private def readDisksImpl(s: MachineSampler)(using AllowUnsafe): Chunk[Machine.DiskReading] =
        LinuxDisk.enumerate(s).map(m => LinuxDisk.stat(m, statvfs))

    /** ns-per-jiffy scale from sysconf(_SC_CLK_TCK); resolved once, Absent-safe on a binding-load failure. */
    private lazy val jiffiesToNanos: Long =
        try jiffiesFromBinding(Ffi.load[LinuxBindings])
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => defaultJiffiesToNanos

    private def statvfs(mount: String)(using AllowUnsafe): Maybe[(Long, Long)] =
        try statvfsWith(Ffi.load[LinuxBindings], mount)
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => Absent

    /** The jiffies-to-ns fallback used when sysconf is unavailable or non-positive (100 Hz, the Linux
      * default): `1e9 / 100`.
      */
    private[machine] val defaultJiffiesToNanos: Long = 10000000L

    /** The jiffies-to-ns computation from a binding, with the same throw-to-fallback bridge the production
      * lazy val uses. Package-private so a test drives it with a throwing binding and exercises the real
      * catch, rather than re-implementing the catch inline.
      */
    private[machine] def jiffiesFromBinding(bindings: LinuxBindings)(using AllowUnsafe): Long =
        try
            val hz = bindings.sysconf(LinuxBindings.ScClkTck)
            if hz > 0 then 1000000000L / hz else defaultJiffiesToNanos
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => defaultJiffiesToNanos

    /** The statvfs read from a binding, with the same throw-to-Absent bridge the production `statvfs` uses.
      * Package-private so a test drives it with a throwing binding and exercises the real catch.
      */
    private[machine] def statvfsWith(bindings: LinuxBindings, mount: String)(using AllowUnsafe): Maybe[(Long, Long)] =
        try LinuxDisk.statvfsRaw(bindings, mount)
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => Absent

end MachineLinux
