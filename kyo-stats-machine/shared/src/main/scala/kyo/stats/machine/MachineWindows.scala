package kyo.stats.machine

import kyo.*
import kyo.ffi.*

/** The Windows host reader: cpu-time through `GetSystemTimes`, memory and swap through
  * `GlobalMemoryStatusEx`, per-drive disk through `GetLogicalDrives` and `GetDiskFreeSpaceEx`. Win32 is
  * bound directly against kernel32.
  *
  * The out-buffers are RETAINED, allocated once at construction and closed by the sampler's Scope
  * finalizer, and `GlobalMemoryStatusEx` is called ONCE per tick, with the memory and the swap rows reading
  * the same filled buffer.
  *
  * Load averages and cgroup and PSI do not exist on Windows, so those cells are never written and their
  * series are never registered. Memory has no distinct free-versus-available concept on Windows, so
  * `memory.free` is never written either, rather than reporting the available figure under a second label.
  */
final private[machine] class MachineWindows(h: MachineHandles, s: MachineSampler)(using AllowUnsafe) extends Machine:

    private val idleOut   = Buffer.alloc[Long](1)
    private val kernelOut = Buffer.alloc[Long](1)
    private val userOut   = Buffer.alloc[Long](1)
    private val memOut    = Buffer.alloc[Long](8)

    private val disk = new WindowsDisk(h)

    def read()(using AllowUnsafe): Unit =
        bindings match
            case Present(b) =>
                // A library that resolves at load time can still fail its first real symbol lookup lazily
                // (the generated implementation's static initializer runs on the first call and wraps a
                // missing Win32 export in an ExceptionInInitializerError, a LinkageError that NonFatal
                // excludes). This is the reader's degradation boundary, so such a failure degrades here too.
                try
                    readCpu(b)
                    readMemoryAndSwap(b)
                catch
                    case ex: Throwable if scala.util.control.NonFatal(ex) || ex.isInstanceOf[LinkageError] => ()
            case Absent => ()

    def readDisks()(using AllowUnsafe): Unit =
        bindings match
            case Present(b) =>
                try disk.read(b)
                catch
                    case ex: Throwable if scala.util.control.NonFatal(ex) || ex.isInstanceOf[LinkageError] => ()
            case Absent => ()

    def close()(using AllowUnsafe): Unit =
        idleOut.close()
        kernelOut.close()
        userOut.close()
        memOut.close()
        disk.close()
    end close

    private lazy val bindings: Maybe[WindowsBindings] =
        try Present(Ffi.load[WindowsBindings])
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => Absent

    private[machine] def readCpu(b: WindowsBindings)(using AllowUnsafe): Unit =
        // GetSystemTimes takes three out-params, each a FILETIME: one little-endian 100ns int64.
        if b.getSystemTimes(idleOut, kernelOut, userOut) != 0 then
            val idle   = idleOut.get(0) * 100L
            val kernel = kernelOut.get(0) * 100L
            val user   = userOut.get(0) * 100L
            // Kernel time INCLUDES idle on Windows, so system time is kernel minus idle.
            h.cpuUser.observe(user)
            h.cpuSystem.observe(kernel - idle)
            h.cpuIdle.observe(idle)
            h.cpuTotal.observe(kernel + user)
        end if
    end readCpu

    private[machine] def readMemoryAndSwap(b: WindowsBindings)(using AllowUnsafe): Unit =
        if WindowsBindings.fillMemoryStatus(b, memOut) then
            // Index 1 is ullTotalPhys and index 2 ullAvailPhys. Windows exposes no distinct free-versus-
            // available figure, so memory.free is not written.
            h.memTotal.set(memOut.get(1))
            h.memAvailable.observe(memOut.get(2))
            // Indexes 3 and 4 are ullTotalPageFile and ullAvailPageFile: the system COMMIT LIMIT (physical
            // memory plus the page file) and its remainder, not a page-file-only figure the way Linux
            // SwapTotal and SwapFree are. Windows exposes no narrower swap concept, so the commit limit is
            // the closest honest mapping and is reported as-is.
            h.swapTotal.set(memOut.get(3))
            h.swapFree.observe(memOut.get(4))
        end if
    end readMemoryAndSwap

end MachineWindows
