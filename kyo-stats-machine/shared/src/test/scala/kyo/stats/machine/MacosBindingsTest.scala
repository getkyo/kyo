package kyo.stats.machine

import kyo.*
import kyo.ffi.*

class MacosBindingsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "real host load" - {

        // Ffi.load[MacosBindings] against the real compiled machine_macos shim is the one leaf here
        // that touches an actual host resource rather than a stub: it verifies host-invariant
        // properties (host_cpu_load returns 0 with positive cumulative ns values) that hold across
        // any macOS runner, never a specific numeric value. The shim's __APPLE__ branch reads real
        // mach/sysctl symbols, so the leaf is gated to a genuine macOS host; on Linux CI the same
        // binding loads against the shim's #else stub branch, which returns failure codes this leaf
        // is designed to skip past, never assert against.
        "the projection shim loads and host_cpu_load returns 0 with non-zero ns values (host-run on macOS)".onlyJvm in {
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

        "a statfs of a nonexistent path returns non-zero and is caught to Absent (host-run on macOS)".onlyJvm in {
            assume(
                System.live.unsafe.operatingSystem() == System.OS.MacOS,
                "the statfs struct layout this binding reads is macOS-specific"
            )
            val bindings = Ffi.load[MacosBindings]
            val result   = MacosDisk.stat(bindings, "/does/not/exist/kyo-stats-machine-test")
            assert(result == Machine.DiskReading("/does/not/exist/kyo-stats-machine-test", Absent, Absent))
        }
    }

    given CanEqual[Machine.DiskReading, Machine.DiskReading] = CanEqual.derived

end MacosBindingsTest
