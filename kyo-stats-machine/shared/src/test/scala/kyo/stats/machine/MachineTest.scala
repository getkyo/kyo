package kyo.stats.machine

import kyo.*
import kyo.stats.internal.StatsRegistry
import kyo.stats.internal.Summary
import kyo.stats.internal.UnsafeGauge
import kyo.stats.internal.UnsafeHistogram

class MachineTest extends kyo.test.Test[Any]:

    // Every leaf's MachineHandles.init shares the SAME process-global StatsRegistry scope ("machine")
    // every other suite's own MachineHandles.init resolves to; the assembled-reader leaf below reads
    // before/after registry deltas rather than absolute counts, so a concurrently-running sibling suite's
    // own observations cannot corrupt it.
    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    /** The registered `machine.*` keys across all four stores. Enumerates keys directly rather than through
      * `MachineRegistrySnapshot.read`, which dereferences each entry's `WeakReference` and is JVM-only:
      * Scala.js has no linkable `java.lang.ref.Reference`, and on Scala Native `WeakReference` does not
      * extend it (a `ClassCastException` at the dereference), so `MachineRegistrySnapshot`'s every other
      * caller is `.onlyJvm`-gated. This leaf needs no VALUE, only the registered key set, so a plain
      * `ConcurrentHashMap.keySet()` walk (a standard, cross-platform-safe collections call) sidesteps the
      * dereference entirely and stays genuinely cross-platform.
      */
    private def machineKeys(): Set[List[String]] =
        val registry = kyo.stats.internal.StatsRegistry.internal
        val keys     = collection.mutable.HashSet.empty[List[String]]
        def collect(map: java.util.Map[List[String], ?]): Unit =
            map.keySet().forEach { k =>
                if k.headOption.contains("machine") then discard(keys.add(k))
            }
        collect(registry.histograms.map)
        collect(registry.counters.map)
        collect(registry.counterGauges.map)
        collect(registry.gauges.map)
        keys.toSet
    end machineKeys

    // Value readers for a metric path (scope, family, leaf), mirroring the other suites' helpers. The
    // WeakReference dereference these perform is JVM-only, so their callers are `.onlyJvm`-gated.
    private def gaugePath(path: String*): Double =
        StatsRegistry.internal.gauges.get(path.toList.reverse, "", new UnsafeGauge(() => -1d)).collect()

    private def histogramSummary(path: String*): Summary =
        StatsRegistry.internal.histograms.get(path.toList.reverse, "", new UnsafeHistogram(Array(0d))).summary()

    private def histogramRegistered(path: String*): Boolean =
        StatsRegistry.internal.histograms.map.containsKey(path.toList)

    "NullMachine read/readDisks/close are no-ops that register zero machine.* series and never throw" in {
        // Captured back-to-back on one thread with no suspension in between, the same zero-window idiom
        // the module's other shared-scope suites rely on: NullMachine's three calls are the ONLY thing
        // that can happen in that window.
        val before = machineKeys()
        Machine.NullMachine.read()
        Machine.NullMachine.readDisks()
        Machine.NullMachine.close()
        val after = machineKeys()
        assert(after == before)
    }

    "the fully assembled host reader runs one tick and populates every family the host produces (standing-invariant re-verify anchor)".onlyJvm in {
        val hostOs = System.live.unsafe.operatingSystem()
        assume(
            hostOs == System.OS.Linux || hostOs == System.OS.MacOS || hostOs == System.OS.Windows,
            "this leaf drives the fully assembled per-OS reader and needs a host OS with a dedicated Machine implementation"
        )
        def observationsOf(readings: Chunk[MachineRegistrySnapshot.Reading], path: String): Long =
            readings.find(_.path == path).map(_.observations).getOrElse(0L)
        for
            handles <- MachineHandles.init
            sampler = new MachineSampler(handles)
            machine = Machine.forOs(hostOs, handles, sampler)
            // Baseline tick: a RateCell's first-ever observe call only establishes its prior value, with
            // no observation recorded (RateCell.observe, MachineHandles.scala), so a lone measured tick
            // on a freshly constructed reader would never advance any observation count. This baseline
            // makes the MEASURED tick below the one that genuinely records.
            _              = machine.read()
            _              = machine.readDisks()
            before         = MachineRegistrySnapshot.read
            cpuCountBefore = observationsOf(before, "machine.cpu.total.rate")
            memCountBefore = observationsOf(before, "machine.memory.available")
            _              = machine.read()
            _              = machine.readDisks()
            after          = MachineRegistrySnapshot.read
        yield
            assert(observationsOf(after, "machine.cpu.total.rate") > cpuCountBefore)
            assert(observationsOf(after, "machine.memory.available") > memCountBefore)
            val cpuCores = after.find(_.path == "machine.cpu.cores")
            assert(cpuCores.exists(_.value > 0.0))
            if hostOs == System.OS.Linux || hostOs == System.OS.MacOS then
                assert(after.exists(_.path == "machine.load.one"))
            if hostOs == System.OS.Linux then
                assert(after.exists(_.path.startsWith("machine.cgroup.")))
                assert(after.exists(p => p.path.startsWith("machine.pressure.") || p.path.startsWith("machine.cgroup.pressure.")))
            end if
            assert(after.exists(_.path.startsWith("machine.disk.")))
        end for
    }

    "the assembled host reader wires memory.total in bytes: a plausible RAM total dominating available and free".onlyJvm in {
        val hostOs = System.live.unsafe.operatingSystem()
        assume(
            hostOs == System.OS.Linux || hostOs == System.OS.MacOS || hostOs == System.OS.Windows,
            "this leaf drives the fully assembled per-OS reader and needs a host OS with a dedicated Machine implementation"
        )
        // A uniquely-scoped MachineHandles keeps this real-host read's gauge writes out of the shared "machine"
        // scope, so the absolute memory.total value read back is this reader's own and cannot be poisoned by a
        // sibling suite writing the same first-registered process-global cell.
        val scope   = "mtest-hostmem-floor"
        val handles = MachineHandles.initForTest(Stat.initScope(scope), System.live.unsafe.availableProcessors().toLong)
        val sampler = new MachineSampler(handles)
        val machine = Machine.forOs(hostOs, handles, sampler)
        machine.read()
        val total = gaugePath(scope, "memory", "total")
        // A real host reports more than 256 MiB of total RAM. This is the unit-scaling guard: a KB-vs-bytes
        // wiring error (a /proc/meminfo KiB figure read as bytes, or a dropped x1024) lands far below it.
        assert(total >= 268435456.0)
        // Total RAM dominates the available and free levels the same read produced, where the OS exposes them.
        if histogramRegistered(scope, "memory", "available") then
            assert(total >= histogramSummary(scope, "memory", "available").sum)
        if histogramRegistered(scope, "memory", "free") then
            assert(total >= histogramSummary(scope, "memory", "free").sum)
    }

end MachineTest
