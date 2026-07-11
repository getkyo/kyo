package kyo.stats.machine

import kyo.*

/** One PSI family's retained handles (system or cgroup). For cpu.some, memory.some/full, io.some/full
  * each carries three avg Histograms, a cumulative stall total Counter, and a paired per-second stall
  * Histogram. cpu.full is intentionally omitted (present-but-pinned-zero, not emitted). observe advances
  * each total Counter by the per-tick delta and observes the same delta into the paired stall Histogram.
  */
final private[machine] class PsiHandles private (
    cpuSome: PsiHandles.One,
    memSome: PsiHandles.One,
    memFull: PsiHandles.One,
    ioSome: PsiHandles.One,
    ioFull: PsiHandles.One
):
    def observe(reading: Maybe[Machine.PressureReading], st: MachineSampler.PriorState, family: String)(using
        AllowUnsafe
    ): MachineSampler.PriorState =
        reading match
            case Present(p) =>
                var s = cpuSome.observe(p.cpuSome, st, family + ".cpu.some")
                s = memSome.observe(p.memorySome, s, family + ".memory.some")
                s = memFull.observe(p.memoryFull, s, family + ".memory.full")
                s = ioSome.observe(p.ioSome, s, family + ".io.some")
                s = ioFull.observe(p.ioFull, s, family + ".io.full")
                s
            case Absent => st
end PsiHandles

private[machine] object PsiHandles:

    final class One private[PsiHandles] (
        avg10: Histogram,
        avg60: Histogram,
        avg300: Histogram,
        total: Counter,
        rate: Histogram
    ):
        def observe(r: Machine.PsiReading, st: MachineSampler.PriorState, key: String)(using
            AllowUnsafe
        ): MachineSampler.PriorState =
            r.avg10.foreach(v => avg10.unsafe.observe(v))
            r.avg60.foreach(v => avg60.unsafe.observe(v))
            r.avg300.foreach(v => avg300.unsafe.observe(v))
            r.total match
                case Present(cur) =>
                    st.get(key) match
                        case Present(prev) =>
                            val delta = cur - prev
                            val adv   = if delta < 0 then 0L else delta
                            total.unsafe.add(adv)
                            rate.unsafe.observe(adv)
                            st.set(key, cur)
                        case Absent => st.set(key, cur)
                case Absent => st
            end match
        end observe
    end One

    private def one(scope: Stat, res: String, kind: String): One =
        val s = scope.scope(res, kind)
        One(
            s.initHistogram("avg10", "percent 0..100", boundaries = MachineHandles.percent),
            s.initHistogram("avg60", "percent 0..100", boundaries = MachineHandles.percent),
            s.initHistogram("avg300", "percent 0..100", boundaries = MachineHandles.percent),
            s.initCounter("total", "cumulative stall time, ns"),
            s.initHistogram("rate", "per-second stall-time delta, ns/s", boundaries = MachineHandles.nanosPerSec)
        )
    end one

    def init(scope: Stat): PsiHandles =
        new PsiHandles(
            one(scope, "cpu", "some"),
            one(scope, "memory", "some"),
            one(scope, "memory", "full"),
            one(scope, "io", "some"),
            one(scope, "io", "full")
        )

end PsiHandles
