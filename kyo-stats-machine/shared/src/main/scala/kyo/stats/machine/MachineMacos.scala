package kyo.stats.machine

import kyo.*
import kyo.ffi.*

/** The macOS host reader: cpu-time through mach `host_statistics`, memory and swap through `sysctl` plus
  * mach `vm_statistics64`, load averages through `getloadavg`, and per-mount disk through the mount
  * enumeration and `statfs`. All syscalls go through a small projection shim that flattens the nested and
  * array struct fields the FFI struct layer cannot read into flat primitive out-params.
  *
  * The four out-buffers are RETAINED, allocated once at construction and closed by the sampler's Scope
  * finalizer: `Buffer.alloc` opens a fresh memory arena per call, so allocating them per read would allocate
  * four arenas on every tick. Every read is through `Buffer`'s non-generic `getLong`/`setLong`/`getDouble`
  * accessors rather than the generic `get`/`set`, which box every element through the `UnsafeLayout[A]`
  * typeclass dispatch (JVM erasure); the non-generic accessors bypass that dispatch, so a steady read
  * allocates nothing. cgroup and PSI are Linux-only and are never written here, and macOS has no iowait or
  * steal concept, so those cells are never written either and their series are never registered.
  */
final private[machine] class MachineMacos(
    h: MachineHandles,
    s: MachineSampler,
    loadProbe: MachineMacos.LoadProbe = MachineMacos.RealLoad
)(using AllowUnsafe) extends Machine:

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

    /** The binding, loaded once; a load failure (a host with no koffi, an unresolvable shim) degrades
      * every reading to absent.
      *
      * The generated impl loads the native library lazily, on the first binding call, not at
      * `Ffi.load`, so a shim-load failure would otherwise be thrown from the tick path's first read and
      * escape this `try`. `loadProbe` forces that first call here, inside the guarded region, so the
      * failure lands in the `catch` and yields Absent.
      */
    private lazy val bindings: Maybe[MacosBindings] =
        try
            val b = Ffi.load[MacosBindings]
            loadProbe(b, cpuOut)
            Present(b)
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => Absent

    private[machine] def readCpu(b: MacosBindings)(using AllowUnsafe): Unit =
        if b.hostCpuLoad(cpuOut) == 0 then
            val user   = cpuOut.getLong(0)
            val system = cpuOut.getLong(1)
            val idle   = cpuOut.getLong(2)
            val nice   = cpuOut.getLong(3)
            h.cpuUser.observe(user)
            h.cpuSystem.observe(system)
            h.cpuIdle.observe(idle)
            h.cpuTotal.observe(user + system + idle + nice)
        end if
    end readCpu

    private[machine] def readMemory(b: MacosBindings)(using AllowUnsafe): Unit =
        if b.vmStatistics(memOut) == 0 then
            h.memTotal.set(memOut.getLong(0))
            h.memFree.observe(memOut.getLong(1))
            h.memAvailable.observe(memOut.getLong(2))
        end if
    end readMemory

    private[machine] def readSwap(b: MacosBindings)(using AllowUnsafe): Unit =
        if b.swapUsage(swapOut) == 0 then
            h.swapTotal.set(swapOut.getLong(0))
            h.swapFree.observe(swapOut.getLong(1))
        end if
    end readSwap

    private[machine] def readLoad(b: MacosBindings)(using AllowUnsafe): Unit =
        if b.getloadavg(loadOut, 3) == 3 then
            h.loadOne.set(loadOut.getDouble(0))
            h.loadFive.set(loadOut.getDouble(1))
            h.loadFifteen.set(loadOut.getDouble(2))
        end if
    end readLoad

end MachineMacos

private[machine] object MachineMacos:

    /** Forces the generated `MacosBindings` impl's lazy native-library load. `MachineMacos.bindings`
      * invokes it inside its guarded region so a shim-load failure degrades to Absent instead of
      * throwing from the first tick read.
      */
    trait LoadProbe:
        def apply(bindings: MacosBindings, scratch: Buffer[Long])(using AllowUnsafe): Unit

    /** The production probe: one real `host_cpu_load` read triggers the impl's lazy load. `scratch` is
      * the reader's own cpu out-buffer, overwritten by its first real read.
      */
    object RealLoad extends LoadProbe:
        def apply(bindings: MacosBindings, scratch: Buffer[Long])(using AllowUnsafe): Unit =
            discard(bindings.hostCpuLoad(scratch))
    end RealLoad

end MachineMacos
