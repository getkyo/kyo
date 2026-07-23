package kyo.stats.machine

import kyo.*
import kyo.ffi.*
import kyo.stats.internal.StatsRegistry
import kyo.stats.internal.Summary
import kyo.stats.internal.UnsafeGauge
import kyo.stats.internal.UnsafeHistogram
import kyo.test.AllocationCounter
import kyo.test.AllocationProbe
import kyo.test.AssertionFailed

class MachineSamplerJvmTest extends kyo.test.Test[Any]:

    // Every leaf's MachineHandles.init or initForTest shares process-global state (either the well-known
    // "machine" scope or this suite's own isolated scopes), and the allocation probe's measured window must
    // run with no sibling leaf writing into the same cells concurrently: sequential execution keeps the
    // per-op byte measurement free of a concurrent leaf's own allocation.
    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    private val warmupIters   = 20000
    private val measuredIters = 2000

    // AllocationProbe checks the best of several independent post-warmup measurement windows, so the
    // measured op runs warmupIters + probeTrials * measuredIters times total; a leaf that counts total
    // invocations through a side effect (a RateCell's histogram count, here) must account for every window.
    private val probeTrials = 5

    private def histogramSummary(path: String*): Summary =
        StatsRegistry.internal.histograms.get(path.toList.reverse, "", new UnsafeHistogram(Array(0d))).summary()

    private def gaugePath(path: String*): Double =
        StatsRegistry.internal.gauges.get(path.toList.reverse, "", new UnsafeGauge(() => -1d)).collect()

    /** A stub `MacosBindings` whose every method is overridable per test, defaulting to failure codes so an
      * un-stubbed call surfaces as an obvious Absent rather than a silent success. A local copy of the same
      * idiom `MachineMacosTest` and `MachineWindowsTest` each define for themselves.
      */
    private class StubMacosBindings extends MacosBindings:
        var hostCpuLoadFn: Buffer[Long] => Int         = _ => 1
        var vmStatisticsFn: Buffer[Long] => Int        = _ => 1
        var swapUsageFn: Buffer[Long] => Int           = _ => 1
        var getloadavgFn: (Buffer[Double], Int) => Int = (_, _) => 0
        var mountsFn: (Buffer[Byte], Int) => Int       = (_, _) => 0
        var statfsFn: (String, Buffer[Long]) => Int    = (_, _) => 1

        def hostCpuLoad(out: Buffer[Long])(using AllowUnsafe): Int          = hostCpuLoadFn(out)
        def vmStatistics(out: Buffer[Long])(using AllowUnsafe): Int         = vmStatisticsFn(out)
        def swapUsage(out: Buffer[Long])(using AllowUnsafe): Int            = swapUsageFn(out)
        def getloadavg(out: Buffer[Double], n: Int)(using AllowUnsafe): Int = getloadavgFn(out, n)
        def mounts(out: Buffer[Byte], cap: Int)(using AllowUnsafe): Int     = mountsFn(out, cap)
        def statfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int = statfsFn(path, out)
    end StubMacosBindings

    private class StubWindowsBindings extends WindowsBindings:
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
    end StubWindowsBindings

    /** The macOS leg's op: the reader's real cpu-plus-memory decode against retained out-buffers and a
      * stubbed FFI response, never a length-only or identity callback.
      */
    private def realDecodeObserve(machine: MachineMacos, bindings: MacosBindings): Unit =
        machine.readCpu(bindings)
        machine.readMemory(bindings)
        machine.readSwap(bindings)
        machine.readLoad(bindings)
    end realDecodeObserve

    /** The Windows leg's op: the reader's real cpu-plus-memory decode against retained out-buffers and a
      * stubbed FFI response.
      */
    private def realDecodeObserve(machine: MachineWindows, bindings: WindowsBindings): Unit =
        machine.readCpu(bindings)
        machine.readMemoryAndSwap(bindings)
    end realDecodeObserve

    /** The Linux leg's op: the reader's real decode against a retained `FileSlot`, through the same
      * `sampler.readInto` production seam `MachineLinux.read()` drives every tick.
      */
    private def realDecodeObserve(
        sampler: MachineSampler,
        slot: Maybe[MachineSampler.FileSlot],
        decode: MachineSampler.Decode
    ): Unit =
        discard(sampler.readInto(slot, decode))

    "the steady-state per-OS decode+observe allocates exactly 0 bytes per op".onlyJvm in {
        val macosHandles = MachineHandles.initForTest(Stat.initScope("mstest-alloc-macos"), 8L)
        val macosSampler = new MachineSampler(macosHandles)
        val macosMachine = new MachineMacos(macosHandles, macosSampler)
        val macosStub    = new StubMacosBindings
        macosStub.hostCpuLoadFn = out =>
            out.setLong(0, 1000000000L); out.setLong(1, 2000000000L); out.setLong(2, 7000000000L); out.setLong(3, 300000000L); 0
        macosStub.vmStatisticsFn = out =>
            out.setLong(0, 17179869184L); out.setLong(1, 2147483648L); out.setLong(2, 6442450944L); 0
        macosStub.swapUsageFn = out =>
            out.setLong(0, 4294967296L); out.setLong(1, 1073741824L); 0
        macosStub.getloadavgFn = (out, n) =>
            out.setDouble(0, 1.5); out.setDouble(1, 2.5); out.setDouble(2, 3.5); n
        AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0)(realDecodeObserve(macosMachine, macosStub))

        val windowsHandles = MachineHandles.initForTest(Stat.initScope("mstest-alloc-windows"), 8L)
        val windowsSampler = new MachineSampler(windowsHandles)
        val windowsMachine = new MachineWindows(windowsHandles, windowsSampler)
        val windowsStub    = new StubWindowsBindings
        windowsStub.getSystemTimesFn = (idle, kernel, user) =>
            idle.setLong(0, 1000000L); kernel.setLong(0, 3000000L); user.setLong(0, 2000000L); 1
        windowsStub.globalMemoryStatusFn = out =>
            out.setLong(1, 17179869184L); out.setLong(2, 6442450944L); out.setLong(3, 25769803776L); out.setLong(4, 12884901888L); 1
        AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0)(
            realDecodeObserve(windowsMachine, windowsStub)
        )

        // A uniquely-scoped MachineHandles, not the shared "machine" root MachineHandles.init resolves to:
        // the measured op below calls LinuxDecoders.meminfo tens of thousands of times (warmup plus every
        // probe trial), and each call writes this fixture's memTotal into the retained LongGaugeCell;
        // against the shared scope that would poison "machine.memory.total" for every other suite's later
        // poll of the same well-known path (a real-host acceptance leaf among them).
        for
            dir <- Path.tempDir("kyo-stats-machine-allocprobe-linux")
            handles  = MachineHandles.initForTest(Stat.initScope("mstest-alloc-linux"), 8L)
            statFile = dir / "stat"
            memFile  = dir / "meminfo"
            _ <- statFile.write("cpu 100 20 30 40 50 6 7 80\n")
            _ <- memFile.write(
                "MemTotal:       16384 kB\nMemAvailable:    8192 kB\nMemFree:  4096 kB\nSwapTotal:  2048 kB\nSwapFree: 1024 kB\n"
            )
            sampler  = new MachineSampler(handles)
            statSlot = sampler.openSlot(statFile)
            memSlot  = sampler.openSlot(memFile)
            decodeCpu = new MachineSampler.Decode:
                def apply(b: Span[Byte], n: Int)(using AllowUnsafe): Unit = LinuxDecoders.cpu(b, n, 1L, handles)
            decodeMem = new MachineSampler.Decode:
                def apply(b: Span[Byte], n: Int)(using AllowUnsafe): Unit = LinuxDecoders.meminfo(b, n, handles)
            _ = AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0) {
                realDecodeObserve(sampler, statSlot, decodeCpu)
                realDecodeObserve(sampler, memSlot, decodeMem)
            }
            _ <- dir.removeAll
        yield ()
        end for
    }

    "the steady-state disk read on an unchanged mount table allocates exactly 0 bytes per op".onlyJvm in {
        val handles = MachineHandles.initForTest(Stat.initScope("mstest-alloc-disk-macos"), 8L)
        val disk    = new MacosDisk(handles)
        // A direct trait implementation, not StubMacosBindings's mutable-Function2-field idiom: calling
        // through a Function2 field boxes a primitive Int argument outside the JVM's small-integer cache
        // (MacosDisk.MountsCap is 65536), which would measure the STUB's own call overhead rather than
        // MacosDisk's. The enumeration buffer is written ONCE, on the first call (inside the probe's own
        // warmup), with a single fixed mount pair; every call after that returns the same count without
        // rewriting the buffer, exactly matching a real host whose mount table does not change between reads.
        val mountsWritten = new Array[Boolean](1)
        val stub = new MacosBindings:
            def hostCpuLoad(out: Buffer[Long])(using AllowUnsafe): Int          = 1
            def vmStatistics(out: Buffer[Long])(using AllowUnsafe): Int         = 1
            def swapUsage(out: Buffer[Long])(using AllowUnsafe): Int            = 1
            def getloadavg(out: Buffer[Double], n: Int)(using AllowUnsafe): Int = 0
            def mounts(out: Buffer[Byte], cap: Int)(using AllowUnsafe): Int =
                if !mountsWritten(0) then
                    MachineSamplerJvmTest.rootApfsPair.zipWithIndex.foreach { case (b, i) => out.set(i, b) }
                    mountsWritten(0) = true
                end if
                1
            end mounts
            def statfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int =
                out.setLong(0, 1000000000L)
                out.setLong(1, 400000000L)
                0
            end statfs
        AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0)(disk.read(stub))
    }

    "the Windows steady-state disk read on an unchanged drive set allocates exactly 0 bytes per op".onlyJvm in {
        val handles = MachineHandles.initForTest(Stat.initScope("mstest-alloc-disk-windows"), 8L)
        val disk    = new WindowsDisk(handles)
        // A direct trait implementation, not StubWindowsBindings's mutable-Function2-field idiom, the same
        // choice the macOS and Linux disk leaves above make for this measured-window leaf.
        val stub = new WindowsBindings:
            def getSystemTimes(idle: Buffer[Long], kernel: Buffer[Long], user: Buffer[Long])(using AllowUnsafe): Int = 0
            def globalMemoryStatus(out: Buffer[Long])(using AllowUnsafe): Int                                        = 0
            def getLogicalDrives()(using AllowUnsafe): Int                                                           = 1 << 2 // C: only
            def getDriveType(root: String)(using AllowUnsafe): Int = WindowsBindings.DriveFixed
            def diskFreeSpace(
                drive: String,
                availToCaller: Buffer[Long],
                total: Buffer[Long],
                totalFree: Buffer[Long]
            )(using AllowUnsafe): Int =
                total.setLong(0, 1000000000L)
                totalFree.setLong(0, 400000000L)
                1
            end diskFreeSpace
        AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0)(disk.read(stub))
        // Revert-and-fail proof: a degenerate measured op that never drove the real production read would
        // leave the "C:\" store's cells unregistered, so gaugePath's -1d sentinel would fail this assertion
        // rather than pass silently.
        assert(gaugePath("mstest-alloc-disk-windows", "disk", "C:\\", "total") == 1000000000.0)
    }

    "the Linux steady-state disk read on an unchanged mount table allocates exactly 0 bytes per op, and a mount-table change rebuilds the retained store set".onlyJvm in {
        val stub = new LinuxBindings:
            def statvfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int =
                out.setLong(1, 4096L)
                out.setLong(2, 1000000L)
                out.setLong(4, 250000L)
                0
            end statvfs
            def sysconf(name: Int)(using AllowUnsafe): Long = 100L
        for
            dir <- Path.tempDir("kyo-stats-machine-allocprobe-disk-linux")
            mountsFile = dir / "mounts"
            _ <- mountsFile.write("/dev/sda1 / ext4 rw 0 0\n")
            handles = MachineHandles.initForTest(Stat.initScope("mstest-alloc-disk-linux"), 8L)
            sampler = new MachineSampler(handles)
            disk    = new LinuxDisk(handles, sampler, mountsFile)
            _       = AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0)(disk.read(Present(stub)))
            // Revert-and-fail proof: a degenerate measured op that never drove the real production read
            // would leave the "root" store's cells unregistered, so gaugePath's -1d sentinel would fail this
            // assertion rather than pass silently.
            _ = assert(gaugePath("mstest-alloc-disk-linux", "disk", "root", "total") == 4096000000.0)
            // Exercises the mount-change rebuild branch the steady read above never touches: a byte-differing
            // mounts file fails decodeMounts's fingerprint check, forcing a real re-parse that registers a
            // retained Store (and its cells) for the newly-added mount.
            _ <- mountsFile.write("/dev/sda1 / ext4 rw 0 0\n/dev/sdb1 /data ext4 rw 0 0\n")
            _ = disk.read(Present(stub))
            _ = assert(gaugePath("mstest-alloc-disk-linux", "disk", "data", "total") == 4096000000.0)
            _ <- dir.removeAll
        yield ()
        end for
    }

    "the probe op is the reader's REAL decode+observe, not a degenerate callback, and an unsupported counter fails loud".onlyJvm in {
        val unsupported = new AllocationCounter:
            def isSupported: Boolean                = false
            def isEnabled: Boolean                  = false
            def enable(): Unit                      = ()
            def currentThreadAllocatedBytes(): Long = 0L
        val acc = new Array[Long](1)
        val failure = intercept[AssertionFailed] {
            AllocationProbe.assertBoundedPerOp(unsupported, warmupIters, measuredIters, 0.0) {
                acc(0) = acc(0) + 1L
            }
        }
        assert(failure.diagram.contains("per-thread allocation measurement is unsupported"))

        for
            dir <- Path.tempDir("kyo-stats-machine-allocprobe-substance")
            baselineFile = dir / "stat-baseline"
            tickFile     = dir / "stat-tick"
            _ <- baselineFile.write("cpu 0 0 0 0 0 0 0 0\n")
            _ <- tickFile.write("cpu 100 20 30 40 50 6 7 80\n")
            isolated     = MachineHandles.initForTest(Stat.initScope("mstest-substance"), 8L)
            sampler      = new MachineSampler(isolated)
            baselineSlot = sampler.openSlot(baselineFile)
            tickSlot     = sampler.openSlot(tickFile)
            decode = new MachineSampler.Decode:
                def apply(b: Span[Byte], n: Int)(using AllowUnsafe): Unit = LinuxDecoders.cpu(b, n, 1L, isolated)
            // Baselines the RateCell's prior with no observation recorded yet (RateCell's first-ever
            // observe call only baselines), so the FIRST probe-driven call below records the one genuine
            // delta and every call after it (identical tick bytes) records a clamped-to-zero delta.
            _ = realDecodeObserve(sampler, baselineSlot, decode)
            _ = AllocationProbe.assertBoundedPerOp(warmupIters, measuredIters, 0.0)(
                realDecodeObserve(sampler, tickSlot, decode)
            )
            _ <- dir.removeAll
        yield
            val summary = histogramSummary("mstest-substance", "cpu", "total.rate")
            // A degenerate `(_, len) => len` op would never call LinuxDecoders.cpu, leaving this cell
            // unobserved: count would stay 0 and this assertion would fail (the revert-and-fail guard).
            assert(summary.count == (warmupIters + probeTrials * measuredIters).toLong)
            assert(summary.sum == 333.0)
        end for
    }

end MachineSamplerJvmTest

private object MachineSamplerJvmTest:
    // "/" NUL "apfs" NUL, as bytes -- a single physical mount pair, built once at class-init rather than
    // re-encoded per call.
    val rootApfsPair: Array[Byte] =
        "/".getBytes(java.nio.charset.StandardCharsets.US_ASCII) ++ Array[Byte](0) ++
            "apfs".getBytes(java.nio.charset.StandardCharsets.US_ASCII) ++ Array[Byte](0)
end MachineSamplerJvmTest
