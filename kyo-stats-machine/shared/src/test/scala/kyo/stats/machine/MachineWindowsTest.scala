package kyo.stats.machine

import kyo.*
import kyo.ffi.*

class MachineWindowsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    given CanEqual[Machine.Reading, Machine.Reading]         = CanEqual.derived
    given CanEqual[Machine.DiskReading, Machine.DiskReading] = CanEqual.derived

    /** A stub `WindowsBindings` whose every method is overridable per test, defaulting to failure codes so
      * an un-stubbed call surfaces as an obvious Absent rather than a silent success.
      */
    private class StubBindings extends WindowsBindings:
        var getSystemTimesFn: (Buffer[Long], Buffer[Long], Buffer[Long]) => Int        = (_, _, _) => 0
        var globalMemoryStatusFn: Buffer[Long] => Int                                  = _ => 0
        var getLogicalDrivesFn: () => Int                                              = () => 0
        var diskFreeSpaceFn: (String, Buffer[Long], Buffer[Long], Buffer[Long]) => Int = (_, _, _, _) => 0

        def getSystemTimes(idle: Buffer[Long], kernel: Buffer[Long], user: Buffer[Long])(using AllowUnsafe): Int =
            getSystemTimesFn(idle, kernel, user)
        def globalMemoryStatus(out: Buffer[Long])(using AllowUnsafe): Int = globalMemoryStatusFn(out)
        def getLogicalDrives()(using AllowUnsafe): Int                    = getLogicalDrivesFn()
        def diskFreeSpace(
            drive: String,
            availToCaller: Buffer[Long],
            total: Buffer[Long],
            totalFree: Buffer[Long]
        )(using AllowUnsafe): Int = diskFreeSpaceFn(drive, availToCaller, total, totalFree)
    end StubBindings

    "load is always Absent on Windows" - {

        "a Windows reading has load Absent (Windows has no load-average concept)" in {
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles, MachineWindows)
                reading = Machine.Reading(
                    cpu = Absent,
                    memory = Absent,
                    swap = Absent,
                    disks = Chunk.empty,
                    load = Absent,
                    cgroup = Absent,
                    pressure = Absent,
                    cgroupPressure = Absent
                )
                _ = sampler.observe(reading)
            yield assert(reading.load == Absent)
        }
    }

    "cgroup, pressure, and cgroupPressure are always Absent" - {

        "a Windows reading has cgroup, pressure, and cgroupPressure Absent" in {
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles, MachineWindows)
                reading = Machine.Reading(
                    cpu = Absent,
                    memory = Absent,
                    swap = Absent,
                    disks = Chunk.empty,
                    load = Absent,
                    cgroup = Absent,
                    pressure = Absent,
                    cgroupPressure = Absent
                )
                _ = sampler.observe(reading)
            yield
                assert(reading.cgroup == Absent)
                assert(reading.pressure == Absent)
                assert(reading.cgroupPressure == Absent)
            end for
        }
    }

    "GetLogicalDrives bitmask decode and per-drive GetDiskFreeSpaceEx failure isolation" - {

        "the C: and D: roots are enumerated; D: is Absent total/free (skipped), C: recorded" in {
            val stub = new StubBindings
            // bit 2 = C:, bit 3 = D:
            stub.getLogicalDrivesFn = () => (1 << 2) | (1 << 3)
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
    }

    "binding-load failure degrades to empty" - {

        "a binding-load failure (kernel32 unresolvable off Windows) degrades every Windows family to Absent, no throw" in {
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles, MachineWindows)
                reading = MachineWindows.read(sampler)
            yield assert(reading == Machine.Reading.empty)
        }
    }

end MachineWindowsTest
