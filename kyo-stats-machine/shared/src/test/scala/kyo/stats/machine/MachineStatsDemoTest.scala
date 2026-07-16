package kyo.stats.machine

import kyo.*

class MachineStatsDemoTest extends kyo.test.Test[Any]:

    // Every leaf's MachineHandles.init shares the SAME process-global StatsRegistry scope ("machine")
    // every other suite's own MachineHandles.init resolves to; both leaves below read before/after
    // registry deltas rather than absolute counts, so a concurrently-running sibling suite's own
    // observations cannot corrupt them.
    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    private val cpuAdvancePollInterval = 100.millis
    private val cpuAdvanceMaxAttempts  = 20

    private def cpuTotalSum: Double =
        MachineRegistrySnapshot.read.find(_.path == "machine.cpu.total.rate").map(_.sum).getOrElse(0.0)

    /** Retries `machine.read()` with a short real wait between attempts until cpu.total.rate's cumulative
      * sum advances past `baseline`, up to `maxAttempts` tries. The host's cpu-time accounting is
      * tick-quantized, so a single fixed wait can occasionally straddle two reads inside the same quantum
      * and observe a genuine zero delta; retrying widens the wall-clock window without ever accepting a
      * value the host did not actually produce.
      */
    private def awaitCpuAdvance(machine: Machine, baseline: Double, maxAttempts: Int)(using Frame): Double < Async =
        Loop(maxAttempts): remaining =>
            for
                _   <- Async.sleep(cpuAdvancePollInterval)
                sum <- Sync.Unsafe.defer { machine.read(); cpuTotalSum }
            yield
                if sum > baseline || remaining <= 1 then Loop.done(sum)
                else Loop.continue(remaining - 1)

    "the demo acceptance holds as before/after registry deltas under a sequential run with auto-start opted out".onlyJvm in {
        val hostOs = System.live.unsafe.operatingSystem()
        assume(
            hostOs == System.OS.Linux || hostOs == System.OS.MacOS || hostOs == System.OS.Windows,
            "this leaf drives the fully assembled per-OS reader and needs a supported host OS"
        )
        for
            handles <- MachineHandles.init
            sampler = new MachineSampler(handles)
            machine = Machine.forOs(hostOs, handles, sampler)
            // Baseline tick: establishes every RateCell's prior with no observation recorded yet, so the
            // measured tick(s) below are the sole contributor to the cpu.total.rate observation delta.
            _              = machine.read()
            _              = machine.readDisks()
            before         = MachineRegistrySnapshot.read
            cpuCountBefore = before.find(_.path == "machine.cpu.total.rate").map(_.observations).getOrElse(0L)
            cpuSumBefore   = before.find(_.path == "machine.cpu.total.rate").map(_.sum).getOrElse(0.0)
            _ <- awaitCpuAdvance(machine, cpuSumBefore, cpuAdvanceMaxAttempts)
            _     = machine.readDisks()
            after = MachineRegistrySnapshot.read
        yield
            val cpuCountAfter = after.find(_.path == "machine.cpu.total.rate").map(_.observations).getOrElse(0L)
            // One or more retried ticks, not necessarily exactly one, contribute the delta.
            assert(cpuCountAfter - cpuCountBefore >= 1L)
            assert(after.exists(_.path == "machine.memory.available"))
            assert(after.exists(_.path == "machine.memory.total"))
            assert(after.exists(_.path.startsWith("machine.disk.")))
            val report = demo.MachineStatsDemo.report(hostOs.toString, after)
            assert(demo.MachineStatsDemo.validate(report).isEmpty)
        end for
    }

    "a real-host leaf asserts host-invariant properties only, never a runner-specific value".onlyJvm in {
        val hostOs = System.live.unsafe.operatingSystem()
        assume(
            hostOs == System.OS.Linux || hostOs == System.OS.MacOS || hostOs == System.OS.Windows,
            "this leaf drives the fully assembled per-OS reader against the real, unmocked host"
        )
        for
            handles <- MachineHandles.init
            sampler = new MachineSampler(handles)
            machine = Machine.forOs(hostOs, handles, sampler)
            _       = machine.read()
            _       = machine.readDisks()
            first   = MachineRegistrySnapshot.read
            _       = machine.read()
            _       = machine.readDisks()
            second  = MachineRegistrySnapshot.read
        yield
            def cpuCores(readings: Chunk[MachineRegistrySnapshot.Reading]): Double =
                readings.find(_.path == "machine.cpu.cores").map(_.value).getOrElse(0.0)
            def cpuTotalSum(readings: Chunk[MachineRegistrySnapshot.Reading]): Double =
                readings.find(_.path == "machine.cpu.total.rate").map(_.sum).getOrElse(0.0)
            def memoryTotal(readings: Chunk[MachineRegistrySnapshot.Reading]): Double =
                readings.find(_.path == "machine.memory.total").map(_.value).getOrElse(0.0)
            assert(cpuCores(second) > 0.0)
            assert(cpuTotalSum(second) >= cpuTotalSum(first))
            assert(memoryTotal(second) >= 268435456.0)
            assert(second.exists(_.path.startsWith("machine.disk.")))
        end for
    }

end MachineStatsDemoTest
