package kyo.stats.machine

import kyo.*
import kyo.stats.internal.StatsRegistry
import kyo.stats.internal.UnsafeCounter
import kyo.stats.internal.UnsafeGauge
import kyo.stats.internal.UnsafeHistogram

/** The registry key of every `machine.*` metric, as a value rather than a string to reconstruct.
  *
  * A registry key is a `List[String]`: the scope segments, then the instrument name. The dotted rendering the
  * taxonomy tables use is LOSSY with respect to that key, because it does not say where the scope ends, and
  * the split is not the same for every row. `machine.cpu.total.rate` is scope `["machine","cpu"]` with name
  * `"total.rate"`, keeping the dot inside the name; `machine.memory.available` is scope
  * `["machine","memory"]` with name `"available"`. Nothing in a dotted string distinguishes the two, so a
  * consumer reconstructing keys from the tables gets half the taxonomy wrong, and a wrong key reads back
  * empty rather than failing.
  *
  * Every metric the sampler can produce is named here, so no key is ever reconstructed by hand:
  *
  * {{{
  * import kyo.stats.machine.MachineMetrics
  *
  * val cpu: Maybe[Summary] =
  *     MachineMetrics.cpuTotalRate.findHistogram.map(_.summary())
  * }}}
  *
  * Two families are named at runtime rather than fixed: one entry per mounted filesystem
  * (`MachineMetrics.disk`) and one per pressure resource-and-kind pair (`MachineMetrics.pressure`). Their
  * live members come from `StatsRegistry.snapshot`, which is also how to answer "what does this host
  * actually produce?": a metric the running OS has no source for registers nothing at all, so absence in the
  * snapshot is the answer.
  */
