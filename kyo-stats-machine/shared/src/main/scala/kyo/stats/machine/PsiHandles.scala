package kyo.stats.machine

import kyo.*

/** One PSI family's retained cells (the system family, or the cgroup family).
  *
  * Each emitted resource-and-kind pair carries three pre-averaged kernel percentages, which are Gauges, and
  * one per-second stall flow, which is a Histogram whose running sum also carries the cumulative stall time
  * (so there is no separate cumulative Counter). `cpu.full` is parsed by the kernel but is not emitted, so
  * it has no cells at all.
  */
final private[machine] class PsiHandles private (
    val cpuSome: PsiHandles.One,
    val memorySome: PsiHandles.One,
    val memoryFull: PsiHandles.One,
    val ioSome: PsiHandles.One,
    val ioFull: PsiHandles.One
)

private[machine] object PsiHandles:

    import MachineHandles.*

    /** The cells for one resource-and-kind pair (`cpu.some`, `memory.full`, ...). */
    final class One private[PsiHandles] (scope: Stat, nanosPerSec: Array[Double]):
        val avg10  = DoubleGaugeCell(scope, "avg10", "percent 0..100")
        val avg60  = DoubleGaugeCell(scope, "avg60", "percent 0..100")
        val avg300 = DoubleGaugeCell(scope, "avg300", "percent 0..100")
        val rate   = RateCell(scope, "rate", "per-second stall-time delta, ns/s", nanosPerSec)
    end One

    def apply(scope: Stat, nanosPerSec: Array[Double]): PsiHandles =
        def one(res: String, kind: String) = new One(scope.scope(res, kind), nanosPerSec)
        new PsiHandles(
            one("cpu", "some"),
            one("memory", "some"),
            one("memory", "full"),
            one("io", "some"),
            one("io", "full")
        )
    end apply

end PsiHandles
