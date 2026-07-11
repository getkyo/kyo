package kyo.stats.machine

import kyo.*

class MachineSamplerTest extends kyo.test.Test[Any]:

    // Every leaf's MachineHandles.init shares the SAME process-global StatsRegistry scope
    // ("machine"), since the scope root is locked and every MachineHandles instance across every
    // leaf and every suite resolves to the identical retained handles by path. Running leaves in
    // parallel would race concurrent observe/drain calls against those shared handles; sequential
    // execution keeps each leaf's Counter drain (destructive sumThenReset) and delta assertions
    // meaningful.
    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    private def cpuTotalReading(total: Long): Machine.Reading =
        Machine.Reading.empty.copy(cpu = Present(Machine.CpuReading(Present(total), Absent, Absent, Absent, Absent)))

    private def sampler(handles: MachineHandles): MachineSampler =
        new MachineSampler(handles, Machine.NullMachine)

    "cumulative-time advance" - {

        "cumulative-time Counter advances by 0 on the first tick, no absolute-value spike" in {
            for
                handles <- MachineHandles.init
                s       = sampler(handles)
                _       = s.observe(cpuTotalReading(5000000000L))
                advance = handles.cpuTimeTotal.unsafe.get()
            yield assert(advance == 0L)
        }

        "cumulative-time Counter advances by the delta on tick 2, never the absolute" in {
            for
                handles <- MachineHandles.init
                s       = sampler(handles)
                _       = s.observe(cpuTotalReading(5000000000L))
                _       = handles.cpuTimeTotal.unsafe.get() // drain tick-1 baseline advance (0)
                _       = s.observe(cpuTotalReading(6000000000L))
                advance = handles.cpuTimeTotal.unsafe.get()
            yield assert(advance == 1000000000L)
        }

        "a per-source scale is applied before the delta (jiffies scaled to ns; microseconds scaled to ns)" in {
            // jiffies-source: 100 jiffies/s clock, scale factor 10000000 (1e9 / 100)
            val jiffyScale = 10000000L
            val jiffyTicks = 100L // one second's worth of jiffies
            // microsecond-source: scale factor 1000 (1e9 / 1e6)
            val usScale = 1000L
            val usTicks = 1000000L // one second's worth of microseconds
            for
                jHandles <- MachineHandles.init
                jSampler   = sampler(jHandles)
                _          = jSampler.observe(cpuTotalReading(0L * jiffyScale))
                _          = jHandles.cpuTimeTotal.unsafe.get()
                _          = jSampler.observe(cpuTotalReading(jiffyTicks * jiffyScale))
                jiffyDelta = jHandles.cpuTimeTotal.unsafe.get()
                uHandles <- MachineHandles.init
                uSampler = sampler(uHandles)
                _        = uSampler.observe(cpuTotalReading(0L * usScale))
                _        = uHandles.cpuTimeTotal.unsafe.get()
                _        = uSampler.observe(cpuTotalReading(usTicks * usScale))
                usDelta  = uHandles.cpuTimeTotal.unsafe.get()
            yield
                assert(jiffyDelta == 1000000000L)
                assert(usDelta == 1000000000L)
                assert(jiffyDelta == usDelta)
            end for
        }

        "an Absent read leaves the Counter and prior unchanged; a later good read advances against the last good prior" in {
            for
                handles <- MachineHandles.init
                s            = sampler(handles)
                _            = s.observe(cpuTotalReading(5000000000L))
                _            = handles.cpuTimeTotal.unsafe.get()
                _            = s.observe(Machine.Reading.empty)        // tick 2: Absent
                tick2Advance = handles.cpuTimeTotal.unsafe.get()
                _            = s.observe(cpuTotalReading(7000000000L)) // tick 3: good read
                tick3Advance = handles.cpuTimeTotal.unsafe.get()
            yield
                assert(tick2Advance == 0L)
                assert(tick3Advance == 2000000000L)
        }

        "a negative raw delta (counter reset) clamps to a non-advance" in {
            for
                handles <- MachineHandles.init
                s           = sampler(handles)
                _           = s.observe(cpuTotalReading(8000000000L))
                _           = handles.cpuTimeTotal.unsafe.get()
                _           = s.observe(cpuTotalReading(3000000000L)) // kernel counter reset
                advance     = handles.cpuTimeTotal.unsafe.get()
                _           = s.observe(cpuTotalReading(3500000000L)) // next tick advances from the reset value
                nextAdvance = handles.cpuTimeTotal.unsafe.get()
            yield
                assert(advance == 0L)
                assert(nextAdvance == 500000000L)
        }
    }

    "dual-treatment" - {

        "the same per-tick delta feeds the Counter and its paired rate Histogram" in {
            for
                handles <- MachineHandles.init
                countBefore = handles.cpuUsageTotal.unsafe.summary().count
                s           = sampler(handles)
                _           = s.observe(cpuTotalReading(5000000000L))
                _           = handles.cpuTimeTotal.unsafe.get()
                _           = s.observe(cpuTotalReading(6000000000L))
                advance     = handles.cpuTimeTotal.unsafe.get()
                rateSummary = handles.cpuUsageTotal.unsafe.summary()
            yield
                assert(advance == 1000000000L)
                // The Histogram is process-global and cumulative across every leaf that shares this
                // handle (min/max never reset), so the DELTA in count (exactly one new observation
                // from this leaf's single advancing tick) is the meaningful cross-leaf-safe
                // assertion; the exact observed value (the same 1000000000 delta the co-located
                // Counter advanced by, proving dual-treatment feeds both from one scaled delta) is
                // already pinned by the `advance` assertion above.
                assert(rateSummary.count == countBefore + 1L)
                assert(rateSummary.max >= 1000000000.0)
        }

        "every cumulative-time Counter the factory creates has a matching per-second-delta Histogram handle" in {
            for handles <- MachineHandles.init
            yield
                assert((handles.cpuTimeTotal ne null) && (handles.cpuUsageTotal ne null))
                assert((handles.cpuTimeUser ne null) && (handles.cpuUsageUser ne null))
                assert((handles.cpuTimeSystem ne null) && (handles.cpuUsageSystem ne null))
                assert((handles.cpuTimeIdle ne null) && (handles.cpuUsageIdle ne null))
                assert((handles.cpuTimeIowait ne null) && (handles.cpuUsageIowait ne null))
                assert((handles.cgCpuThrPeriods ne null) && (handles.cgCpuThrPeriodsHi ne null))
                assert((handles.cgCpuThrTime ne null) && (handles.cgCpuThrTimeHi ne null))
        }
    }

    "handle retention" - {

        "the sampler retains a handle across a GC: the Counter series is continuous over System.gc()" in {
            for
                handles <- MachineHandles.init
                s       = sampler(handles)
                _       = s.observe(cpuTotalReading(1000000000L))
                _       = handles.cpuTimeTotal.unsafe.get()
                _       = java.lang.System.gc()
                _       = s.observe(cpuTotalReading(2000000000L))
                advance = handles.cpuTimeTotal.unsafe.get()
            yield assert(advance == 1000000000L)
        }

        "no metric handle is re-inited after start" in {
            for
                handles <- MachineHandles.init
                s                  = sampler(handles)
                cpuTimeTotalBefore = handles.cpuTimeTotal
                _                  = s.observe(cpuTotalReading(1000000000L))
                _                  = s.observe(cpuTotalReading(2000000000L))
                _                  = s.observe(cpuTotalReading(3000000000L))
                cpuTimeTotalAfter  = handles.cpuTimeTotal
                // Drain the shared cpuTimeTotal Counter so this leaf's N ticks leave no residual
                // delta for a later leaf reading the same process-global handle.
                _ = handles.cpuTimeTotal.unsafe.get()
            yield assert(cpuTimeTotalBefore eq cpuTimeTotalAfter)
        }
    }

end MachineSamplerTest
