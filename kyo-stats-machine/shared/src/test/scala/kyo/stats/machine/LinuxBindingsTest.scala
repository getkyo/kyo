package kyo.stats.machine

import kyo.*
import kyo.ffi.*

class LinuxBindingsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "real host load" - {

        // Ffi.load[LinuxBindings] against the real JVM host libc is the one leaf here that touches
        // an actual host resource rather than a staged fixture: it verifies
        // host-invariant properties (a positive sysconf Hz; a coherent statvfs total/free
        // relation) that hold across any CI runner's disk size, never a specific numeric value.
        // struct statvfs's field layout is platform-specific (this binding reads the LP64
        // glibc/musl layout), so the leaf is gated to a real Linux host; JVM/JS/Native all
        // compile this binding, but only a genuine Linux host produces a coherent struct read.
        "statvfs and sysconf load on the real JVM host libc; statvfsRaw of / yields a coherent total/free relation".onlyJvm in {
            assume(
                System.live.unsafe.operatingSystem() == System.OS.Linux,
                "struct statvfs's field layout this binding reads is Linux-specific"
            )
            val bindings = Ffi.load[LinuxBindings]
            val hz       = bindings.sysconf(LinuxBindings.ScClkTck)
            assert(hz > 0L)
            val result = LinuxDisk.statvfsRaw(bindings, "/")
            assert(result.isDefined)
            result.foreach { case (total, free) =>
                assert(total > 0L)
                assert(free >= 0L)
                assert(free <= total)
            }
        }
    }

    "throw-to-Absent bridge" - {

        "a thrown platform exception from a binding call is caught by the production Machine-impl bridge, no exception escapes" in {
            val throwing = new LinuxBindings:
                def statvfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int =
                    throw new java.io.IOException("no such file or directory")
                def sysconf(name: Int)(using AllowUnsafe): Long =
                    throw new RuntimeException("sysconf failed")

            // Drives the PRODUCTION bridge methods (MachineLinux.statvfsWith / jiffiesFromBinding) with a
            // throwing binding, so the actual catch branches route to Absent / the default scale, rather
            // than a test-local re-implementation of the same catch.
            assert(MachineLinux.statvfsWith(throwing, "/does/not/exist") == Absent)
            assert(MachineLinux.jiffiesFromBinding(throwing) == MachineLinux.defaultJiffiesToNanos)
        }

        "a sysconf returning a non-positive Hz falls back to the default jiffies scale, no throw" in {
            val zeroHz = new LinuxBindings:
                def statvfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int = 1
                def sysconf(name: Int)(using AllowUnsafe): Long                      = 0L
            assert(MachineLinux.jiffiesFromBinding(zeroHz) == MachineLinux.defaultJiffiesToNanos)
        }

        "a sysconf returning a positive Hz yields the exact ns-per-jiffy scale" in {
            val hz100 = new LinuxBindings:
                def statvfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int = 1
                def sysconf(name: Int)(using AllowUnsafe): Long                      = 100L
            assert(MachineLinux.jiffiesFromBinding(hz100) == 10000000L)
        }
    }

end LinuxBindingsTest
