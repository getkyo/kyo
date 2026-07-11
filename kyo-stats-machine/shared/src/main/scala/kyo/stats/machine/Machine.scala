package kyo.stats.machine

import kyo.*

/** Host-metrics reader abstraction with one implementation per operating system.
  *
  * A `Machine` reads the host once per sampler tick and returns a `Machine.Reading`: a flat set of
  * `Maybe`-typed metric values where an unavailable metric is `Absent` (never a fake zero and never a
  * throw). The single implementation for the running OS is selected once, at sampler init, from
  * `System.operatingSystem`. Every implementation compiles on every platform because it composes only
  * cross-platform kyo primitives (`kyo.Path` for files, its per-OS FFI binding for syscalls, `kyo.Stat`).
  */
private[kyo] trait Machine:
    def read(sampler: MachineSampler)(using AllowUnsafe): Machine.Reading

private[kyo] object Machine:

    /** Selects the implementation for the current OS once. An OS with no dedicated reader degrades to
      * an all-`Absent` reading (graceful degradation, never a throw).
      */
    def forOs(os: System.OS): Machine =
        os match
            case System.OS.Linux   => MachineLinux
            case System.OS.MacOS   => MachineMacos
            case System.OS.Windows => MachineWindows
            case _                 => NullMachine

    /** A flat, allocation-light snapshot of one tick's host readings. Every field is `Maybe`-typed so an
      * unavailable metric is `Absent` and not observed by the sampler. This is NOT a public value
      * type: it never leaves the sampler, carries no nested public ADT tree, and exists only to hand one
      * tick's primitives from the OS reader to the observe step.
      */
    final case class Reading(
        cpu: Maybe[CpuReading],
        memory: Maybe[MemoryReading],
        swap: Maybe[SwapReading],
        disks: Chunk[DiskReading],
        load: Maybe[LoadReading],
        cgroup: Maybe[CgroupReading],
        pressure: Maybe[PressureReading],
        cgroupPressure: Maybe[PressureReading]
    )

    object Reading:
        val empty: Reading =
            Reading(Absent, Absent, Absent, Chunk.empty, Absent, Absent, Absent, Absent)

    /** Per-mode cumulative cpu-time in nanoseconds. `Absent` per mode where the OS does not expose it. */
    final case class CpuReading(
        total: Maybe[Long],
        user: Maybe[Long],
        system: Maybe[Long],
        idle: Maybe[Long],
        iowait: Maybe[Long]
    )

    final case class MemoryReading(total: Maybe[Long], available: Maybe[Long], free: Maybe[Long])
    final case class SwapReading(total: Maybe[Long], free: Maybe[Long])
    final case class DiskReading(store: String, total: Maybe[Long], free: Maybe[Long])
    final case class LoadReading(one: Maybe[Double], five: Maybe[Double], fifteen: Maybe[Double])

    final case class CgroupReading(
        memoryUsage: Maybe[Long],
        memoryLimit: Maybe[Long],
        cpuQuota: Maybe[Long],
        cpuPeriod: Maybe[Long],
        periods: Maybe[Long],
        throttledPeriods: Maybe[Long],
        throttledTime: Maybe[Long]
    )

    /** PSI for one hierarchy (system or cgroup). `some`/`full` per resource; each carries three `avgNN`
      * percentages and a cumulative stall total in nanoseconds. `cpu.full` is parsed but never emitted.
      */
    final case class PressureReading(
        cpuSome: PsiReading,
        cpuFull: PsiReading,
        memorySome: PsiReading,
        memoryFull: PsiReading,
        ioSome: PsiReading,
        ioFull: PsiReading
    )

    final case class PsiReading(
        avg10: Maybe[Double],
        avg60: Maybe[Double],
        avg300: Maybe[Double],
        total: Maybe[Long]
    )

    object PsiReading:
        val empty: PsiReading = PsiReading(Absent, Absent, Absent, Absent)

    /** The reader for an OS with no dedicated implementation: every metric is `Absent`. */
    private[machine] object NullMachine extends Machine:
        def read(sampler: MachineSampler)(using AllowUnsafe): Reading = Reading.empty

end Machine
