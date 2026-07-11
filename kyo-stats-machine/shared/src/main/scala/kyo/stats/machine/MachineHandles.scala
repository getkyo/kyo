package kyo.stats.machine

import kyo.*

/** Every `kyo.Stat` metric handle the sampler retains, created once under `Stat.scope("machine")`.
  *
  * The handle set is the machine.* taxonomy: cpu.<mode>.total Counters paired with cpu.<mode>.rate Histograms
  * (dual-treatment), memory/swap/load gauge Histograms, cpu.cores CounterGauge, the cgroup family, and
  * the two PSI families (system and cgroup). Each cumulative-time Counter is created alongside its
  * per-second-delta Histogram so the pairing is structural. `observe` advances every paired Counter by
  * the per-tick delta on the scaled value and observes that same delta into its paired Histogram; an
  * unpaired Counter (cgroup.cpu.periods, which the taxonomy declares with no dual) advances by its own
  * delta and records into no Histogram. Every gauge is observed; an Absent field is skipped and does
  * not advance its Counter or prior. Disk handles are created per fixed mount at init by the sampler
  * and observed the same way.
  */
final private[kyo] class MachineHandles private (root: Stat, cores: CounterGauge):

    import MachineHandles.*

    private val cpu    = root.scope("cpu")
    val cpuTimeTotal   = cpu.initCounter("total.total", "cumulative cpu-time, ns")
    val cpuTimeUser    = cpu.initCounter("user.total", "cumulative cpu-time, ns")
    val cpuTimeSystem  = cpu.initCounter("system.total", "cumulative cpu-time, ns")
    val cpuTimeIdle    = cpu.initCounter("idle.total", "cumulative cpu-time, ns")
    val cpuTimeIowait  = cpu.initCounter("iowait.total", "cumulative cpu-time, ns")
    val cpuUsageTotal  = cpu.initHistogram("total.rate", "per-second cpu-time delta, ns/s", boundaries = nanosPerSec)
    val cpuUsageUser   = cpu.initHistogram("user.rate", "per-second cpu-time delta, ns/s", boundaries = nanosPerSec)
    val cpuUsageSystem = cpu.initHistogram("system.rate", "per-second cpu-time delta, ns/s", boundaries = nanosPerSec)
    val cpuUsageIdle   = cpu.initHistogram("idle.rate", "per-second cpu-time delta, ns/s", boundaries = nanosPerSec)
    val cpuUsageIowait = cpu.initHistogram("iowait.rate", "per-second cpu-time delta, ns/s", boundaries = nanosPerSec)

    private val mem  = root.scope("memory")
    val memTotal     = mem.initHistogram("total", "bytes", boundaries = bytes)
    val memAvailable = mem.initHistogram("available", "bytes", boundaries = bytes)
    val memFree      = mem.initHistogram("free", "bytes", boundaries = bytes)

    private val swp = root.scope("swap")
    val swapTotal   = swp.initHistogram("total", "bytes", boundaries = bytes)
    val swapFree    = swp.initHistogram("free", "bytes", boundaries = bytes)

    private val ld  = root.scope("load")
    val loadOne     = ld.initHistogram("one", "load units", boundaries = load)
    val loadFive    = ld.initHistogram("five", "load units", boundaries = load)
    val loadFifteen = ld.initHistogram("fifteen", "load units", boundaries = load)

    val coresGauge = cores

    private val cg        = root.scope("cgroup")
    val cgMemUsage        = cg.initHistogram("memory.usage", "bytes", boundaries = bytes)
    val cgCpuPeriods      = cg.initCounter("cpu.periods", "cumulative count")
    val cgCpuThrPeriods   = cg.initCounter("cpu.throttled.periods.total", "cumulative count")
    val cgCpuThrPeriodsHi = cg.initHistogram("cpu.throttled.periods.rate", "throttled periods per second", boundaries = countPerSec)
    val cgCpuThrTime      = cg.initCounter("cpu.throttled.total", "cumulative throttled time, ns")
    val cgCpuThrTimeHi    = cg.initHistogram("cpu.throttled.rate", "per-second throttled-time delta, ns/s", boundaries = nanosPerSec)

    // Config-value gauges (memory.limit, cpu.quota, cpu.period): a config value that is genuinely
    // unavailable must not be registered or emitted at all. Each config gauge is created LAZILY on the
    // FIRST Present observation (the same first-seen-then-retained model the per-store disk handles use):
    // an unset limit/quota registers no handle and exports nothing; once a Present value is seen, the gauge
    // is created once, SEEDED with that first value (so its first poll is never a transient 0), and its
    // poll thereafter reads the last observed value from its holder. A config value is a point-in-time
    // reading that can rise OR fall, so each is a plain Gauge (raw value, no delta), never a CounterGauge
    // (whose delta wraps a decrease to garbage).
    private val configGauges = collection.mutable.HashMap.empty[String, ConfigGauge]

    private def configFor(name: String, unit: String, first: Long): ConfigGauge =
        configGauges.getOrElseUpdate(name, ConfigGauge(cg, name, unit, first))

    /** The system PSI and cgroup PSI families, each a full set of avg Histograms + total Counters + stall
      * Histograms for cpu.some, memory.some/full, io.some/full (cpu.full parsed but not emitted).
      */
    val systemPressure = PsiHandles.init(root.scope("pressure"))
    val cgroupPressure = PsiHandles.init(root.scope("cgroup", "pressure"))

    /** Per-store disk handles, created ONCE per mount (init-once retained-handle model). The store set is
      * fixed the first time a mount is seen; sanitized store names come from the disk reader.
      */
    private val diskHandles = collection.mutable.LinkedHashMap.empty[String, DiskHandles]

    private def diskFor(store: String): DiskHandles =
        diskHandles.getOrElseUpdate(
            store, {
                val d = root.scope("disk", store)
                DiskHandles(d.initHistogram("total", "bytes", boundaries = bytes), d.initHistogram("free", "bytes", boundaries = bytes))
            }
        )

    /** Observes one reading; returns the updated prior-cumulative state. The delta/scale/dual logic lives
      * here so the sampler tick is a single call. Absent fields are skipped.
      */
    def observe(reading: Machine.Reading, prior: MachineSampler.PriorState)(using
        AllowUnsafe
    ): MachineSampler.PriorState =
        var st     = prior
        val stores = MachineHandles.storeNames(reading.disks.map(_.store))
        reading.disks.zip(stores).foreach { case (d, store) =>
            val h = diskFor(store)
            d.total.foreach(v => h.total.unsafe.observe(v))
            d.free.foreach(v => h.free.unsafe.observe(v))
        }
        reading.cpu.foreach { c =>
            st = advance(st, "cpu.total.total", c.total, cpuTimeTotal, cpuUsageTotal)
            st = advance(st, "cpu.user.total", c.user, cpuTimeUser, cpuUsageUser)
            st = advance(st, "cpu.system.total", c.system, cpuTimeSystem, cpuUsageSystem)
            st = advance(st, "cpu.idle.total", c.idle, cpuTimeIdle, cpuUsageIdle)
            st = advance(st, "cpu.iowait.total", c.iowait, cpuTimeIowait, cpuUsageIowait)
        }
        reading.memory.foreach { m =>
            m.total.foreach(v => memTotal.unsafe.observe(v))
            m.available.foreach(v => memAvailable.unsafe.observe(v))
            m.free.foreach(v => memFree.unsafe.observe(v))
        }
        reading.swap.foreach { s =>
            s.total.foreach(v => swapTotal.unsafe.observe(v))
            s.free.foreach(v => swapFree.unsafe.observe(v))
        }
        reading.load.foreach { l =>
            l.one.foreach(v => loadOne.unsafe.observe(v))
            l.five.foreach(v => loadFive.unsafe.observe(v))
            l.fifteen.foreach(v => loadFifteen.unsafe.observe(v))
        }
        st = observeCgroup(reading.cgroup, st)
        st = systemPressure.observe(reading.pressure, st, "pressure")
        st = cgroupPressure.observe(reading.cgroupPressure, st, "cgroup.pressure")
        st
    end observe

    /** Advances a Counter by the per-tick delta on the scaled value and observes the same delta into the
      * paired Histogram. First tick records the baseline (advance 0); a negative delta clamps to 0; an
      * Absent read leaves the Counter and prior unchanged.
      */
    private def advance(
        st: MachineSampler.PriorState,
        key: String,
        value: Maybe[Long],
        counter: Counter,
        histogram: Histogram
    )(using AllowUnsafe): MachineSampler.PriorState =
        step(st, key, value)((adv, next) =>
            counter.unsafe.add(adv); histogram.unsafe.observe(adv); next
        )

    /** Advances an UNPAIRED Counter by the per-tick delta with no Histogram. Used for a metric that carries
      * no dual in the taxonomy (cgroup.cpu.periods): the delta advances the Counter and nothing more.
      */
    private def advanceCounter(
        st: MachineSampler.PriorState,
        key: String,
        value: Maybe[Long],
        counter: Counter
    )(using AllowUnsafe): MachineSampler.PriorState =
        step(st, key, value)((adv, next) =>
            counter.unsafe.add(adv); next
        )

    private inline def step(
        st: MachineSampler.PriorState,
        key: String,
        value: Maybe[Long]
    )(inline record: (Long, MachineSampler.PriorState) => MachineSampler.PriorState): MachineSampler.PriorState =
        value match
            case Present(cur) =>
                st.get(key) match
                    case Present(prev) =>
                        val delta = cur - prev
                        val adv   = if delta < 0 then 0L else delta
                        record(adv, st.set(key, cur))
                    case Absent =>
                        st.set(key, cur)
            case Absent => st

    private def observeCgroup(
        reading: Maybe[Machine.CgroupReading],
        st: MachineSampler.PriorState
    )(using AllowUnsafe): MachineSampler.PriorState =
        reading match
            case Present(c) =>
                c.memoryUsage.foreach(v => cgMemUsage.unsafe.observe(v))
                // A config value only registers (and thereafter polls) its Gauge once a Present value is
                // seen; an Absent config records nothing, so no fabricated value is ever exported. The gauge
                // is seeded with this first value at construction, and every later tick's value is set into
                // the same holder.
                c.memoryLimit.foreach(v => configFor("memory.limit", "bytes (config value)", v).set(v))
                c.cpuQuota.foreach(v => configFor("cpu.quota", "ns (config value)", v).set(v))
                c.cpuPeriod.foreach(v => configFor("cpu.period", "ns (config value)", v).set(v))
                var s = advanceCounter(st, "cgroup.cpu.periods", c.periods, cgCpuPeriods)
                s = advance(s, "cgroup.cpu.throttled.periods.total", c.throttledPeriods, cgCpuThrPeriods, cgCpuThrPeriodsHi)
                s = advance(s, "cgroup.cpu.throttled.total", c.throttledTime, cgCpuThrTime, cgCpuThrTimeHi)
                s
            case Absent => st

