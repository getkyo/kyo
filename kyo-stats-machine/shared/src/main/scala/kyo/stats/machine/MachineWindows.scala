package kyo.stats.machine

import kyo.*
import kyo.ffi.*

/** The Windows host reader: cpu-time via `GetSystemTimes`, memory/swap via `GlobalMemoryStatusEx`,
  * per-drive disk via `GetLogicalDrives`+`GetDiskFreeSpaceEx`, core count via the shared gauge. Win32 is
  * bound directly against `kernel32` (no bundled C). Load average and cgroup/PSI do not exist on Windows
  * and are always Absent. The behavioral read is validated on a Windows host, not this Linux-only CI.
  */
private[machine] object MachineWindows extends Machine:

    // Unsafe: the reader runs inside the sampler's tick and bridges the Ffi.load initializer and the
    // kernel32 syscall reads, all of which require the capability.
    import AllowUnsafe.embrace.danger

    def read(sampler: MachineSampler)(using AllowUnsafe): Machine.Reading =
        bindings match
            case Present(b) =>
                // A library that resolves at Ffi.load time can still fail its first real symbol lookup
                // lazily (the generated impl's static initializer runs on first call, wrapping a missing
                // Win32 export in ExceptionInInitializerError, a LinkageError NonFatal excludes): this is
                // the Machine-impl degradation boundary, so any such failure degrades to empty here too.
                try
                    Machine.Reading(
                        cpu = readCpu(b),
                        memory = readMemory(b),
                        swap = readSwap(b),
                        disks = Chunk.empty,
                        load = Absent,
                        cgroup = Absent,
                        pressure = Absent,
                        cgroupPressure = Absent
                    )
                catch
                    case ex: Throwable if scala.util.control.NonFatal(ex) || ex.isInstanceOf[LinkageError] =>
                        Machine.Reading.empty
            case Absent => Machine.Reading.empty

    override def readDisks(sampler: MachineSampler)(using AllowUnsafe): Chunk[Machine.DiskReading] =
        bindings match
            case Present(b) =>
                try readDisksImpl(b)
                catch
                    case ex: Throwable if scala.util.control.NonFatal(ex) || ex.isInstanceOf[LinkageError] =>
                        Chunk.empty
            case Absent => Chunk.empty

    private lazy val bindings: Maybe[WindowsBindings] =
        try Present(Ffi.load[WindowsBindings])
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => Absent

    private[machine] def readCpu(b: WindowsBindings)(using AllowUnsafe): Maybe[Machine.CpuReading] =
        // GetSystemTimes takes three LPFILETIME out-params; each FILETIME is one little-endian 100ns int64.
        val idleB   = Buffer.alloc[Long](1)
        val kernelB = Buffer.alloc[Long](1)
        val userB   = Buffer.alloc[Long](1)
        try
            if b.getSystemTimes(idleB, kernelB, userB) == 0 then Absent
            else
                // FILETIME is in 100ns units; scale to ns (x100). Kernel time INCLUDES idle on Windows.
                val idle   = idleB.get(0) * 100L; val kernel = kernelB.get(0) * 100L; val user = userB.get(0) * 100L
                val system = kernel - idle
                val total  = kernel + user
                Present(Machine.CpuReading(Present(total), Present(user), Present(system), Present(idle), Absent))
            end if
        finally
            idleB.close(); kernelB.close(); userB.close()
        end try
    end readCpu

    private[machine] def readMemory(b: WindowsBindings)(using AllowUnsafe): Maybe[Machine.MemoryReading] =
        WindowsBindings.withMemoryStatus(b) match
            case Present(out) =>
                try
                    // GlobalMemoryStatusEx exposes ullTotalPhys and ullAvailPhys only; Windows has no distinct
                    // free-vs-available concept, so free is Absent rather than reporting available's value under
                    // a different label, a fabricated-signal shape the reader must not produce even for a real number.
                    Present(Machine.MemoryReading(Present(out.get(1)), Present(out.get(2)), Absent))
                finally out.close()
            case Absent => Absent

    private[machine] def readSwap(b: WindowsBindings)(using AllowUnsafe): Maybe[Machine.SwapReading] =
        WindowsBindings.withMemoryStatus(b) match
            case Present(out) =>
                // ullTotalPageFile/ullAvailPageFile are the commit limit (physical RAM plus page-file size), not
                // a page-file-only figure the way Linux SwapTotal/SwapFree are; Windows exposes no narrower swap
                // concept, so the commit limit is the closest honest mapping and is reported as-is.
                try Present(Machine.SwapReading(Present(out.get(3)), Present(out.get(4))))
                finally out.close()
            case Absent => Absent

    private def readDisksImpl(b: WindowsBindings)(using AllowUnsafe): Chunk[Machine.DiskReading] =
        WindowsDisk.enumerate(b).map(d => WindowsDisk.stat(b, d))

end MachineWindows
