package kyo.stats.machine

import kyo.*
import kyo.ffi.*

class LinuxBindingsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "MachineLinux.jiffiesFromBinding" - {

        "a non-positive sysconf result falls back to the 100 Hz Linux default" in {
            val stub = new LinuxBindings:
                def statvfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int = 1
                def sysconf(name: Int)(using AllowUnsafe): Long                      = 0L
            assert(MachineLinux.jiffiesFromBinding(stub) == MachineLinux.defaultJiffiesToNanos)
            assert(MachineLinux.jiffiesFromBinding(stub) == 10000000L)
        }

        "a negative sysconf result falls back to the 100 Hz default" in {
            val stub = new LinuxBindings:
                def statvfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int = 1
                def sysconf(name: Int)(using AllowUnsafe): Long                      = -1L
            assert(MachineLinux.jiffiesFromBinding(stub) == 10000000L)
        }

        "a positive sysconf result yields the exact per-jiffy nanoseconds" in {
            val stub100 = new LinuxBindings:
                def statvfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int = 1
                def sysconf(name: Int)(using AllowUnsafe): Long                      = 100L
            val stub250 = new LinuxBindings:
                def statvfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int = 1
                def sysconf(name: Int)(using AllowUnsafe): Long                      = 250L
            assert(MachineLinux.jiffiesFromBinding(stub100) == 10000000L)
            assert(MachineLinux.jiffiesFromBinding(stub250) == 4000000L)
        }

        "a throwing sysconf falls back to the 100 Hz default" in {
            val stub = new LinuxBindings:
                def statvfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int = 1
                def sysconf(name: Int)(using AllowUnsafe): Long                      = throw new RuntimeException("sysconf failed")
            assert(MachineLinux.jiffiesFromBinding(stub) == MachineLinux.defaultJiffiesToNanos)
        }
    }

end LinuxBindingsTest
