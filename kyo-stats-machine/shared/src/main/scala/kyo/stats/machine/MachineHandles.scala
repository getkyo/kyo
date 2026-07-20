package kyo.stats.machine

import kyo.*

/** Every `kyo.Stat` metric cell the sampler retains, created once under `Stat.scope("machine")`.
  *
  * A CELL is the one thing a decoder writes to. It owns its `kyo.Stat` handle, its prior-cumulative value
  * when it has one, and its holder when it is a gauge; it takes a decoded PRIMITIVE and does the rest. A
  * decoder therefore never builds a value to hand over, never wraps an absent reading, and never looks a
  * handle up: it calls a field.
  *
  * Every cell registers its handle on its FIRST Present observation and reads it as a field thereafter, so
  * a metric this host never produces (load averages on Windows, cgroup and PSI off Linux, cpu.steal off
  * Linux, cpu.iowait on macOS) registers no series at all, and a gauge is seeded with its first real value
  * BEFORE it is registered, so its first poll is never a transient zero.
  *
  * The taxonomy: a per-second `.rate` is a flow whose within-window distribution is the metric, so it is a
  * `Histogram` whose running sum also carries the cumulative total (which is why no metric with a `.rate`
  * carries a separate cumulative Counter). A genuinely varying byte level is a `Histogram` too, because a
  * 1 Hz sample over the export window captures a spike a poll-at-flush would miss. A fixed total, a
  * pre-averaged kernel average, a config value and the core count neither rise nor fall within a window in
  * a way a distribution would describe, so each is a `Gauge` backed by a retained holder the sampler writes
  * and the registry polls. `machine.cgroup.cpu.periods` is the one cumulative with no `.rate` pair, so it
  * is the one Counter.
  */
final private[kyo] class MachineHandles private (root: Stat, coreCount: Long):

    import MachineHandles.*

    /** The per-second cpu-time boundaries, derived from the host core count at init: a fixed 8-core ceiling
      * put every observation of a larger host into the overflow bucket and collapsed its percentiles.
      */
    val nanosPerSec: Array[Double] = nanosPerSecFor(coreCount)

    private val cpu = root.scope("cpu")
    val cpuTotal    = RateCell(cpu, "total.rate", "per-second cpu-time delta, ns/s", nanosPerSec)
    val cpuUser     = RateCell(cpu, "user.rate", "per-second cpu-time delta, ns/s", nanosPerSec)
    val cpuSystem   = RateCell(cpu, "system.rate", "per-second cpu-time delta, ns/s", nanosPerSec)
    val cpuIdle     = RateCell(cpu, "idle.rate", "per-second cpu-time delta, ns/s", nanosPerSec)
    val cpuIowait   = RateCell(cpu, "iowait.rate", "per-second cpu-time delta, ns/s", nanosPerSec)
    val cpuSteal    = RateCell(cpu, "steal.rate", "per-second cpu-time delta, ns/s", nanosPerSec)
    val cpuCores    = LongGaugeCell(cpu, "cores", "count (fixed process core count)")

    private val mem  = root.scope("memory")
    val memTotal     = LongGaugeCell(mem, "total", "bytes")
    val memAvailable = LevelCell(mem, "available", "bytes", byteBoundaries)
    val memFree      = LevelCell(mem, "free", "bytes", byteBoundaries)

    private val swp = root.scope("swap")
    val swapTotal   = LongGaugeCell(swp, "total", "bytes")
    val swapFree    = LevelCell(swp, "free", "bytes", byteBoundaries)

    private val ld  = root.scope("load")
    val loadOne     = DoubleGaugeCell(ld, "one", "load units")
    val loadFive    = DoubleGaugeCell(ld, "five", "load units")
    val loadFifteen = DoubleGaugeCell(ld, "fifteen", "load units")

    private val cg   = root.scope("cgroup")
    val cgMemUsage   = LevelCell(cg, "memory.usage", "bytes", byteBoundaries)
    val cgMemLimit   = LongGaugeCell(cg, "memory.limit", "bytes (config value)")
    val cgCpuQuota   = LongGaugeCell(cg, "cpu.quota", "nanoseconds (config value)")
    val cgCpuPeriod  = LongGaugeCell(cg, "cpu.period", "nanoseconds (config value)")
    val cgCpuPeriods = CounterCell(cg, "cpu.periods", "cumulative count")
    val cgThrPeriods = RateCell(cg, "cpu.throttled.periods.rate", "throttled periods per second", countPerSec)
    val cgThrTime    = RateCell(cg, "cpu.throttled.rate", "per-second throttled-time delta, ns/s", nanosPerSec)

    /** The two PSI families, kept distinct so system-wide and per-cgroup pressure never mix. */
    val systemPressure = PsiHandles(root.scope("pressure"), nanosPerSec)
    val cgroupPressure = PsiHandles(root.scope("cgroup", "pressure"), nanosPerSec)

    // The ONE surviving registry. The mount set is genuinely dynamic (unknown at init, and a filesystem
    // mounted later must still be picked up), so a per-store cell set cannot be a fixed field. It is
    // consulted ONLY when the reader derives its store set, which happens at init and again only when the
    // mount table actually changes, never per observation: the readers hold direct references to the cells
    // they write on the steady-state tick.
    private val diskScope  = root.scope("disk")
    private val diskStores = collection.mutable.LinkedHashMap.empty[String, DiskStore]

    def diskStore(store: String): DiskStore =
        diskStores.getOrElseUpdate(store, new DiskStore(diskScope.scope(store), byteBoundaries))

    // The core count is available on every OS, so its gauge is seeded and registered at init.
    // Unsafe: single-owner cell state on the sampler fiber.
    import AllowUnsafe.embrace.danger
    cpuCores.set(coreCount)

