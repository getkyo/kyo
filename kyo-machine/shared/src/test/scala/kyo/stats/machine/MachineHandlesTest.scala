package kyo.stats.machine

import kyo.*
import kyo.stats.internal.StatsRegistry

class MachineHandlesTest extends kyo.test.Test[Any]:

    // Every leaf's MachineHandles.init shares the SAME process-global StatsRegistry scope
    // ("machine"), since the scope root is locked and every MachineHandles instance across every
    // leaf and every suite resolves to the identical retained handles by path. Running leaves in
    // parallel would race concurrent observe calls against those shared handles; sequential
    // execution keeps each leaf's before/after and delta assertions meaningful.
    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    private def diskReading(store: String, total: Long, free: Long): Machine.Reading =
        Machine.Reading.empty.copy(disks = Chunk(Machine.DiskReading(store, Present(total), Present(free))))

    private def cgroupReading(
        memoryLimit: Maybe[Long] = Absent,
        cpuQuota: Maybe[Long] = Absent,
        cpuPeriod: Maybe[Long] = Absent,
        periods: Maybe[Long] = Absent,
        throttledPeriods: Maybe[Long] = Absent
    ): Machine.Reading =
        Machine.Reading.empty.copy(cgroup =
            Present(Machine.CgroupReading(
                memoryUsage = Absent,
                memoryLimit = memoryLimit,
                cpuQuota = cpuQuota,
                cpuPeriod = cpuPeriod,
                periods = periods,
                throttledPeriods = throttledPeriods,
                throttledTime = Absent
            ))
        )

    "no thread blocking" - {

        "the sampler blocks no thread: N ticks on a single-worker scheduler do not starve other submitted work" in {
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles, Machine.NullMachine)
                coSubmitted <- Fiber.initUnscoped(42)
                _ = (1 to 5).foreach(i => sampler.observe(cpuTotal(i.toLong * 1000000000L)))
                result <- coSubmitted.get
                // Drain the shared cpuTimeTotal Counter (a destructive sumThenReset): this leaf's
                // own N ticks otherwise leave a residual delta that would corrupt a later leaf's
                // (in this suite or another) baseline read of the same process-global handle.
                _ = handles.cpuTimeTotal.unsafe.get()
            yield assert(result == 42)
            end for
        }

        // Reading the module's own source tree is a JVM-only mechanics check (java.io.File /
        // java.nio.file.Files do not exist on JS/Native, and a runtime source-tree read makes no
        // sense off the JVM build layout in any case), the same sanctioned platform-mechanics split
        // PathJvmTest's allocation probes use.
        "no banned blocking construct appears in the module's production sources".onlyJvm in {
            val sources = Seq(
                "MachineSampler.scala",
                "MachineHandles.scala",
                "MachineStatFactory.scala",
                "PsiHandles.scala",
                "Machine.scala"
            )
            val root   = new java.io.File("shared/src/main/scala/kyo/stats/machine")
            val banned = List("Thread.sleep", "synchronized", ".await()", "CountDownLatch")
            sources.foreach { name =>
                val f = new java.io.File(root, name)
                if f.isFile then
                    val text = new String(java.nio.file.Files.readAllBytes(f.toPath), java.nio.charset.StandardCharsets.UTF_8)
                    banned.foreach { token =>
                        assert(!text.contains(token), s"found banned blocking construct '$token' in ${f.getPath}")
                    }
                end if
            }
            succeed
        }
    }

    "detached-fiber lifecycle" - {

        "the sampler fiber survives its triggering call's own scope closing (detached-fiber keep-alive)" in {
            // Mirrors MachineStatFactory.triggerStart's own shape exactly: Scope.run wraps
            // MachineSampler.run INSIDE the detached fiber body, so the sampler's own Scope belongs
            // to the fiber, independent of whatever scope surrounds the Fiber.initUnscoped call
            // itself (the "triggering call's own scope"). Wrapping Scope.run OUTSIDE
            // Fiber.initUnscoped instead would close that scope the instant initUnscoped returns,
            // orphaning the fiber's own Scope.ensure finalizer (observed as a kyo.Closed panic).
            Clock.withTimeControl { tc =>
                for
                    clock <- Clock.get
                    fiber <- Fiber.initUnscoped {
                        Clock.let(clock)(Scope.run(MachineSampler.run))
                    }
                    // The triggering call's own scope (this for-comprehension's ambient scope) is
                    // unrelated to the fiber's own Scope, kept open independently by the tick loop.
                    _     <- tc.advance(1.seconds, Duration.Zero)
                    done1 <- fiber.done
                    _     <- tc.advance(1.seconds, Duration.Zero)
                    done2 <- fiber.done
                    _     <- fiber.interrupt
                yield
                    assert(!done1)
                    assert(!done2)
            }
        }

        "tearing down the sampler stops the tick loop BEFORE closing the handles: no read of a closed handle" in {
            Clock.withTimeControl { tc =>
                for
                    clock <- Clock.get
                    fiber <- Fiber.initUnscoped {
                        Clock.let(clock)(Scope.run(MachineSampler.run))
                    }
                    _           <- tc.advance(1.seconds, Duration.Zero)
                    interrupted <- fiber.interrupt
                    done        <- fiber.done
                yield
                    assert(interrupted)
                    assert(done)
            }
        }
    }

    "metric taxonomy" - {

        "every locked metric handle is created once under Stat scope machine with its declared type; the 3 config-value gauges appear only after their first Present" in {
            for
                handles <- MachineHandles.init
                s                 = new MachineSampler(handles, Machine.NullMachine)
                memoryLimitBefore = StatsRegistry.internal.counterGauges.map.containsKey(List("machine", "cgroup", "memory.limit"))
                cpuQuotaBefore    = StatsRegistry.internal.counterGauges.map.containsKey(List("machine", "cgroup", "cpu.quota"))
                cpuPeriodBefore   = StatsRegistry.internal.counterGauges.map.containsKey(List("machine", "cgroup", "cpu.period"))
                _ = handles.observe(
                    cgroupReading(
                        memoryLimit = Present(1073741824L),
                        cpuQuota = Present(200000000L)
                    ),
                    MachineSampler.PriorState.empty
                )
                memoryLimitAfter = StatsRegistry.internal.counterGauges.map.containsKey(List("machine", "cgroup", "memory.limit"))
                cpuQuotaAfter    = StatsRegistry.internal.counterGauges.map.containsKey(List("machine", "cgroup", "cpu.quota"))
                cpuPeriodAfter   = StatsRegistry.internal.counterGauges.map.containsKey(List("machine", "cgroup", "cpu.period"))
            yield
                assert(!memoryLimitBefore)
                assert(!cpuQuotaBefore)
                assert(!cpuPeriodBefore)
                assert(memoryLimitAfter)
                assert(cpuQuotaAfter)
                assert(!cpuPeriodAfter) // cpuPeriod stayed Absent this tick, so no gauge registers yet
                assert(handles.coresGauge ne null)
                assert(handles.cgMemUsage ne null)
        }

        "storeNames sanitization: root-slash-to-root, dotted-path-to-underscored, nested-path-to-underscored" in {
            val result = MachineHandles.storeNames(Seq("/", "/home/user.name", "/mnt/data"))
            assert(result == Seq("root", "home_user_name", "mnt_data"))
        }

        "storeNames collision gets a stable numeric suffix in enumeration order" in {
            // /mnt/data and /mnt.data both sanitize to the same base segment "mnt_data"
            // ('/' and '.' both map to '_'), a genuine collision on the full sanitized path.
            val result = MachineHandles.storeNames(Seq("/mnt/data", "/mnt.data"))
            assert(result == Seq("mnt_data", "mnt_data_2"))
        }

        "disk observe creates a per-store handle once and records total/free into it each tick" in {
            for
                handles <- MachineHandles.init
                _      = handles.observe(diskReading("data", 1000L, 400L), MachineSampler.PriorState.empty)
                after1 = StatsRegistry.internal.histograms.map.containsKey(List("machine", "disk", "data", "total"))
                _      = handles.observe(diskReading("data", 1000L, 300L), MachineSampler.PriorState.empty)
                summary = StatsRegistry.internal.histograms.get(
                    List("machine", "disk", "data", "total").reverse,
                    "",
                    new kyo.stats.internal.UnsafeHistogram(MachineHandles.bytes)
                )
            yield
                assert(after1)
                // Two ticks each observed total=1000: the SAME retained handle accumulated two observations
                // (never re-created), proving the once-per-store init-once model.
                assert(summary.summary().count == 2L)
        }

        "cgroup config-value CounterGauges are created lazily on first Present; an unset config exports nothing (no fake value)" in {
            // Uses cpu.period exclusively (not memory.limit/cpu.quota, already registered by the
            // "every locked metric handle" leaf earlier in this suite; the registry is process-
            // global, so re-testing the same name's before=false fact there would be stale here).
            for
                handles <- MachineHandles.init
                beforeCpuPeriod     = StatsRegistry.internal.counterGauges.map.containsKey(List("machine", "cgroup", "cpu.period"))
                _                   = handles.observe(cgroupReading(cpuPeriod = Absent), MachineSampler.PriorState.empty)
                afterTick1CpuPeriod = StatsRegistry.internal.counterGauges.map.containsKey(List("machine", "cgroup", "cpu.period"))
                _                   = handles.observe(cgroupReading(cpuPeriod = Present(100000000L)), MachineSampler.PriorState.empty)
                afterTick2CpuPeriod = StatsRegistry.internal.counterGauges.map.containsKey(List("machine", "cgroup", "cpu.period"))
            yield
                assert(!beforeCpuPeriod)
                assert(!afterTick1CpuPeriod)
                assert(afterTick2CpuPeriod)
        }

        "the unpaired cgroup.cpu.periods Counter advances by its own delta and records into no Histogram" in {
            for
                handles <- MachineHandles.init
                throttledRateCountBefore = handles.cgCpuThrPeriodsHi.unsafe.summary().count
                st1                      = handles.observe(cgroupReading(periods = Present(100L)), MachineSampler.PriorState.empty)
                _                        = handles.cgCpuPeriods.unsafe.get() // drain tick-1 baseline (0)
                _                        = handles.observe(cgroupReading(periods = Present(150L)), st1)
                advance                  = handles.cgCpuPeriods.unsafe.get()
                throttledRateCountAfter  = handles.cgCpuThrPeriodsHi.unsafe.summary().count
            yield
                assert(advance == 50L)
                // No new observation into the paired rate Histogram: the count is unchanged across
                // the two ticks (the Histogram is process-global and cumulative, so the DELTA, not
                // the absolute count, is the meaningful assertion here).
                assert(throttledRateCountAfter == throttledRateCountBefore)
        }

        "throttled.periods.rate receives exactly one observation per tick, from the throttledPeriods delta only" in {
            for
                handles <- MachineHandles.init
                countBefore = handles.cgCpuThrPeriodsHi.unsafe.summary().count
                st1 = handles.observe(
                    cgroupReading(periods = Present(100L), throttledPeriods = Present(10L)),
                    MachineSampler.PriorState.empty
                )
                _            = handles.observe(cgroupReading(periods = Present(150L), throttledPeriods = Present(13L)), st1)
                afterSummary = handles.cgCpuThrPeriodsHi.unsafe.summary()
            yield
                // Exactly one new observation across the two ticks (tick 1 records the baseline
                // with no observe; tick 2 observes the throttledPeriods delta of 3).
                assert(afterSummary.count == countBefore + 1L)
                assert(afterSummary.max == 3.0)
        }
    }

    private def cpuTotal(total: Long): Machine.Reading =
        Machine.Reading.empty.copy(cpu = Present(Machine.CpuReading(Present(total), Absent, Absent, Absent, Absent)))

end MachineHandlesTest