end MachineHandles

private[kyo] object MachineHandles:

    final case class DiskHandles(total: Histogram, free: Histogram)

    /** A config-value Gauge created only once a genuine Present value has been observed. The gauge's poll
      * reads the last observed value from the retained holder; the handle does not exist (and exports
      * nothing) until the gauge is constructed on first observation, so an unset config never emits a
      * fabricated value, and the gauge is seeded with the first observed value so its first poll is never a
      * transient 0. A plain Gauge (not a CounterGauge) exports the raw value with no delta, correct for a
      * point-in-time config value that can rise OR fall (a lowered cgroup limit at runtime); a CounterGauge
      * would map a decrease to a wraparound.
      *
      * The holder is `kyo.AtomicLong.Unsafe`, the kyo opaque alias of the same underlying atomic: a
      * single-owner cross-tick cell. The collect-time poll body runs on the registry flusher with no
      * capability in scope, so it reads the raw atomic directly; `set` takes the sampler tick's own
      * propagated capability rather than re-embracing it.
      */
    final class ConfigGauge private (holder: AtomicLong.Unsafe, gauge: Gauge):
        def set(v: Long)(using AllowUnsafe): Unit = holder.set(v)

    object ConfigGauge:
        def apply(cg: Stat, name: String, unit: String, initial: Long): ConfigGauge =
            // Unsafe: constructed lazily from configFor, off any ambient effect context; the collect-time
            // poll body that reads this holder also runs with no capability available.
            import AllowUnsafe.embrace.danger
            val holder = AtomicLong.Unsafe.init(initial)
            new ConfigGauge(holder, cg.initGauge(name, unit)(holder.get().toDouble))
        end apply
    end ConfigGauge

    val nanosPerSec: Array[Double] =
        Array(0d, 1000000d, 10000000d, 50000000d, 100000000d, 250000000d, 500000000d, 1000000000d,
            2000000000d, 4000000000d, 8000000000d)

    val bytes: Array[Double] =
        Array(0d, 1048576d, 16777216d, 67108864d, 268435456d, 1073741824d, 4294967296d, 17179869184d,
            68719476736d, 274877906944d)

    val load: Array[Double] =
        Array(0d, 0.25d, 0.5d, 1d, 2d, 4d, 8d, 16d, 32d, 64d, 128d)

    val percent: Array[Double] =
        Array(0d, 1d, 5d, 10d, 25d, 50d, 75d, 90d, 95d, 99d, 100d)

    val countPerSec: Array[Double] =
        Array(0d, 1d, 2d, 4d, 8d, 16d, 32d, 64d, 128d, 256d)

    /** Deterministic Stat scope segment per mount path: `/` -> `root`; else strip the leading `/` and
      * replace every remaining `/` and `.` with `_`; a collision gets a stable numeric suffix in
      * enumeration order (`_2`, `_3`). The sampler owns the mount identity, so this rule lives here.
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
            val root  = Stat.initScope("machine")
            val gauge = root.scope("cpu").initCounterGauge("cores", "count (fixed process core count)")(cores.toLong)
            Sync.Unsafe.defer(new MachineHandles(root, gauge))
        }

end MachineHandles
