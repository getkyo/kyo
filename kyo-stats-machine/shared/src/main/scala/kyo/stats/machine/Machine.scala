package kyo.stats.machine

import kyo.*

/** Host-metrics reader abstraction with one implementation per operating system.
  *
  * A `Machine` reads the host once per sampler tick and writes each decoded PRIMITIVE straight into the
  * retained `MachineHandles` cell it belongs to. No value carries a reading: there is no carrier object, no
  * boxed absent value, and nothing allocated per observation. An unavailable metric is the primitive
  * `Path.ReadHandle.AbsentLong` (or `Double.NaN` for the two fixed-point families), which its cell skips, so
  * it is never recorded and its series is never registered.
  *
  * The implementation for the running OS is selected and CONSTRUCTED once, at sampler init, from
  * `System.operatingSystem`, and it retains everything a tick needs: its file slots, its decode callbacks
  * and its FFI out-buffers. Every implementation compiles on every platform because it composes only
  * cross-platform kyo primitives (`kyo.Path` for files, a per-OS kyo-ffi binding for syscalls, `kyo.Stat`).
  */
private[kyo] trait Machine:
    /** Reads every non-disk metric family for one tick, straight into the retained cells. Disk is split out
      * to `readDisks` because disk syscalls are the one genuinely blockable read: a slow or dead mount must
      * not stall the fast in-kernel and proc reads that make up the rest of the tick.
      */
    def read()(using AllowUnsafe): Unit

    /** Reads the per-mount disk metrics for one tick, straight into the retained per-store cells. The
      * sampler runs this on its own timed fiber, off the tick loop's fiber.
      */
    def readDisks()(using AllowUnsafe): Unit

    /** Releases every retained FFI out-buffer and native resource this reader owns. Invoked once by the
      * sampler's Scope finalizer, after the tick fiber has been interrupted.
      */
    def close()(using AllowUnsafe): Unit
end Machine

private[kyo] object Machine:

    /** Selects and constructs the implementation for the current OS once, binding it to the handle set it
      * writes into and to the sampler that owns its file slots. An OS with no dedicated reader gets
      * `NullMachine`, which writes no cell at all, so no `machine.*` series is ever registered on it:
      * honest graceful degradation, never a fake zero.
      */
    def forOs(os: System.OS, handles: MachineHandles, sampler: MachineSampler)(using AllowUnsafe): Machine =
        os match
            case System.OS.Linux   => new MachineLinux(handles, sampler)
            case System.OS.MacOS   => new MachineMacos(handles, sampler)
            case System.OS.Windows => new MachineWindows(handles, sampler)
            case _                 => NullMachine

    /** The reader for an OS with no dedicated implementation: it writes nothing, so every family stays
      * unregistered and every series absent. Both reads are defined here rather than defaulted on the
      * trait, so a no-metric outcome has exactly one home.
      */
    private[machine] object NullMachine extends Machine:
        def read()(using AllowUnsafe): Unit      = ()
        def readDisks()(using AllowUnsafe): Unit = ()
        def close()(using AllowUnsafe): Unit     = ()
    end NullMachine

end Machine
