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

    import AllowUnsafe.embrace.danger

    def read(sampler: MachineSampler)(using AllowUnsafe): Machine.Reading =
        Machine.Reading(
            cpu = readCpu(sampler),
            memory = readMemory(sampler),
            swap = readSwap(sampler),
            disks = readDisks(sampler),
            load = readLoad(sampler),
            cgroup = LinuxCgroup.read(sampler),
            pressure = LinuxPressure.readSystem(sampler),
            cgroupPressure = LinuxPressure.readCgroup(sampler)
        )

    private def readCpu(s: MachineSampler)(using AllowUnsafe): Maybe[Machine.CpuReading] =
        s.readScoped(Path("/proc/stat"), (b, n) => LinuxDecoders.cpu(b, n, jiffiesToNanos)).flatten

    private def readMemory(s: MachineSampler)(using AllowUnsafe): Maybe[Machine.MemoryReading] =
        s.readScoped(Path("/proc/meminfo"), (b, n) => LinuxDecoders.memory(b, n)).flatten

    private def readSwap(s: MachineSampler)(using AllowUnsafe): Maybe[Machine.SwapReading] =
        s.readScoped(Path("/proc/meminfo"), (b, n) => LinuxDecoders.swap(b, n)).flatten

    private def readLoad(s: MachineSampler)(using AllowUnsafe): Maybe[Machine.LoadReading] =
        s.readScoped(Path("/proc/loadavg"), (b, n) => LinuxDecoders.load(b, n)).flatten

    private def readDisks(s: MachineSampler)(using AllowUnsafe): Chunk[Machine.DiskReading] =
        LinuxDisk.enumerate(s).map(m => LinuxDisk.stat(m, statvfs))

    /** ns-per-jiffy scale from sysconf(_SC_CLK_TCK); resolved once, Absent-safe on a binding-load failure. */
    private lazy val jiffiesToNanos: Long =
        try
            val hz = Ffi.load[LinuxBindings].sysconf(LinuxBindings.ScClkTck)
            if hz > 0 then 1000000000L / hz else 10000000L
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => 10000000L

    private def statvfs(mount: String)(using AllowUnsafe): Maybe[(Long, Long)] =
        try LinuxDisk.statvfsRaw(Ffi.load[LinuxBindings], mount)
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => Absent

end MachineLinux