object MachineMetrics:

    /** The scope root every metric in this module registers under. */
    val root: String = "machine"

    /** Which instrument kind a key holds, so a reader calls the accessor that matches rather than guessing.
      *
      * The rule behind the assignments: a per-second `.rate` is a flow whose within-window distribution is
      * the metric, so it is a Histogram whose running sum also carries the cumulative total; a genuinely
      * varying level is a Histogram for the same reason; a fixed total, a pre-averaged kernel average or a
      * config value is a Gauge; and `cgroup.cpu.periods`, the one cumulative with no `.rate` companion, is
      * the module's only Counter.
      */
    enum Kind derives CanEqual:
        case Counter, Histogram, Gauge
    end Kind

    /** One metric's registry key.
      *
      * `scope` and `name` are a `List[String]` and a `String` because that is exactly the shape
      * `StatsRegistry` keys on: `path` here equals the `path` of the matching `StatsRegistry.Registration`,
      * so a key discovered through a snapshot and a key named here compare directly.
      */
    final case class Key(scope: List[String], name: String, kind: Kind) derives CanEqual:

        /** The flattened registry key, the same `List[String]` `StatsRegistry.Registration.path` carries. */
        def path: List[String] = scope :+ name

        /** The dotted rendering used in the taxonomy tables and by most exporters. Lossy: it does not say
          * where the scope ends, which is why this type exists.
          */
        def dotted: String = path.mkString(".")

        /** The registry scope this metric is registered in. */
        def registryScope: StatsRegistry.Scope = StatsRegistry.scope(scope*)

        /** The registered histogram at this key, or `Absent` when the host never produced it. Never
          * registers anything: a metric this OS has no source for stays absent instead of becoming a
          * permanently empty series.
          */
        def findHistogram: Maybe[UnsafeHistogram] =
            Maybe.fromOption(registryScope.findHistogram(name))

        /** The registered gauge at this key, or `Absent`. Never registers. */
        def findGauge: Maybe[UnsafeGauge] =
            Maybe.fromOption(registryScope.findGauge(name))

        /** The registered counter at this key, or `Absent`. Never registers. */
        def findCounter: Maybe[UnsafeCounter] =
            Maybe.fromOption(registryScope.findCounter(name))
    end Key

    private def key(scope: List[String], name: String, kind: Kind): Key = Key(scope, name, kind)

    private val cpuScope     = List(root, "cpu")
    private val memoryScope  = List(root, "memory")
    private val swapScope    = List(root, "swap")
    private val loadScope    = List(root, "load")
    private val cgroupScope  = List(root, "cgroup")
    private val diskScope    = List(root, "disk")
    private val pressScope   = List(root, "pressure")
    private val cgPressScope = List(root, "cgroup", "pressure")

    val cpuTotalRate: Key  = key(cpuScope, "total.rate", Kind.Histogram)
    val cpuUserRate: Key   = key(cpuScope, "user.rate", Kind.Histogram)
    val cpuSystemRate: Key = key(cpuScope, "system.rate", Kind.Histogram)
    val cpuIdleRate: Key   = key(cpuScope, "idle.rate", Kind.Histogram)
    val cpuIowaitRate: Key = key(cpuScope, "iowait.rate", Kind.Histogram)
    val cpuStealRate: Key  = key(cpuScope, "steal.rate", Kind.Histogram)
    val cpuCores: Key      = key(cpuScope, "cores", Kind.Gauge)

    val memoryTotal: Key     = key(memoryScope, "total", Kind.Gauge)
    val memoryAvailable: Key = key(memoryScope, "available", Kind.Histogram)
    val memoryFree: Key      = key(memoryScope, "free", Kind.Histogram)

    val swapTotal: Key = key(swapScope, "total", Kind.Gauge)
    val swapFree: Key  = key(swapScope, "free", Kind.Histogram)

    val loadOne: Key     = key(loadScope, "one", Kind.Gauge)
    val loadFive: Key    = key(loadScope, "five", Kind.Gauge)
    val loadFifteen: Key = key(loadScope, "fifteen", Kind.Gauge)

    val cgroupMemoryUsage: Key         = key(cgroupScope, "memory.usage", Kind.Histogram)
    val cgroupMemoryLimit: Key         = key(cgroupScope, "memory.limit", Kind.Gauge)
    val cgroupCpuQuota: Key            = key(cgroupScope, "cpu.quota", Kind.Gauge)
    val cgroupCpuPeriod: Key           = key(cgroupScope, "cpu.period", Kind.Gauge)
    val cgroupCpuPeriods: Key          = key(cgroupScope, "cpu.periods", Kind.Counter)
    val cgroupCpuThrottledPeriods: Key = key(cgroupScope, "cpu.throttled.periods.rate", Kind.Histogram)
    val cgroupCpuThrottledTime: Key    = key(cgroupScope, "cpu.throttled.rate", Kind.Histogram)

    /** The keys of one mount's disk metrics.
      *
      * `store` is the sanitized mount identity the sampler derives at runtime: `/` becomes `root`, and any
      * other mount loses its leading `/` and has every remaining `/` and `.` replaced by `_`. The mount set
      * is genuinely dynamic, so read the live names from `stores` rather than guessing them.
      */
    final case class Disk(store: String) derives CanEqual:
        private val scope: List[String] = diskScope :+ store
        val total: Key                  = key(scope, "total", Kind.Gauge)
        val free: Key                   = key(scope, "free", Kind.Histogram)
    end Disk

    /** The disk keys for one mount identity. */
    def disk(store: String): Disk = Disk(store)

    /** The mount identities this process has actually registered disk metrics for, in key order. */
    def diskStores(): Seq[String] =
        StatsRegistry.snapshot(diskScope*)
            .collect { case r if r.path.size > diskScope.size => r.path(diskScope.size) }
            .distinct

    /** The four keys of one pressure resource-and-kind pair.
      *
      * `resource` is `cpu`, `memory` or `io`; `kind` is `some` or `full`. The kernel pins `cpu.full` at zero
      * and the sampler never emits it, so that pair registers nothing on any host.
      */
    final case class Pressure(scope: List[String], resource: String, kind: String) derives CanEqual:
        private val pairScope: List[String] = scope ++ List(resource, kind)
        val avg10: Key                      = key(pairScope, "avg10", Kind.Gauge)
        val avg60: Key                      = key(pairScope, "avg60", Kind.Gauge)
        val avg300: Key                     = key(pairScope, "avg300", Kind.Gauge)
        val rate: Key                       = key(pairScope, "rate", Kind.Histogram)
    end Pressure

    /** The system-wide pressure keys for one resource-and-kind pair: contention across the whole host. */
    def pressure(resource: String, kind: String): Pressure = Pressure(pressScope, resource, kind)

    /** The cgroup pressure keys for one resource-and-kind pair: contention this container experiences, which
      * can differ sharply from the host's under a busy neighbour.
      */
    def cgroupPressure(resource: String, kind: String): Pressure = Pressure(cgPressScope, resource, kind)

    /** The resource-and-kind pairs the sampler emits, on a host that produces pressure at all. */
    val pressurePairs: Seq[(String, String)] =
        List("cpu" -> "some", "memory" -> "some", "memory" -> "full", "io" -> "some", "io" -> "full")

    /** Every fixed key in the taxonomy: everything except the two runtime-named families, `disk` and the
      * pressure pairs. A key here is what the metric WOULD be registered as; whether it is registered on
      * this host is what `findHistogram` / `findGauge` / `findCounter` answer.
      */
    val all: Seq[Key] =
        List(
            cpuTotalRate,
            cpuUserRate,
            cpuSystemRate,
            cpuIdleRate,
            cpuIowaitRate,
            cpuStealRate,
            cpuCores,
            memoryTotal,
            memoryAvailable,
            memoryFree,
            swapTotal,
            swapFree,
            loadOne,
            loadFive,
            loadFifteen,
            cgroupMemoryUsage,
            cgroupMemoryLimit,
            cgroupCpuQuota,
            cgroupCpuPeriod,
            cgroupCpuPeriods,
            cgroupCpuThrottledPeriods,
            cgroupCpuThrottledTime
        )

end MachineMetrics
