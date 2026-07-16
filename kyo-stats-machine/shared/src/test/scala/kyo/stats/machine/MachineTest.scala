package kyo.stats.machine

import kyo.*

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

end MachineTest
