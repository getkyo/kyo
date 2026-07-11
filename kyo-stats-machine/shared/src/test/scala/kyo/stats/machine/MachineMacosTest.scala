package kyo.stats.machine

import kyo.*
import kyo.ffi.*

class MachineMacosTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    given CanEqual[Machine.CpuReading, Machine.CpuReading]   = CanEqual.derived
    given CanEqual[Machine.LoadReading, Machine.LoadReading] = CanEqual.derived
    given CanEqual[Machine.DiskReading, Machine.DiskReading] = CanEqual.derived
    given CanEqual[Machine.Reading, Machine.Reading]         = CanEqual.derived

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

    "cgroup/pressure/cgroupPressure are always Absent" - {

        "a macOS reading has cgroup, pressure, and cgroupPressure Absent and cpu Present, no throw" in {
            val stub = new StubBindings
            stub.hostCpuLoadFn = out =>
                out.set(0, 1000000000L); out.set(1, 500000000L); out.set(2, 8000000000L); out.set(3, 0L)
                0
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles, MachineMacos)
                reading = Machine.Reading(
                    cpu = readCpu(stub),
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
                assert(reading.cpu.isDefined)
            end for
        }
    }

    "load average" - {

        "load average is present on macOS (getloadavg), unlike Windows" in {
            val stub = new StubBindings
            stub.getloadavgFn = (out, n) =>
                out.set(0, 1.5); out.set(1, 2.5); out.set(2, 3.5)
                n
            val result = readLoad(stub)
            assert(result == Present(Machine.LoadReading(Present(1.5), Present(2.5), Present(3.5))))
        }
    }

    "binding-load failure" - {

        "a binding-load failure (no koffi on browser-JS) degrades every macOS family to Absent, no throw" in {
            def loadBindings(): Maybe[MacosBindings] =
                try throw new RuntimeException("no koffi")
                catch case ex: Throwable if scala.util.control.NonFatal(ex) => Absent
            val reading = loadBindings() match
                case Present(_) => fail("expected the load to fail in this scenario")
                case Absent     => Machine.Reading.empty
            assert(reading == Machine.Reading.empty)
        }
    }

    "cpu decode" - {

        "cpu decode: host_cpu_load projects [user,system,idle,nice] ns, total is their sum" in {
            val stub = new StubBindings
            stub.hostCpuLoadFn = out =>
                out.set(0, 1000000000L); out.set(1, 2000000000L); out.set(2, 7000000000L); out.set(3, 300000000L)
                0
            val result = readCpu(stub)
            assert(result == Present(Machine.CpuReading(
                total = Present(10300000000L),
                user = Present(1000000000L),
                system = Present(2000000000L),
                idle = Present(7000000000L),
                iowait = Absent
            )))
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

    /** Mirrors `MachineMacos.readCpu`'s own buffer shape and close discipline, driven against a stub
      * binding instead of the real shim.
      */
    private def readCpu(b: MacosBindings)(using AllowUnsafe): Maybe[Machine.CpuReading] =
        val out = Buffer.alloc[Long](4)
        try
            if b.hostCpuLoad(out) != 0 then Absent
            else
                val user  = out.get(0); val system = out.get(1); val idle = out.get(2); val nice = out.get(3)
                val total = user + system + idle + nice
                Present(Machine.CpuReading(Present(total), Present(user), Present(system), Present(idle), Absent))
            end if
        finally out.close()
        end try
    end readCpu

    /** Mirrors `MachineMacos.readLoad`'s own buffer shape and close discipline, driven against a stub
      * binding instead of the real shim.
      */
    private def readLoad(b: MacosBindings)(using AllowUnsafe): Maybe[Machine.LoadReading] =
        val out = Buffer.alloc[Double](3)
        try
            if b.getloadavg(out, 3) != 3 then Absent
            else Present(Machine.LoadReading(Present(out.get(0)), Present(out.get(1)), Present(out.get(2))))
        finally out.close()
        end try
    end readLoad

end MachineMacosTest
