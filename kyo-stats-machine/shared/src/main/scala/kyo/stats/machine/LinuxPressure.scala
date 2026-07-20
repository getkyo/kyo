package kyo.stats.machine

import kyo.*

/** System PSI (`/proc/pressure/{cpu,memory,io}`) and cgroup v2 PSI (the resolved cgroup directory's
  * `{cpu,memory,io}.pressure`), read into two DISTINCT families so system-wide and per-cgroup pressure
  * never mix.
  *
  * Each file carries a `some` line and a `full` line; `cpu.full` is parsed by the kernel but is not
  * emitted, so the cpu decoders carry no full cells and no cpu.full series is ever registered. The `total=`
  * field is microseconds and is scaled to nanoseconds. A host without PSI has no such files, so the slots
  * stay absent, nothing is written, and no PSI series is registered.
  */
final private[machine] class LinuxPressure(h: MachineHandles, s: MachineSampler, cgroup: LinuxCgroup)(using AllowUnsafe):

    import LinuxPressure.*

    private val sysCpu = s.openSlot(Path("/proc/pressure/cpu"))
    private val sysMem = s.openSlot(Path("/proc/pressure/memory"))
    private val sysIo  = s.openSlot(Path("/proc/pressure/io"))

    private val cgCpu = if cgroup.hasV2Pressure then s.openSlot(Path(cgroup.v2Dir + "/cpu.pressure")) else Absent
    private val cgMem = if cgroup.hasV2Pressure then s.openSlot(Path(cgroup.v2Dir + "/memory.pressure")) else Absent
    private val cgIo  = if cgroup.hasV2Pressure then s.openSlot(Path(cgroup.v2Dir + "/io.pressure")) else Absent

    private val decodeSysCpu = new PsiDecode(h.systemPressure.cpuSome, Absent)
    private val decodeSysMem = new PsiDecode(h.systemPressure.memorySome, Present(h.systemPressure.memoryFull))
    private val decodeSysIo  = new PsiDecode(h.systemPressure.ioSome, Present(h.systemPressure.ioFull))
    private val decodeCgCpu  = new PsiDecode(h.cgroupPressure.cpuSome, Absent)
    private val decodeCgMem  = new PsiDecode(h.cgroupPressure.memorySome, Present(h.cgroupPressure.memoryFull))
    private val decodeCgIo   = new PsiDecode(h.cgroupPressure.ioSome, Present(h.cgroupPressure.ioFull))

    def read()(using AllowUnsafe): Unit =
        discard(s.readInto(sysCpu, decodeSysCpu))
        discard(s.readInto(sysMem, decodeSysMem))
        discard(s.readInto(sysIo, decodeSysIo))
        discard(s.readInto(cgCpu, decodeCgCpu))
        discard(s.readInto(cgMem, decodeCgMem))
        discard(s.readInto(cgIo, decodeCgIo))
    end read

end LinuxPressure

private[machine] object LinuxPressure:

    private[machine] val SomeLine = LinuxScan.ascii("some ")
    private[machine] val FullLine = LinuxScan.ascii("full ")
    private[machine] val Avg10    = LinuxScan.ascii("avg10=")
    private[machine] val Avg60    = LinuxScan.ascii("avg60=")
    private[machine] val Avg300   = LinuxScan.ascii("avg300=")
    private[machine] val TotalTag = LinuxScan.ascii("total=")

    /** A retained decoder for one PSI file: it writes the three pre-averaged percentages into their gauge
      * cells and the cumulative stall into the per-second rate cell, for the `some` row and, where the row
      * is emitted, the `full` row. Built once per file at reader construction.
      */
    final private[machine] class PsiDecode(some: PsiHandles.Pair, full: Maybe[PsiHandles.Pair])
        extends MachineSampler.Decode:
        def apply(b: Span[Byte], n: Int)(using AllowUnsafe): Unit =
            observeLine(b, n, SomeLine, some)
            full match
                case Present(cells) => observeLine(b, n, FullLine, cells)
                case Absent         => ()
        end apply
    end PsiDecode

    private[machine] def observeLine(
        b: Span[Byte],
        n: Int,
        prefix: Array[Byte],
        cells: PsiHandles.Pair
    )(using AllowUnsafe): Unit =
        val from = LinuxScan.lineFields(b, n, prefix)
        if from >= 0 then
            cells.avg10.set(LinuxScan.taggedDouble(b, n, from, Avg10))
            cells.avg60.set(LinuxScan.taggedDouble(b, n, from, Avg60))
            cells.avg300.set(LinuxScan.taggedDouble(b, n, from, Avg300))
            cells.rate.observe(LinuxScan.taggedLong(b, n, from, TotalTag, 1000L))
        end if
    end observeLine

end LinuxPressure
