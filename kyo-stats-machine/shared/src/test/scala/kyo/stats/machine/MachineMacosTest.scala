package kyo.stats.machine

import kyo.*
import kyo.ffi.*
import kyo.stats.internal.StatsRegistry
import kyo.stats.internal.Summary
import kyo.stats.internal.UnsafeGauge
import kyo.stats.internal.UnsafeHistogram

class MachineMacosTest extends kyo.test.Test[Any]:

    // Every leaf's MachineHandles.init shares the SAME process-global StatsRegistry scope ("machine"):
    // every metric this suite decodes into (cpu/memory/swap/load/disk) is a fixed, well-known path
    // shared with every other suite's own MachineHandles.init. Each leaf below therefore reads its own
    // metric's DELTA (a histogram sum/count captured immediately before and after its own decode call,
    // with no suspension in between) rather than an absolute registry value, so a concurrently-running
    // sibling suite's own observations cannot corrupt this suite's assertions.
    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    /** A stub `MacosBindings` whose every method is overridable per test, defaulting to failure codes so an
      * un-stubbed call surfaces as an obvious Absent rather than a silent success.
      */
    private class StubBindings extends MacosBindings:
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
    end StubBindings

    /** Encodes `pairs` as NUL-separated `<mount>\0<fstype>\0` bytes into `buf`, the exact wire shape
      * `machine_macos_mounts` fills; returns the pair count `MacosDisk.snapshot` expects.
      */
    private def encodeMounts(buf: Buffer[Byte], pairs: List[(String, String)])(using AllowUnsafe): Int =
        var at = 0
        pairs.foreach { case (mount, fstype) =>
            mount.getBytes(java.nio.charset.StandardCharsets.UTF_8).foreach { b =>
                buf.set(at, b); at += 1
            }
            buf.set(at, 0.toByte); at += 1
            fstype.getBytes(java.nio.charset.StandardCharsets.UTF_8).foreach { b =>
                buf.set(at, b); at += 1
            }
            buf.set(at, 0.toByte); at += 1
        }
        pairs.length
    end encodeMounts

    private def gaugePath(path: String*): Double =
        StatsRegistry.internal.gauges.get(path.toList.reverse, "", new UnsafeGauge(() => -1d)).collect()

    private def gaugeRegistered(path: String*): Boolean =
        StatsRegistry.internal.gauges.map.containsKey(path.toList)

    private def histogramRegistered(path: String*): Boolean =
        StatsRegistry.internal.histograms.map.containsKey(path.toList)

    private def histogramSummary(path: String*): Summary =
        StatsRegistry.internal.histograms.get(path.toList.reverse, "", new UnsafeHistogram(Array(0d))).summary()

    "cpu decode" - {

        "host_cpu_load projects [user,system,idle,nice] ns and the cpu rate cells register their sum" in {
            val stub = new StubBindings
            stub.hostCpuLoadFn = out =>
                out.set(0, 1000000000L); out.set(1, 2000000000L); out.set(2, 7000000000L); out.set(3, 300000000L); 0
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles)
                machine = new MachineMacos(handles, sampler)
            yield
                machine.readCpu(stub) // baseline tick
                val totalSumBefore  = histogramSummary("machine", "cpu", "total.rate").sum
                val userSumBefore   = histogramSummary("machine", "cpu", "user.rate").sum
                val systemSumBefore = histogramSummary("machine", "cpu", "system.rate").sum
                val idleSumBefore   = histogramSummary("machine", "cpu", "idle.rate").sum
                stub.hostCpuLoadFn = out =>
                    out.set(0, 2000000000L); out.set(1, 4000000000L); out.set(2, 14000000000L); out.set(3, 600000000L); 0
                machine.readCpu(stub) // delta tick: user+1e9, system+2e9, idle+7e9, nice+3e8
                assert(histogramSummary("machine", "cpu", "total.rate").sum - totalSumBefore == 10300000000.0)
                assert(histogramSummary("machine", "cpu", "user.rate").sum - userSumBefore == 1000000000.0)
                assert(histogramSummary("machine", "cpu", "system.rate").sum - systemSumBefore == 2000000000.0)
                assert(histogramSummary("machine", "cpu", "idle.rate").sum - idleSumBefore == 7000000000.0)
            end for
        }

        "a host_cpu_load failure routes cpu to Absent, never a fabricated zero" in {
            val stub = new StubBindings
            stub.hostCpuLoadFn = _ => 1
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles)
                machine = new MachineMacos(handles, sampler)
            yield
                val totalCountBefore = histogramSummary("machine", "cpu", "total.rate").count
                machine.readCpu(stub)
                assert(histogramSummary("machine", "cpu", "total.rate").count == totalCountBefore)
            end for
        }

        "a macOS-shaped read registers no cpu.steal.rate and no cpu.iowait.rate" in {
            val stub = new StubBindings
            stub.hostCpuLoadFn = out =>
                out.set(0, 1L); out.set(1, 1L); out.set(2, 1L); out.set(3, 1L); 0
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles)
                machine = new MachineMacos(handles, sampler)
            yield
                // cpu.steal.rate/cpu.iowait.rate are also written by MachineLinuxTest's own leaves on the
                // SAME process-global "machine" scope, so a sibling suite may have already registered them
                // in this JVM; the discriminating check is that THIS call adds no new observation, via the
                // count delta, not an absolute "never registered" fact.
                val stealCountBefore  = histogramSummary("machine", "cpu", "steal.rate").count
                val iowaitCountBefore = histogramSummary("machine", "cpu", "iowait.rate").count
                machine.readCpu(stub)
                assert(histogramSummary("machine", "cpu", "steal.rate").count == stealCountBefore)
                assert(histogramSummary("machine", "cpu", "iowait.rate").count == iowaitCountBefore)
            end for
        }
    }

    "memory decode" - {

        "vm_statistics projects [total,free,available] bytes, re-ordered to (total,available,free) cells" in {
            val stub = new StubBindings
            stub.vmStatisticsFn = out =>
                out.set(0, 17179869184L); out.set(1, 2147483648L); out.set(2, 6442450944L); 0
            // A uniquely-scoped MachineHandles, not the shared "machine" root every MachineHandles.init
            // call resolves to (also written by MachineLinuxTest's own meminfo leaves): StatsRegistry keeps
            // only the first-ever-registered cell for a path canonical for the process lifetime, so a poll
            // against the shared scope could read a value a different leaf registered first.
            val handles = MachineHandles.initForTest(Stat.initScope("mmactest-memory-decode"), 8L)
            val sampler = new MachineSampler(handles)
            val machine = new MachineMacos(handles, sampler)
            machine.readMemory(stub)
            assert(gaugePath("mmactest-memory-decode", "memory", "total") == 17179869184.0)
            assert(histogramSummary("mmactest-memory-decode", "memory", "available").sum == 6442450944.0)
            assert(histogramSummary("mmactest-memory-decode", "memory", "free").sum == 2147483648.0)
        }
    }

    "swap decode" - {

        "swap_usage projects [total,free] bytes unchanged" in {
            val stub = new StubBindings
            stub.swapUsageFn = out =>
                out.set(0, 4294967296L); out.set(1, 1073741824L); 0
            // A uniquely-scoped MachineHandles; see the memTotal note in "memory decode" above.
            val handles = MachineHandles.initForTest(Stat.initScope("mmactest-swap-decode"), 8L)
            val sampler = new MachineSampler(handles)
            val machine = new MachineMacos(handles, sampler)
            machine.readSwap(stub)
            assert(gaugePath("mmactest-swap-decode", "swap", "total") == 4294967296.0)
            assert(histogramSummary("mmactest-swap-decode", "swap", "free").sum == 1073741824.0)
        }

        "a swap_usage failure routes swap to Absent" in {
            val stub = new StubBindings
            stub.swapUsageFn = _ => 1
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles)
                machine = new MachineMacos(handles, sampler)
            yield
                val freeCountBefore = histogramSummary("machine", "swap", "free").count
                machine.readSwap(stub)
                assert(histogramSummary("machine", "swap", "free").count == freeCountBefore)
            end for
        }
    }

    "load decode" - {

        "getloadavg returning 3 registers load.one/five/fifteen (load is present on macOS)" in {
            val stub = new StubBindings
            stub.getloadavgFn = (out, n) =>
                out.set(0, 1.5); out.set(1, 2.5); out.set(2, 3.5); n
            // A uniquely-scoped MachineHandles; see the memTotal note in "memory decode" above. Load has
            // no histogram-backed sibling to fall back on, so the routing claim (index i -> the right
            // named cell, not a swap between five/fifteen) needs this isolation to be checked exactly.
            val handles = MachineHandles.initForTest(Stat.initScope("mmactest-load-decode"), 8L)
            val sampler = new MachineSampler(handles)
            val machine = new MachineMacos(handles, sampler)
            machine.readLoad(stub)
            assert(gaugePath("mmactest-load-decode", "load", "one") == 1.5)
            assert(gaugePath("mmactest-load-decode", "load", "five") == 2.5)
            assert(gaugePath("mmactest-load-decode", "load", "fifteen") == 3.5)
        }

        "a getloadavg returning fewer than 3 samples routes load to Absent" in {
            val stub = new StubBindings
            stub.getloadavgFn = (out, n) =>
                out.set(0, 9.0); 1
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles)
                machine = new MachineMacos(handles, sampler)
            yield
                val oneRegisteredBefore = gaugeRegistered("machine", "load", "one")
                machine.readLoad(stub)
                assert(gaugeRegistered("machine", "load", "one") == oneRegisteredBefore) // no new registration
            end for
        }
    }

    "family independence" - {

        "a macOS-shaped read registers no cgroup and no PSI series, while load IS present" in {
            val stub = new StubBindings
            stub.hostCpuLoadFn = out =>
                out.set(0, 1L); out.set(1, 1L); out.set(2, 1L); out.set(3, 1L); 0
            stub.vmStatisticsFn = out =>
                out.set(0, 1L); out.set(1, 1L); out.set(2, 1L); 0
            stub.swapUsageFn = out =>
                out.set(0, 1L); out.set(1, 1L); 0
            stub.getloadavgFn = (out, n) =>
                out.set(0, 4.2); out.set(1, 4.2); out.set(2, 4.2); n
            // A uniquely-scoped MachineHandles, never touched by any other leaf or suite, so every path
            // under it starts genuinely unregistered and an absolute check is meaningful (unlike the
            // shared "machine" scope, which MachineLinuxTest's own leaves may have already populated).
            val handles = MachineHandles.initForTest(Stat.initScope("mmactest-family-independence"), 8L)
            val sampler = new MachineSampler(handles)
            val machine = new MachineMacos(handles, sampler)
            machine.readCpu(stub); machine.readMemory(stub); machine.readSwap(stub); machine.readLoad(stub)
            assert(!histogramRegistered("mmactest-family-independence", "cgroup", "memory.usage"))
            assert(!gaugeRegistered("mmactest-family-independence", "cgroup", "memory.limit"))
            assert(!gaugeRegistered("mmactest-family-independence", "pressure", "cpu", "some", "avg10"))
            assert(!gaugeRegistered("mmactest-family-independence", "cgroup", "pressure", "cpu", "some", "avg10"))
            assert(gaugePath("mmactest-family-independence", "load", "one") == 4.2)
        }
    }

    "disk enumeration" - {

        "keeps physical mounts and drops devfs/autofs/nullfs and network smbfs/nfs/afpfs" in {
            val pairs = List(
                "/"              -> "apfs",
                "/dev"           -> "devfs",
                "/net"           -> "autofs",
                "/Volumes/share" -> "smbfs",
                "/Volumes/nfs"   -> "nfs",
                "/data"          -> "apfs"
            )
            val stub = new StubBindings
            stub.mountsFn = (out, cap) => encodeMounts(out, pairs)
            stub.statfsFn = (path, out) =>
                out.set(0, 4096000L); out.set(1, 1024000L); 0
            // A uniquely-scoped MachineHandles: disk store paths are derived from the handles' own root
            // scope, so "/" -> "root" here cannot collide with the OTHER disk leaf below (or another
            // suite) also decoding a "/" mount under the shared "machine" scope.
            val handles = MachineHandles.initForTest(Stat.initScope("mmactest-disk-enum"), 8L)
            val disk    = new MacosDisk(handles)
            disk.read(stub)
            assert(gaugeRegistered("mmactest-disk-enum", "disk", "root", "total"))
            assert(gaugeRegistered("mmactest-disk-enum", "disk", "data", "total"))
            assert(!gaugeRegistered("mmactest-disk-enum", "disk", "dev", "total"))
            assert(!gaugeRegistered("mmactest-disk-enum", "disk", "net", "total"))
        }

        "a statfs failure for one mount skips only that mount, no throw" in {
            val pairs = List("/" -> "apfs", "/broken" -> "apfs")
            val stub  = new StubBindings
            stub.mountsFn = (out, cap) => encodeMounts(out, pairs)
            stub.statfsFn = (path, out) =>
                if path == "/broken" then 1
                else
                    out.set(0, 4096000L); out.set(1, 1024000L); 0
            // A uniquely-scoped MachineHandles; see the disk-enumeration leaf above for why.
            val handles = MachineHandles.initForTest(Stat.initScope("mmactest-disk-statfs-fail"), 8L)
            val disk    = new MacosDisk(handles)
            disk.read(stub) // no throw despite the per-mount failure
            assert(gaugePath("mmactest-disk-statfs-fail", "disk", "root", "total") == 4096000.0)
            assert(!gaugeRegistered("mmactest-disk-statfs-fail", "disk", "broken", "total"))
        }
    }

    "off-macOS degrade" - {

        "off macOS every family degrades to Absent through the production read, no throw" in {
            assume(
                System.live.unsafe.operatingSystem() != System.OS.MacOS,
                "the macOS shim returns live values on a real macOS host; this leaf asserts the off-macOS degrade"
            )
            for
                handles <- MachineHandles.init
                sampler        = new MachineSampler(handles)
                machine        = new MachineMacos(handles, sampler)
                cpuCountBefore = histogramSummary("machine", "cpu", "total.rate").count
                _              = machine.read()
                _              = machine.readDisks()
            yield assert(histogramSummary("machine", "cpu", "total.rate").count == cpuCountBefore)
            end for
        }
    }

    "shim-load failure" - {

        "a native-library load failure degrades every family to Absent through the production read, no throw" in {
            // The generated MacosBindings impl loads the native library lazily on the first binding call, so
            // a shim-load failure surfaces from read()'s first decode, not from Ffi.load. This drives that
            // failure through MachineMacos' load probe and asserts bindings contains it: read() and
            // readDisks() register nothing and do not throw. Unlike the off-macOS leaf above (assume-cancelled
            // on macOS), this runs on every host: the failing probe throws before any real native load, so the
            // outcome does not depend on whether the shim resolves or on the process-global load order the
            // real koffi/dlopen path is subject to. It encodes the README's degrade promise directly.
            val failing = new MachineMacos.LoadProbe:
                def apply(bindings: MacosBindings, scratch: Buffer[Long])(using AllowUnsafe): Unit =
                    throw new FfiLoadError.LibraryNotFound("machine_macos", Chunk("test: shim unresolvable"), null)
            // A uniquely-scoped MachineHandles, never touched by any other leaf or suite, so every path under
            // it starts genuinely unregistered and the absolute "nothing registered" check is meaningful.
            val handles = MachineHandles.initForTest(Stat.initScope("mmactest-shim-load-fail"), 8L)
            val sampler = new MachineSampler(handles)
            val machine = new MachineMacos(handles, sampler, failing)
            try
                machine.read()      // no throw despite the load failure
                machine.readDisks() // no throw
                assert(!histogramRegistered("mmactest-shim-load-fail", "cpu", "total.rate"))
                assert(!gaugeRegistered("mmactest-shim-load-fail", "memory", "total"))
                assert(!gaugeRegistered("mmactest-shim-load-fail", "swap", "total"))
                assert(!gaugeRegistered("mmactest-shim-load-fail", "load", "one"))
                assert(!gaugeRegistered("mmactest-shim-load-fail", "disk", "root", "total"))
            finally machine.close()
            end try
        }
    }

end MachineMacosTest