end MachineHandles

private[kyo] object MachineHandles:

    /** A cumulative reading's cell: it holds its OWN prior value, so no key, no map and no cross-metric
      * aliasing exist. The first tick baselines (nothing is observed); each later tick observes
      * `current - prior`, clamped at zero so a counter reset or a wraparound cannot record a negative flow.
      * The `.rate` Histogram's running sum carries the cumulative total, which is why no paired cumulative
      * Counter exists.
      */
    final private[machine] class RateCell(scope: Stat, name: String, description: String, boundaries: Array[Double]):
        private val prior =
            // Unsafe: single-owner cell state on the sampler fiber.
            import AllowUnsafe.embrace.danger
            AtomicLong.Unsafe.init(Path.ReadHandle.AbsentLong)
        end prior
        private var handle: Maybe[Histogram] = Absent

        def observe(cur: Long)(using AllowUnsafe): Unit =
            if cur != Path.ReadHandle.AbsentLong then
                val prev = prior.getAndSet(cur)
                if prev != Path.ReadHandle.AbsentLong then
                    val delta = cur - prev
                    histogram().unsafe.observe(if delta < 0L then 0L else delta)
        end observe

        private def histogram(): Histogram =
            handle match
                case Present(h) => h
                case Absent =>
                    val h = scope.initHistogram(name, description, boundaries = boundaries)
                    handle = Present(h)
                    h
    end RateCell

    /** A genuinely varying level's cell: every Present sample is observed into its Histogram, so a
      * within-window spike survives the export window a poll-at-flush would average away.
      */
    final private[machine] class LevelCell(scope: Stat, name: String, description: String, boundaries: Array[Double]):
        private var handle: Maybe[Histogram] = Absent

        def observe(v: Long)(using AllowUnsafe): Unit =
            if v != Path.ReadHandle.AbsentLong then histogram().unsafe.observe(v.toDouble)

        private def histogram(): Histogram =
            handle match
                case Present(h) => h
                case Absent =>
                    val h = scope.initHistogram(name, description, boundaries = boundaries)
                    handle = Present(h)
                    h
    end LevelCell

    /** The one standalone cumulative with no `.rate` pair (`cgroup.cpu.periods`): it advances a Counter by
      * its own per-tick delta, baselining on the first tick, and records into no Histogram.
      */
    final private[machine] class CounterCell(scope: Stat, name: String, description: String):
        private val prior =
            // Unsafe: single-owner cell state on the sampler fiber.
            import AllowUnsafe.embrace.danger
            AtomicLong.Unsafe.init(Path.ReadHandle.AbsentLong)
        end prior
        private var handle: Maybe[Counter] = Absent

        def observe(cur: Long)(using AllowUnsafe): Unit =
            if cur != Path.ReadHandle.AbsentLong then
                val prev = prior.getAndSet(cur)
                if prev != Path.ReadHandle.AbsentLong then
                    val delta = cur - prev
                    counter().unsafe.add(if delta < 0L then 0L else delta)
        end observe

        private def counter(): Counter =
            handle match
                case Present(c) => c
                case Absent =>
                    val c = scope.initCounter(name, description)
                    handle = Present(c)
                    c
    end CounterCell

    /** A point-in-time value's cell: the sampler writes each tick's value into a retained holder, and the
      * Gauge the registry polls reads that holder. `kyo.Stat.Gauge` is pull-based and has no push or set,
      * which is why the holder exists. The Gauge is created on the first Present value, AFTER the holder is
      * seeded with it, so an unavailable metric registers nothing and a registered one never polls a
      * transient zero. A plain Gauge, not a CounterGauge: a config value or a level can fall, and a
      * CounterGauge would map a decrease to a wraparound.
      */
    final private[machine] class LongGaugeCell(scope: Stat, name: String, description: String):
        private val holder =
            // Unsafe: single-owner holder; the collect-time poll body reads it with no capability in scope.
            import AllowUnsafe.embrace.danger
            AtomicLong.Unsafe.init(0L)
        end holder
        private var handle: Maybe[Gauge] = Absent

        def set(v: Long)(using AllowUnsafe): Unit =
            if v != Path.ReadHandle.AbsentLong then
                holder.set(v)
                if handle.isEmpty then
                    handle = Present(scope.initGauge(name, description)(holder.get().toDouble))
        end set
    end LongGaugeCell

    /** A fixed-point point-in-time value's cell (a load average, a PSI percentage): the raw Double bits ride
      * the same retained `AtomicLong` holder, so the value crosses to the pull-based Gauge with no box and
      * no second holder type. `Double.NaN` is the absent marker, and a load average or a PSI percentage is
      * never legitimately NaN, so the sentinel is collision-free.
      */
    final private[machine] class DoubleGaugeCell(scope: Stat, name: String, description: String):
        private val holder =
            // Unsafe: single-owner holder; the collect-time poll body reads it with no capability in scope.
            import AllowUnsafe.embrace.danger
            AtomicLong.Unsafe.init(java.lang.Double.doubleToRawLongBits(0.0))
        end holder
        private var handle: Maybe[Gauge] = Absent

        def set(v: Double)(using AllowUnsafe): Unit =
            if !java.lang.Double.isNaN(v) then
                holder.set(java.lang.Double.doubleToRawLongBits(v))
                if handle.isEmpty then
                    handle = Present(
                        scope.initGauge(name, description)(java.lang.Double.longBitsToDouble(holder.get()))
                    )
                end if
        end set
    end DoubleGaugeCell

    /** One mount's cells: a fixed capacity (a Gauge) and a varying free space (a Histogram). */
    final private[machine] class DiskStore(scope: Stat, boundaries: Array[Double]):
        val total = LongGaugeCell(scope, "total", "bytes")
        val free  = LevelCell(scope, "free", "bytes", boundaries)
    end DiskStore

    /** The per-second cpu-time boundary set, topped at one core-second per core per second so a host with
      * more than 8 cores does not saturate the overflow bucket. Derived once, at sampler init.
      */
    def nanosPerSecFor(cores: Long): Array[Double] =
        val top  = math.max(1L, cores).toDouble * 1000000000d
        val base = Array(0d, 100000000d, 500000000d, 1000000000d, 2000000000d, 4000000000d, 8000000000d)
        if top <= 8000000000d then base
        else base ++ Array(top / 2, top * 3 / 4, top).filter(_ > base.last)
    end nanosPerSecFor

    val byteBoundaries: Array[Double] =
        Array(0d, 1048576d, 16777216d, 67108864d, 268435456d, 1073741824d, 4294967296d, 17179869184d,
            68719476736d, 274877906944d)

    val countPerSec: Array[Double] =
        Array(0d, 1d, 2d, 4d, 8d, 16d, 32d, 64d, 128d, 256d)

    /** A deterministic Stat scope segment per mount path: `/` becomes `root`; otherwise the leading `/` is
      * stripped and every remaining `/` and `.` becomes `_`; a collision takes a stable numeric suffix in
      * enumeration order. Run when the store set is derived, never on a tick.
      */
    def storeNames(mounts: Seq[String]): Seq[String] =
        val seen = collection.mutable.HashMap.empty[String, Int]
        mounts.map { m =>
            val base = if m == "/" then "root" else m.stripPrefix("/").replace("/", "_").replace(".", "_")
            val n    = seen.getOrElse(base, 0) + 1
            seen.update(base, n)
            if n == 1 then base else base + "_" + n
        }
    end storeNames

    def init(using Frame): MachineHandles < Sync =
        System.availableProcessors.map { cores =>
            Sync.Unsafe.defer(new MachineHandles(Stat.initScope("machine"), cores.toLong))
        }

    /** Test-only seam: a `MachineHandles` rooted at an arbitrary scope instead of the shared "machine"
      * root every `init` call resolves to, so a test can observe its own cells without racing another
      * leaf's or suite's registration of the same well-known path. Never called by production code.
      */
    private[machine] def initForTest(scope: Stat, cores: Long): MachineHandles = new MachineHandles(scope, cores)

end MachineHandles
