package kyo.stats.machine

import kyo.*
import kyo.ffi.*

/** The macOS host reader: cpu-time via mach `host_statistics` (host_cpu_load_info), memory/swap via
  * `sysctl` + mach `vm_statistics64`, load via `getloadavg`, per-mount disk via `getmntinfo`+`statfs`.
  * All syscalls go through `MacosBindings` over a small projection C shim that flattens the nested/array
  * structs into flat primitive out-params. cgroup and PSI are Linux-only and always Absent here.
  */
private[machine] object MachineMacos extends Machine:

    // Unsafe: the reader runs inside the sampler's tick and bridges the sysctl/mach/getmntinfo FFI
    // calls, which require the capability.
    import AllowUnsafe.embrace.danger

    def read(sampler: MachineSampler)(using AllowUnsafe): Machine.Reading =
        bindings match
            case Present(b) =>
                Machine.Reading(
                    cpu = readCpu(b),
                    memory = readMemory(b),
                    swap = readSwap(b),
                    disks = readDisks(b),
                    load = readLoad(b),
                    cgroup = Absent,
                    pressure = Absent,
                    cgroupPressure = Absent
                )
            case Absent => Machine.Reading.empty

    /** The binding, loaded once; a load failure (e.g. browser-JS with no koffi) degrades to Absent. */
    private lazy val bindings: Maybe[MacosBindings] =
        try Present(Ffi.load[MacosBindings])
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => Absent

    private def readCpu(b: MacosBindings)(using AllowUnsafe): Maybe[Machine.CpuReading] =
        val out = Buffer.alloc[Long](4)
        try
            if b.hostCpuLoad(out) != 0 then Absent
            else
                val user  = out.get(0); val system = out.get(1); val idle = out.get(2); val nice = out.get(3)
                val total = user + system + idle + nice
                Present(Machine.CpuReading(Present(total), Present(user), Present(system), Present(idle), Absent))
            end if
        finally out.close()
        end try
    end readCpu

    private def readMemory(b: MacosBindings)(using AllowUnsafe): Maybe[Machine.MemoryReading] =
        val out = Buffer.alloc[Long](3)
        try
            if b.vmStatistics(out) != 0 then Absent
            else
                val total = out.get(0); val free = out.get(1); val available = out.get(2)
                Present(Machine.MemoryReading(Present(total), Present(available), Present(free)))
            end if
        finally out.close()
        end try
    end readMemory

    private def readSwap(b: MacosBindings)(using AllowUnsafe): Maybe[Machine.SwapReading] =
        val out = Buffer.alloc[Long](2)
        try
            if b.swapUsage(out) != 0 then Absent
            else Present(Machine.SwapReading(Present(out.get(0)), Present(out.get(1))))
        finally out.close()
        end try
    end readSwap

    private def readLoad(b: MacosBindings)(using AllowUnsafe): Maybe[Machine.LoadReading] =
        val out = Buffer.alloc[Double](3)
        try
            if b.getloadavg(out, 3) != 3 then Absent
            else Present(Machine.LoadReading(Present(out.get(0)), Present(out.get(1)), Present(out.get(2))))
        finally out.close()
        end try
    end readLoad

    private def readDisks(b: MacosBindings)(using AllowUnsafe): Chunk[Machine.DiskReading] =
        MacosDisk.enumerate(b).map(m => MacosDisk.stat(b, m))

end MachineMacos
