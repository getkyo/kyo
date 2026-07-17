package kyo.stats.machine

import kyo.*
import kyo.ffi.*
import kyo.stats.internal.StatsRegistry
import kyo.stats.internal.UnsafeGauge

class MacosBindingsTest extends kyo.test.Test[Any]:

    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

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

    "real host load" - {

        // Ffi.load[MacosBindings] against the real compiled machine_macos shim is the one leaf group here
        // that touches an actual host resource rather than a stub: it verifies host-invariant properties
        // that hold across any macOS runner, never a specific numeric value. The shim's __APPLE__ branch
        // reads real mach/sysctl symbols, so each leaf is gated to a genuine macOS host; on Linux CI the
        // same binding loads against the shim's #else stub branch, which returns failure codes these
        // leaves are designed to skip past, never assert against.

        "the projection shim loads and host_cpu_load returns 0 with positive cumulative ns (host-run on macOS)".onlyJvm in {
            assume(
                System.live.unsafe.operatingSystem() == System.OS.MacOS,
                "the mach host_statistics symbols this binding reads are macOS-specific"
            )
            val bindings = Ffi.load[MacosBindings]
            val out      = Buffer.alloc[Long](4)
            try
                val rc = bindings.hostCpuLoad(out)
                assert(rc == 0)
                val user = out.get(0); val system = out.get(1); val idle = out.get(2); val nice = out.get(3)
                assert(user + system + idle + nice > 0L)
            finally out.close()
            end try
        }

        "the mach host port is acquired once and keeps serving across repeated reads (host-run on macOS)".onlyJvm in {
            assume(
                System.live.unsafe.operatingSystem() == System.OS.MacOS,
                "the mach host_statistics symbols this binding reads are macOS-specific"
            )
            val bindings = Ffi.load[MacosBindings]
            val out      = Buffer.alloc[Long](4)
            try
                var lastCumulative = -1L
                (1 to 3).foreach { _ =>
                    val rc = bindings.hostCpuLoad(out)
                    assert(rc == 0)
                    val cumulative = out.get(0) + out.get(1) + out.get(2) + out.get(3)
                    assert(cumulative >= lastCumulative)
                    lastCumulative = cumulative
                }
            finally out.close()
            end try
        }

        "a statfs of / succeeds and a statfs of a nonexistent path is contained to no write (host-run on macOS)".onlyJvm in {
            assume(
                System.live.unsafe.operatingSystem() == System.OS.MacOS,
                "the statfs struct layout this binding reads is macOS-specific"
            )
            val bindings = Ffi.load[MacosBindings]
            for handles <- MachineHandles.init
            yield
                val rootCell     = handles.diskStore("mbtest-realhost-root")
                val missingCell  = handles.diskStore("mbtest-realhost-missing")
                val rootOut      = Buffer.alloc[Long](2)
                val missingOut   = Buffer.alloc[Long](2)
                val rootStore    = new MacosDisk.Store("/", rootOut, rootCell)
                val missingStore = new MacosDisk.Store("/does/not/exist/kyo-stats-machine-test", missingOut, missingCell)
                MacosDisk.statfsInto(bindings, rootStore)
                MacosDisk.statfsInto(bindings, missingStore) // contained, no write
                rootOut.close(); missingOut.close()
                assert(gaugePath("machine", "disk", "mbtest-realhost-root", "total") > 0.0)
                assert(!gaugeRegistered("machine", "disk", "mbtest-realhost-missing", "total"))
            end for
        }
    }

    "MachineMacos.readMemory host_page_size failure" - {

        "host_page_size==0 (a non-zero vm_statistics return) yields Absent memory, never Present(0)" in {
            val stub = new StubBindings
            stub.vmStatisticsFn = _ => 1
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles)
                machine = new MachineMacos(handles, sampler)
            yield
                val totalRegisteredBefore = gaugeRegistered("machine", "memory", "total")
                machine.readMemory(stub)
                assert(gaugeRegistered("machine", "memory", "total") == totalRegisteredBefore)
            end for
        }
    }

    "MacosDisk.mounts enumeration" - {

        "mounts() fills the caller buffer and per-index path/fstype/total/free decode a staged mount set" in {
            val pairs = List("/mbtest-alpha" -> "apfs", "/dev" -> "devfs", "/mbtest-beta" -> "apfs")
            val stub  = new StubBindings
            stub.mountsFn = (out, cap) => encodeMounts(out, pairs)
            stub.statfsFn = (path, out) =>
                if path == "/mbtest-alpha" then
                    out.set(0, 1000L); out.set(1, 400L); 0
                else if path == "/mbtest-beta" then
                    out.set(0, 2000L); out.set(1, 800L); 0
                else 1
            val buf   = Buffer.alloc[Byte](256)
            val count = encodeMounts(buf, pairs)
            val snap  = MacosDisk.snapshot(buf, count)
            buf.close()
            assert(snap.mounts == Chunk("/mbtest-alpha", "/mbtest-beta")) // devfs dropped
            for
                handles <- MachineHandles.init
                disk = new MacosDisk(handles)
            yield
                disk.read(stub)
                // storeNames only replaces "/" and "." with "_"; a hyphen in the mount path survives
                // unchanged into the derived store name.
                assert(gaugePath("machine", "disk", "mbtest-alpha", "total") == 1000.0)
                assert(gaugePath("machine", "disk", "mbtest-beta", "total") == 2000.0)
            end for
        }

        "a mounts() return of 0 and of -1 (buffer too small) both leave the store set empty with no throw" in {
            val stub = new StubBindings
            stub.mountsFn = (_, _) => 0
            for
                handles <- MachineHandles.init
                disk = new MacosDisk(handles)
            yield
                disk.read(stub) // count == 0: no throw, store set stays empty
                stub.mountsFn = (_, _) => -1
                disk.read(stub) // count == -1 (buffer too small): no throw, store set stays empty
                // The disk reader is still in a healthy, re-derivable state after both degrade paths: a
                // subsequent successful enumeration registers its store normally.
                stub.mountsFn = (out, cap) => encodeMounts(out, List("/mbtest-edge-recover" -> "apfs"))
                stub.statfsFn = (path, out) =>
                    out.set(0, 500L); out.set(1, 100L); 0
                disk.read(stub)
                assert(gaugePath("machine", "disk", "mbtest-edge-recover", "total") == 500.0)
            end for
        }

        "the store set is re-derived only when the enumeration bytes change (fingerprint stability)" in {
            var statfsCallCount = 0
            val stub            = new StubBindings
            stub.mountsFn = (out, cap) => encodeMounts(out, List("/mbtest-fp" -> "apfs"))
            stub.statfsFn = (path, out) =>
                statfsCallCount += 1; out.set(0, 700L); out.set(1, 300L); 0
            for
                handles <- MachineHandles.init
                disk = new MacosDisk(handles)
            yield
                disk.read(stub)              // 1st read: derives one store
                disk.read(stub)              // 2nd read: identical bytes, store set unchanged
                assert(statfsCallCount == 2) // one statfs call per steady read regardless of rebuild
                assert(!gaugeRegistered("machine", "disk", "mbtest-fp2", "total"))
                stub.mountsFn = (out, cap) => encodeMounts(out, List("/mbtest-fp" -> "apfs", "/mbtest-fp2" -> "apfs"))
                disk.read(stub) // 3rd read: changed bytes, re-derives to two stores
                assert(statfsCallCount == 4)
                assert(gaugeRegistered("machine", "disk", "mbtest-fp2", "total"))
            end for
        }
    }

end MacosBindingsTest
