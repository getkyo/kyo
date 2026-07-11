package kyo.stats.machine

import kyo.*

/** System PSI (`/proc/pressure/{cpu,memory,io}`) and cgroup v2 PSI (the resolved cgroup dir's
  * `{cpu,memory,io}.pressure`) parsed into two DISTINCT PressureReadings. cpu `full` is parsed but not
  * emitted (present-but-pinned-zero); `total=` microseconds scale x1000 to ns. Absent on any missing file.
  */
private[machine] object LinuxPressure:

    def readSystem(s: MachineSampler)(using AllowUnsafe): Maybe[Machine.PressureReading] =
        readFamily(s, "/proc/pressure/cpu", "/proc/pressure/memory", "/proc/pressure/io")

    def readCgroup(s: MachineSampler)(using AllowUnsafe): Maybe[Machine.PressureReading] =
        if LinuxCgroup.hasV2Pressure(s) then
            val dir = LinuxCgroup.resolvedV2Dir(s)
            readFamily(s, dir + "/cpu.pressure", dir + "/memory.pressure", dir + "/io.pressure")
        else Absent

    private def readFamily(s: MachineSampler, cpu: String, mem: String, io: String)(using
        AllowUnsafe
    ): Maybe[Machine.PressureReading] =
        val cpuR = psi(s, cpu); val memR = psi(s, mem); val ioR = psi(s, io)
        if cpuR.isEmpty && memR.isEmpty && ioR.isEmpty then Absent
        else
            Present(Machine.PressureReading(
                cpuSome = cpuR.map(_._1).getOrElse(Machine.PsiReading.empty),
                cpuFull = cpuR.map(_._2).getOrElse(Machine.PsiReading.empty),
                memorySome = memR.map(_._1).getOrElse(Machine.PsiReading.empty),
                memoryFull = memR.map(_._2).getOrElse(Machine.PsiReading.empty),
                ioSome = ioR.map(_._1).getOrElse(Machine.PsiReading.empty),
                ioFull = ioR.map(_._2).getOrElse(Machine.PsiReading.empty)
            ))
        end if
    end readFamily

    private def psi(s: MachineSampler, path: String)(using
        AllowUnsafe
    ): Maybe[(Machine.PsiReading, Machine.PsiReading)] =
        s.readScoped(Path(path), (b, n) => LinuxPressureDecode.parse(b, n)).flatten

end LinuxPressure
