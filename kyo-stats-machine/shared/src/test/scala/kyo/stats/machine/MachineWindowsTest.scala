package kyo.stats.machine

import kyo.*
import kyo.ffi.*

class MachineWindowsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    given CanEqual[Machine.Reading, Machine.Reading]             = CanEqual.derived
    given CanEqual[Machine.DiskReading, Machine.DiskReading]     = CanEqual.derived
    given CanEqual[Machine.CpuReading, Machine.CpuReading]       = CanEqual.derived
    given CanEqual[Machine.MemoryReading, Machine.MemoryReading] = CanEqual.derived
    given CanEqual[Machine.SwapReading, Machine.SwapReading]     = CanEqual.derived

    /** A stub `WindowsBindings` whose every method is overridable per test, defaulting to failure codes so
      * an un-stubbed call surfaces as an obvious Absent rather than a silent success.
      */
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

    "GetLogicalDrives bitmask decode and per-drive GetDiskFreeSpaceEx failure isolation" - {

        "the C: and D: fixed roots are enumerated; D: is Absent total/free (skipped), C: recorded" in {
            val stub = new StubBindings
            // bit 2 = C:, bit 3 = D:
            stub.getLogicalDrivesFn = () => (1 << 2) | (1 << 3)
            stub.getDriveTypeFn = _ => WindowsBindings.driveFixed
            stub.diskFreeSpaceFn = (drive, _, totalB, freeB) =>
                if drive == "D:\\" then 0
                else
                    totalB.set(0, 500000000000L); freeB.set(0, 100000000000L)
                    1

            val roots = WindowsDisk.enumerate(stub)
            assert(roots == Chunk("C:\\", "D:\\"))

            val readings = roots.map(d => WindowsDisk.stat(stub, d))
            assert(readings == Chunk(
                Machine.DiskReading("C:\\", Present(500000000000L), Present(100000000000L)),
                Machine.DiskReading("D:\\", Absent, Absent)
            ))
        }

        "a network drive (GetDriveTypeW != DRIVE_FIXED) is filtered out, never probed by GetDiskFreeSpaceExW" in {
            val stub = new StubBindings
            // bit 2 = C: (fixed), bit 25 = Z: (mapped network share). A dead network drive's
            // GetDiskFreeSpaceExW would block, so it must never be enumerated.
            stub.getLogicalDrivesFn = () => (1 << 2) | (1 << 25)
            val driveNetwork = 4
            stub.getDriveTypeFn = root => if root == "Z:\\" then driveNetwork else WindowsBindings.driveFixed
            stub.diskFreeSpaceFn = (drive, _, totalB, freeB) =>
                assert(drive != "Z:\\", "the network drive Z: was probed by GetDiskFreeSpaceExW despite the DRIVE_FIXED filter")
                totalB.set(0, 500000000000L); freeB.set(0, 100000000000L)
                1

            val roots = WindowsDisk.enumerate(stub)
            assert(roots == Chunk("C:\\"))
            val readings = roots.map(d => WindowsDisk.stat(stub, d))
            assert(readings == Chunk(Machine.DiskReading("C:\\", Present(500000000000L), Present(100000000000L))))
        }
    }

    "read degradation off a Windows host" - {

        // Exercises the REAL MachineWindows.read degrade path: off Windows kernel32 is unresolvable, so the
        // binding load or its first symbol lookup fails and every family degrades to Absent (Reading.empty).
        // Gated off a real Windows host, where kernel32 loads and the reading is non-empty, which this
        // degrade leaf is not asserting (mirrors the suite's own real-host gating).
        "off Windows every family degrades to Absent through the production read, no throw" in {
            assume(
                System.live.unsafe.operatingSystem() != System.OS.Windows,
                "kernel32 loads on a real Windows host; this leaf asserts the off-Windows degrade to empty"
            )
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles, MachineWindows)
                reading = MachineWindows.read(sampler)
            yield
                assert(reading == Machine.Reading.empty)
                // The Windows-absent families are Absent regardless of the reading source.
                assert(reading.load == Absent)
                assert(reading.cgroup == Absent)
                assert(reading.pressure == Absent)
                assert(reading.cgroupPressure == Absent)
            end for
        }
    }

    "cpu decode" - {

        "cpu decode: FILETIME 100ns units scale to ns (x100); kernel includes idle, so system = kernel - idle" in {
            val stub = new StubBindings
            stub.getSystemTimesFn = (idle, kernel, user) =>
                idle.set(0, 1000000L); kernel.set(0, 3000000L); user.set(0, 2000000L)
                1
            val result = MachineWindows.readCpu(stub)
            assert(result == Present(Machine.CpuReading(
                total = Present(500000000L),
                user = Present(200000000L),
                system = Present(200000000L),
                idle = Present(100000000L),
                iowait = Absent
            )))
        }

        "a getSystemTimes failure routes the whole reading to Absent" in {
            val stub = new StubBindings
            stub.getSystemTimesFn = (_, _, _) => 0
            assert(MachineWindows.readCpu(stub) == Absent)
        }
    }

    "memory decode" - {

        "memory decode: MEMORYSTATUSEX index 1/2 project to total/available; free is Absent (no Windows concept)" in {
            val stub = new StubBindings
            stub.globalMemoryStatusFn = out =>
                out.set(1, 17179869184L); out.set(2, 6442450944L)
                1
            val result = MachineWindows.readMemory(stub)
            assert(result == Present(Machine.MemoryReading(
                total = Present(17179869184L),
                available = Present(6442450944L),
                free = Absent
            )))
        }

        "a globalMemoryStatus failure routes the whole reading to Absent" in {
            val stub = new StubBindings
            stub.globalMemoryStatusFn = _ => 0
            assert(MachineWindows.readMemory(stub) == Absent)
        }
    }

    "swap decode" - {

        "swap decode: MEMORYSTATUSEX index 3/4 project to the page-file commit limit as total/free" in {
            val stub = new StubBindings
            stub.globalMemoryStatusFn = out =>
                out.set(3, 25769803776L); out.set(4, 12884901888L)
                1
            val result = MachineWindows.readSwap(stub)
            assert(result == Present(Machine.SwapReading(total = Present(25769803776L), free = Present(12884901888L))))
        }

        "a globalMemoryStatus failure routes the whole reading to Absent" in {
            val stub = new StubBindings
            stub.globalMemoryStatusFn = _ => 0
            assert(MachineWindows.readSwap(stub) == Absent)
        }
    }

end MachineWindowsTest
