package kyo.stats.machine

import kyo.*
import kyo.ffi.*

class WindowsBindingsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "real host load" - {

        // Ffi.load[WindowsBindings] against the real kernel32.dll is the one leaf here that touches an
        // actual host resource rather than a stub: it verifies host-invariant properties (GetSystemTimes
        // and GlobalMemoryStatusEx return non-zero with plausible cumulative values) that hold across any
        // Windows runner, never a specific numeric value. This CI is Linux-only, so the leaf is gated to a
        // genuine Windows host and unconditionally assume-cancels here, mirroring MacosBindingsTest and
        // LinuxBindingsTest's held-real-host-leaf structure.
        "GetSystemTimes and GlobalMemoryStatusEx return valid values on a Windows host (held)".onlyJvm in {
            assume(
                System.live.unsafe.operatingSystem() == System.OS.Windows,
                "the kernel32 Win32 ABI this binding reads is Windows-specific"
            )
            val bindings = Ffi.load[WindowsBindings]

            val idleB   = Buffer.alloc[Long](1)
            val kernelB = Buffer.alloc[Long](1)
            val userB   = Buffer.alloc[Long](1)
            try
                val rc = bindings.getSystemTimes(idleB, kernelB, userB)
                assert(rc != 0)
                assert(kernelB.get(0) >= idleB.get(0))
            finally
                idleB.close(); kernelB.close(); userB.close()
            end try

            WindowsBindings.withMemoryStatus(bindings) match
                case Present(out) =>
                    try
                        assert(out.get(1) > 0L)
                        assert(out.get(2) >= 0L)
                    finally out.close()
                case Absent => fail("expected GlobalMemoryStatusEx to succeed on a real Windows host")
            end match
        }
    }

end WindowsBindingsTest
