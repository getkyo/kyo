package kyo.stats.machine

import kyo.*
import kyo.ffi.*
import kyo.stats.internal.StatsRegistry
import kyo.stats.internal.Summary
import kyo.stats.internal.UnsafeGauge
import kyo.stats.internal.UnsafeHistogram

class WindowsBindingsTest extends kyo.test.Test[Any]:

    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    private class StubBindings extends WindowsBindings:
        var getSystemTimesFn: (Buffer[Long], Buffer[Long], Buffer[Long]) => Int        = (_, _, _) => 0
        var globalMemoryStatusFn: Buffer[Long] => Int                                  = _ => 0
        var getLogicalDrivesFn: () => Int                                              = () => 0
        var getDriveTypeFn: String => Int                                              = _ => WindowsBindings.driveFixed
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

    "WindowsBindings.symbols" - {

        "binds the ANSI entry points GetDriveTypeA and GetDiskFreeSpaceExA" in {
            assert(WindowsBindings.symbols("getDriveType") == "GetDriveTypeA")
            assert(WindowsBindings.symbols("diskFreeSpace") == "GetDiskFreeSpaceExA")
            assert(!WindowsBindings.symbols("getDriveType").endsWith("W"))
            assert(!WindowsBindings.symbols("diskFreeSpace").endsWith("W"))
        }
    }

    "WindowsDisk.enumerate" - {

        "keeps only DRIVE_FIXED drives and filters a network drive out, never probing it" in {
            val stub = new StubBindings
            stub.getDriveTypeFn = root => if root == "C:\\" then WindowsBindings.driveFixed else 4 // DRIVE_REMOTE
            val roots = WindowsDisk.enumerate(stub, (1 << 2) | (1 << 25)) // bit 2 = C:, bit 25 = Z:
            assert(roots == Chunk("C:\\"))
        }
    }

    "WindowsDisk.diskFreeInto" - {

        "writes total and free straight into the drive's retained cells; a zero return writes nothing" in {
            for handles <- MachineHandles.init
            yield
                val okCell = handles.diskStore("wbtest-diskfreeinto-ok")
                val okStore =
                    new WindowsDisk.Store("wbtest-e:\\", Buffer.alloc[Long](1), Buffer.alloc[Long](1), Buffer.alloc[Long](1), okCell)
                val stubOk = new StubBindings
                stubOk.diskFreeSpaceFn = (_, _, t, f) =>
                    t.set(0, 500000000000L); f.set(0, 100000000000L); 1
                WindowsDisk.diskFreeInto(stubOk, okStore)
                assert(gaugePath("machine", "disk", "wbtest-diskfreeinto-ok", "total") == 500000000000.0)
                assert(histogramSummary("machine", "disk", "wbtest-diskfreeinto-ok", "free").sum == 100000000000.0)

                val zeroCell = handles.diskStore("wbtest-diskfreeinto-zero")
                val zeroStore =
                    new WindowsDisk.Store("wbtest-f:\\", Buffer.alloc[Long](1), Buffer.alloc[Long](1), Buffer.alloc[Long](1), zeroCell)
                val stubZero = new StubBindings
                stubZero.diskFreeSpaceFn = (_, _, _, _) => 0
                WindowsDisk.diskFreeInto(stubZero, zeroStore)
                assert(!gaugeRegistered("machine", "disk", "wbtest-diskfreeinto-zero", "total"))
                okStore.close(); zeroStore.close()
            end for
        }

        "contains a NonFatal throw from the free-space binding and writes nothing" in {
            for handles <- MachineHandles.init
            yield
                val cell  = handles.diskStore("wbtest-nonfatal")
                val store = new WindowsDisk.Store("wbtest-g:\\", Buffer.alloc[Long](1), Buffer.alloc[Long](1), Buffer.alloc[Long](1), cell)
                val stub  = new StubBindings
                stub.diskFreeSpaceFn = (_, _, _, _) => throw new RuntimeException("boom")
                WindowsDisk.diskFreeInto(stub, store) // no exception escapes
                assert(!gaugeRegistered("machine", "disk", "wbtest-nonfatal", "total"))
                store.close()
            end for
        }

        "contains a LinkageError from an unresolved symbol and writes nothing" in {
            for handles <- MachineHandles.init
            yield
                val cell  = handles.diskStore("wbtest-linkage")
                val store = new WindowsDisk.Store("wbtest-h:\\", Buffer.alloc[Long](1), Buffer.alloc[Long](1), Buffer.alloc[Long](1), cell)
                val stub  = new StubBindings
                stub.diskFreeSpaceFn = (_, _, _, _) => throw new LinkageError("unresolved")
                WindowsDisk.diskFreeInto(stub, store) // no throwable escapes (the sanctioned carve-out)
                assert(!gaugeRegistered("machine", "disk", "wbtest-linkage", "total"))
                store.close()
            end for
        }
    }

    "WindowsDisk.read bitmask change" - {

        "a changed GetLogicalDrives bitmask rebuilds the retained store set so a newly-mounted drive is read" in {
            val stub = new StubBindings
            stub.getDriveTypeFn = _ => WindowsBindings.driveFixed
            stub.diskFreeSpaceFn = (_, _, t, f) =>
                t.set(0, 900L); f.set(0, 400L); 1
            for handles <- MachineHandles.init
            yield
                val disk = new WindowsDisk(handles)
                stub.getLogicalDrivesFn = () => 1 << 2 // C: only
                disk.read(stub)
                assert(!gaugeRegistered("machine", "disk", "D:\\", "total"))
                stub.getLogicalDrivesFn = () => (1 << 2) | (1 << 3) // C: and D:
                disk.read(stub)
                assert(gaugePath("machine", "disk", "D:\\", "total") == 900.0)
                disk.close()
            end for
        }
    }

    "off-Windows degrade" - {

        "MachineWindows read and readDisks contain a kernel32 LinkageError on a non-Windows host and register nothing".onlyJvm in {
            assume(
                System.live.unsafe.operatingSystem() != System.OS.Windows,
                "kernel32 loads on a real Windows host; this leaf asserts the off-Windows degrade"
            )
            for
                handles <- MachineHandles.init
                sampler        = new MachineSampler(handles)
                machine        = new MachineWindows(handles, sampler)
                cpuCountBefore = histogramSummary("machine", "cpu", "total.rate").count
                _              = machine.read()
                _              = machine.readDisks()
            yield assert(histogramSummary("machine", "cpu", "total.rate").count == cpuCountBefore)
            end for
        }

        "GetLogicalDrives-backed disk enumeration populates a fixed drive on a real Windows host (held)".onlyJvm in {
            assume(
                System.live.unsafe.operatingSystem() == System.OS.Windows,
                "GetLogicalDrives/GetDiskFreeSpaceExA are Windows-specific"
            )
            val bindings = Ffi.load[WindowsBindings]
            for handles <- MachineHandles.init
            yield
                val disk = new WindowsDisk(handles)
                disk.read(bindings)
                disk.close()
                val anyPositiveTotal = (0 until 26).exists { i =>
                    val letter = ('A' + i).toChar.toString + ":\\"
                    gaugeRegistered("machine", "disk", letter, "total") && gaugePath("machine", "disk", letter, "total") > 0.0
                }
                assert(anyPositiveTotal, "expected at least one machine.disk.<drive>.total row with value > 0 on a real Windows host")
            end for
        }
    }

end WindowsBindingsTest
