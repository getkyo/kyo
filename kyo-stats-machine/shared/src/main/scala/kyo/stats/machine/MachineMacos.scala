package kyo.stats.machine

import kyo.*
import kyo.ffi.*

/** The macOS host reader: cpu-time through mach `host_statistics`, memory and swap through `sysctl` plus
  * mach `vm_statistics64`, load averages through `getloadavg`, and per-mount disk through the mount
  * enumeration and `statfs`. All syscalls go through a small projection shim that flattens the nested and
  * array struct fields the FFI struct layer cannot read into flat primitive out-params.
  *
  * The four out-buffers are RETAINED, allocated once at construction and closed by the sampler's Scope
  * finalizer: `Buffer.alloc` opens a fresh memory arena per call, so allocating them per read allocated
  * four arenas on every tick. cgroup and PSI are Linux-only and are never written here, and macOS has no
  * iowait or steal concept, so those cells are never written either and their series are never registered.
  */
final private[machine] class MachineMacos(h: MachineHandles, s: MachineSampler)(using AllowUnsafe) extends Machine:

    private val cpuOut  = Buffer.alloc[Long](4)
    private val memOut  = Buffer.alloc[Long](3)
    private val swapOut = Buffer.alloc[Long](2)
    private val loadOut = Buffer.alloc[Double](3)

    private val disk = new MacosDisk(h)

    def read()(using AllowUnsafe): Unit =
        bindings match
            case Present(b) =>
                readCpu(b)
                readMemory(b)
                readSwap(b)
                readLoad(b)
            case Absent => ()

    def readDisks()(using AllowUnsafe): Unit =
        bindings match
            case Present(b) => disk.read(b)
            case Absent     => ()

    def close()(using AllowUnsafe): Unit =
        cpuOut.close()
        memOut.close()
        swapOut.close()
        loadOut.close()
        disk.close()
    end close

    /** The binding, loaded once; a load failure (a host with no koffi, for instance) degrades every
      * reading to absent.
      */
    private lazy val bindings: Maybe[MacosBindings] =
        try Present(Ffi.load[MacosBindings])
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => Absent

    private[machine] def readCpu(b: MacosBindings)(using AllowUnsafe): Unit =
        if b.hostCpuLoad(cpuOut) == 0 then
            val user   = cpuOut.get(0)
            val system = cpuOut.get(1)
            val idle   = cpuOut.get(2)
            val nice   = cpuOut.get(3)
            h.cpuUser.observe(user)
            h.cpuSystem.observe(system)
            h.cpuIdle.observe(idle)
            h.cpuTotal.observe(user + system + idle + nice)
        end if
    end readCpu

    private[machine] def readMemory(b: MacosBindings)(using AllowUnsafe): Unit =
        if b.vmStatistics(memOut) == 0 then
            h.memTotal.set(memOut.get(0))
            h.memFree.observe(memOut.get(1))
            h.memAvailable.observe(memOut.get(2))
        end if
    end readMemory

    private[machine] def readSwap(b: MacosBindings)(using AllowUnsafe): Unit =
        if b.swapUsage(swapOut) == 0 then
            h.swapTotal.set(swapOut.get(0))
            h.swapFree.observe(swapOut.get(1))
        end if
    end readSwap

    private[machine] def readLoad(b: MacosBindings)(using AllowUnsafe): Unit =
        if b.getloadavg(loadOut, 3) == 3 then
            h.loadOne.set(loadOut.get(0))
            h.loadFive.set(loadOut.get(1))
            h.loadFifteen.set(loadOut.get(2))
        end if
    end readLoad

end MachineMacos
