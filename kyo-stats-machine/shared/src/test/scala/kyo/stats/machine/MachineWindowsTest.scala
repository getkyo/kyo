package kyo.stats.machine

import kyo.*
import kyo.ffi.*
import kyo.stats.internal.StatsRegistry
import kyo.stats.internal.Summary
import kyo.stats.internal.UnsafeGauge
import kyo.stats.internal.UnsafeHistogram

class MachineWindowsTest extends kyo.test.Test[Any]:

    // Every leaf's MachineHandles.init shares the SAME process-global StatsRegistry scope ("machine"),
    // shared with MachineLinuxTest and MachineMacosTest's own decode leaves. Every assertion below
    // therefore reads a DELTA or a before/after comparison, never an absolute registered/absent fact on
    // a shared path, so a concurrently-running sibling suite's own observations cannot corrupt it.
    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    private class StubBindings extends WindowsBindings:
        var getSystemTimesFn: (Buffer[Long], Buffer[Long], Buffer[Long]) => Int        = (_, _, _) => 0
        var globalMemoryStatusFn: Buffer[Long] => Int                                  = _ => 0
        var getLogicalDrivesFn: () => Int                                              = () => 0
        var getDriveTypeFn: String => Int                                              = _ => WindowsBindings.DriveFixed
        var diskFreeSpaceFn: (String, Buffer[Long], Buffer[Long], Buffer[Long]) => Int = (_, _, _, _) => 0

        def getSystemTimes(idle: Buffer[Long], kernel: Buffer[Long], user: Buffer[Long])(using AllowUnsafe): Int =
            getSystemTimesFn(idle, kernel, user)
        def globalMemoryStatus(out: Buffer[Long])(using AllowUnsafe): Int = globalMemoryStatusFn(out)
        def getLogicalDrives()(using AllowUnsafe): Int                    = getLogicalDrivesFn()
        def getDriveType(root: String)(using AllowUnsafe): Int            = getDriveTypeFn(root)
        def diskFreeSpace(
            drive: String,
            availToCaller: Buffer[Long],
            total: Buffer[Long],
            totalFree: Buffer[Long]
        )(using AllowUnsafe): Int = diskFreeSpaceFn(drive, availToCaller, total, totalFree)
    end StubBindings

    private def gaugePath(path: String*): Double =
        StatsRegistry.internal.gauges.get(path.toList.reverse, "", new UnsafeGauge(() => -1d)).collect()

    private def gaugeRegistered(path: String*): Boolean =
        StatsRegistry.internal.gauges.map.containsKey(path.toList)

    private def histogramSummary(path: String*): Summary =
        StatsRegistry.internal.histograms.get(path.toList.reverse, "", new UnsafeHistogram(Array(0d))).summary()

    "cpu decode" - {

        "scales FILETIME by 100 to ns and computes cpu.system as kernel minus idle" in {
            val stub = new StubBindings
            stub.getSystemTimesFn = (idle, kernel, user) =>
                idle.set(0, 1000000L); kernel.set(0, 3000000L); user.set(0, 2000000L); 1
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles)
                machine = new MachineWindows(handles, sampler)
            yield
                machine.readCpu(stub) // baseline
                val userSumBefore   = histogramSummary("machine", "cpu", "user.rate").sum
                val systemSumBefore = histogramSummary("machine", "cpu", "system.rate").sum
                val idleSumBefore   = histogramSummary("machine", "cpu", "idle.rate").sum
                val totalSumBefore  = histogramSummary("machine", "cpu", "total.rate").sum
                stub.getSystemTimesFn = (idle, kernel, user) =>
                    idle.set(0, 2000000L); kernel.set(0, 6000000L); user.set(0, 4000000L); 1
                machine.readCpu(stub)
                assert(histogramSummary("machine", "cpu", "user.rate").sum - userSumBefore == 200000000.0)
                assert(histogramSummary("machine", "cpu", "system.rate").sum - systemSumBefore == 200000000.0) // (kernel-idle) delta
                assert(histogramSummary("machine", "cpu", "idle.rate").sum - idleSumBefore == 100000000.0)
                assert(histogramSummary("machine", "cpu", "total.rate").sum - totalSumBefore == 500000000.0)
            end for
        }
    }

    "memory and swap decode" - {

        "maps the page-file commit limit to swap.* and never writes memory.free" in {
            val stub = new StubBindings
            stub.globalMemoryStatusFn = out =>
                out.set(1, 17179869184L); out.set(2, 6442450944L); out.set(3, 25769803776L); out.set(4, 12884901888L); 1
            // memTotal/swapTotal are LongGaugeCells rooted at the shared "machine" scope also written by
            // MachineLinuxTest's and MachineMacosTest's own decode leaves; StatsRegistry keeps only the
            // first-ever-registered cell for a path canonical for the process lifetime, so a poll against
            // the shared scope could read a value a different suite registered first. A uniquely-scoped
            // MachineHandles avoids the race entirely.
            val handles            = MachineHandles.initForTest(Stat.initScope("mwintest-memory-swap-decode"), 8L)
            val sampler            = new MachineSampler(handles)
            val machine            = new MachineWindows(handles, sampler)
            val memAvailSumBefore  = histogramSummary("mwintest-memory-swap-decode", "memory", "available").sum
            val memFreeCountBefore = histogramSummary("mwintest-memory-swap-decode", "memory", "free").count
            val swapFreeSumBefore  = histogramSummary("mwintest-memory-swap-decode", "swap", "free").sum
            machine.readMemoryAndSwap(stub)
            assert(gaugePath("mwintest-memory-swap-decode", "memory", "total") == 17179869184.0)
            assert(histogramSummary("mwintest-memory-swap-decode", "memory", "available").sum - memAvailSumBefore == 6442450944.0)
            assert(gaugePath("mwintest-memory-swap-decode", "swap", "total") == 25769803776.0)
            assert(histogramSummary("mwintest-memory-swap-decode", "swap", "free").sum - swapFreeSumBefore == 12884901888.0)
            assert(
                histogramSummary("mwintest-memory-swap-decode", "memory", "free").count == memFreeCountBefore
            ) // never written on Windows
        }
    }

    "family independence" - {

        "a windows-shaped read registers no cpu.steal, cpu.iowait, or load.* series" in {
            val stub = new StubBindings
            stub.getSystemTimesFn = (idle, kernel, user) =>
                idle.set(0, 1L); kernel.set(0, 2L); user.set(0, 1L); 1
            stub.globalMemoryStatusFn = out =>
                out.set(1, 1L); out.set(2, 1L); out.set(3, 1L); out.set(4, 1L); 1
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles)
                machine = new MachineWindows(handles, sampler)
            yield
                val stealCountBefore        = histogramSummary("machine", "cpu", "steal.rate").count
                val iowaitCountBefore       = histogramSummary("machine", "cpu", "iowait.rate").count
                val loadOneRegisteredBefore = gaugeRegistered("machine", "load", "one")
                val loadOnePolledBefore     = if loadOneRegisteredBefore then gaugePath("machine", "load", "one") else Double.NaN
                machine.readCpu(stub); machine.readMemoryAndSwap(stub)
                assert(histogramSummary("machine", "cpu", "steal.rate").count == stealCountBefore)
                assert(histogramSummary("machine", "cpu", "iowait.rate").count == iowaitCountBefore)
                assert(gaugeRegistered("machine", "load", "one") == loadOneRegisteredBefore) // no new registration
                if loadOneRegisteredBefore then assert(gaugePath("machine", "load", "one") == loadOnePolledBefore)
            end for
        }

        "a windows-shaped read registers no cgroup.* or pressure.* (PSI) series" in {
            val stub = new StubBindings
            stub.getSystemTimesFn = (idle, kernel, user) =>
                idle.set(0, 1L); kernel.set(0, 2L); user.set(0, 1L); 1
            stub.globalMemoryStatusFn = out =>
                out.set(1, 1L); out.set(2, 1L); out.set(3, 1L); out.set(4, 1L); 1
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles)
                machine = new MachineWindows(handles, sampler)
            yield
                val cgMemUsageCountBefore    = histogramSummary("machine", "cgroup", "memory.usage").count
                val pressureRegisteredBefore = gaugeRegistered("machine", "pressure", "cpu", "some", "avg10")
                machine.readCpu(stub); machine.readMemoryAndSwap(stub)
                assert(histogramSummary("machine", "cgroup", "memory.usage").count == cgMemUsageCountBefore)
                assert(gaugeRegistered("machine", "pressure", "cpu", "some", "avg10") == pressureRegisteredBefore)
            end for
        }
    }

end MachineWindowsTest
