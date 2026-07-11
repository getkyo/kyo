package kyo.stats.machine

import kyo.*
import kyo.ffi.*

class MachineMacosTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    given CanEqual[Machine.CpuReading, Machine.CpuReading]       = CanEqual.derived
    given CanEqual[Machine.MemoryReading, Machine.MemoryReading] = CanEqual.derived
    given CanEqual[Machine.SwapReading, Machine.SwapReading]     = CanEqual.derived
    given CanEqual[Machine.LoadReading, Machine.LoadReading]     = CanEqual.derived
    given CanEqual[Machine.DiskReading, Machine.DiskReading]     = CanEqual.derived
    given CanEqual[Machine.Reading, Machine.Reading]             = CanEqual.derived

    /** A stub `MacosBindings` whose every method is overridable per test, defaulting to failure codes so
      * an un-stubbed call surfaces as an obvious Absent rather than a silent success.
      */
    private class StubBindings extends MacosBindings:
        var hostCpuLoadFn: Buffer[Long] => Int         = _ => 1
        var vmStatisticsFn: Buffer[Long] => Int        = _ => 1
        var swapUsageFn: Buffer[Long] => Int           = _ => 1
        var getloadavgFn: (Buffer[Double], Int) => Int = (_, _) => 0
        var mountCountFn: () => Int                    = () => 0
        var mountPathFn: Int => String                 = _ => ""
        var mountFstypeFn: Int => String               = _ => ""
        var statfsFn: (String, Buffer[Long]) => Int    = (_, _) => 1

        def hostCpuLoad(out: Buffer[Long])(using AllowUnsafe): Int          = hostCpuLoadFn(out)
        def vmStatistics(out: Buffer[Long])(using AllowUnsafe): Int         = vmStatisticsFn(out)
        def swapUsage(out: Buffer[Long])(using AllowUnsafe): Int            = swapUsageFn(out)
        def getloadavg(out: Buffer[Double], n: Int)(using AllowUnsafe): Int = getloadavgFn(out, n)
        def mountCount()(using AllowUnsafe): Int                            = mountCountFn()
        def mountPath(i: Int)(using AllowUnsafe): Ffi.Borrowed[String]      = Ffi.Borrowed.wrap(mountPathFn(i))
        def mountFstype(i: Int)(using AllowUnsafe): Ffi.Borrowed[String]    = Ffi.Borrowed.wrap(mountFstypeFn(i))
        def statfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int = statfsFn(path, out)
    end StubBindings

    "load average" - {

        "load average is present on macOS (getloadavg), unlike Windows" in {
            val stub = new StubBindings
            stub.getloadavgFn = (out, n) =>
                out.set(0, 1.5); out.set(1, 2.5); out.set(2, 3.5)
                n
            val result = MachineMacos.readLoad(stub)
            assert(result == Present(Machine.LoadReading(Present(1.5), Present(2.5), Present(3.5))))
        }

        "a getloadavg call returning fewer than 3 samples routes the whole reading to Absent" in {
            val stub = new StubBindings
            stub.getloadavgFn = (out, n) =>
                out.set(0, 1.5)
                1
            val result = MachineMacos.readLoad(stub)
            assert(result == Absent)
        }
    }

    "read degradation on a non-macOS host" - {

        // Exercises the REAL MachineMacos.read degrade path: off macOS the C shim's #else stubs return
        // failure codes for every projection, so each family routes to Absent. Whether the binding loads
        // (returning failure codes) or fails to load (bindings Absent), the read must not throw and the
        // Linux-only families are always Absent. Gated off a real macOS host, where the shim returns live
        // values and the families would be Present, which this degrade leaf is not asserting.
        "off macOS every family degrades to Absent through the production read, no throw" in {
            assume(
                System.live.unsafe.operatingSystem() != System.OS.MacOS,
                "the macOS shim returns live values on a real macOS host; this leaf asserts the off-macOS degrade"
            )
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles, MachineMacos)
                reading = MachineMacos.read(sampler)
            yield
                assert(reading.cpu == Absent)
                assert(reading.memory == Absent)
                assert(reading.swap == Absent)
                assert(reading.load == Absent)
                assert(reading.cgroup == Absent)
                assert(reading.pressure == Absent)
                assert(reading.cgroupPressure == Absent)
            end for
        }
    }

    "cpu decode" - {

        "cpu decode: host_cpu_load projects [user,system,idle,nice] ns, total is their sum" in {
            val stub = new StubBindings
            stub.hostCpuLoadFn = out =>
                out.set(0, 1000000000L); out.set(1, 2000000000L); out.set(2, 7000000000L); out.set(3, 300000000L)
                0
            val result = MachineMacos.readCpu(stub)
            assert(result == Present(Machine.CpuReading(
                total = Present(10300000000L),
                user = Present(1000000000L),
                system = Present(2000000000L),
                idle = Present(7000000000L),
                iowait = Absent
            )))
        }

        "a hostCpuLoad failure routes the whole reading to Absent" in {
            val stub = new StubBindings
            stub.hostCpuLoadFn = _ => 1
            assert(MachineMacos.readCpu(stub) == Absent)
        }
    }

    "memory decode" - {

        "memory decode: vm_statistics projects [total,free,available] bytes, re-ordered to (total,available,free)" in {
            val stub = new StubBindings
            stub.vmStatisticsFn = out =>
                out.set(0, 17179869184L); out.set(1, 2147483648L); out.set(2, 6442450944L)
                0
            val result = MachineMacos.readMemory(stub)
            assert(result == Present(Machine.MemoryReading(
                total = Present(17179869184L),
                available = Present(6442450944L),
                free = Present(2147483648L)
            )))
        }

        "a vm_statistics failure routes the whole reading to Absent" in {
            val stub = new StubBindings
            stub.vmStatisticsFn = _ => 1
            assert(MachineMacos.readMemory(stub) == Absent)
        }
    }

    "swap decode" - {

        "swap decode: swap_usage projects [total,free] bytes unchanged" in {
            val stub = new StubBindings
            stub.swapUsageFn = out =>
                out.set(0, 4294967296L); out.set(1, 1073741824L)
                0
            val result = MachineMacos.readSwap(stub)
            assert(result == Present(Machine.SwapReading(total = Present(4294967296L), free = Present(1073741824L))))
        }

        "a swap_usage failure routes the whole reading to Absent" in {
            val stub = new StubBindings
            stub.swapUsageFn = _ => 1
            assert(MachineMacos.readSwap(stub) == Absent)
        }
    }

    "disk enumeration filter" - {

        "disk enumeration filters devfs/autofs/nullfs and keeps physical mounts" in {
            val stub   = new StubBindings
            val mounts = Vector(("/", "apfs"), ("/dev", "devfs"), ("/net", "autofs"))
            stub.mountCountFn = () => mounts.length
            stub.mountPathFn = i => mounts(i)._1
            stub.mountFstypeFn = i => mounts(i)._2
            val result = MacosDisk.enumerate(stub)
            assert(result == Chunk("/"))
        }

        "disk enumeration filters network/remote filesystems (smbfs/nfs/afpfs); a hung remote mount is never statfs'd" in {
            val stub = new StubBindings
            val mounts = Vector(
                ("/", "apfs"),
                ("/Volumes/share", "smbfs"),
                ("/Volumes/nfs", "nfs"),
                ("/Volumes/afp", "afpfs"),
                ("/data", "apfs")
            )
            stub.mountCountFn = () => mounts.length
            stub.mountPathFn = i => mounts(i)._1
            stub.mountFstypeFn = i => mounts(i)._2
            val result = MacosDisk.enumerate(stub)
            assert(result == Chunk("/", "/data"))
        }
    }

    "per-mount statfs failure" - {

        "a statfs failure for one mount skips only that mount" in {
            val stub = new StubBindings
            stub.statfsFn = (path, out) =>
                if path == "/broken" then 1
                else
                    out.set(0, 4096000L); out.set(1, 1024000L)
                    0
            val ok     = MacosDisk.stat(stub, "/")
            val failed = MacosDisk.stat(stub, "/broken")
            assert(ok == Machine.DiskReading("/", Present(4096000L), Present(1024000L)))
            assert(failed == Machine.DiskReading("/broken", Absent, Absent))
        }
    }

end MachineMacosTest
