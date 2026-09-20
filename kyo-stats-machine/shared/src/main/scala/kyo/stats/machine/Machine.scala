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

    /** Whether a failure degrades the family that raised it, instead of propagating out of the reader.
      *
      * `scala.util.control.NonFatal` alone is the wrong predicate here, and it is wrong for exactly the
      * failure this module exists to absorb. A native library that is missing or unloadable surfaces as
      * `ExceptionInInitializerError` the first time the generated binding's class initializes, as
      * `NoClassDefFoundError` on every later touch of that now-poisoned class, and as `UnsatisfiedLinkError`
      * when the library resolves but a symbol does not. All three are `LinkageError`s, which `NonFatal`
      * excludes, so a guard written with `NonFatal` lets the one error it was written for escape.
      *
      * A genuinely fatal error (`OutOfMemoryError`, `StackOverflowError`, an interrupt) still propagates:
      * degrading on those would hide a process-level problem behind a missing metric.
      */
    private[machine] def degradable(ex: Throwable): Boolean =
        scala.util.control.NonFatal(ex) || ex.isInstanceOf[LinkageError]

    /** Already-reported degradations, keyed by the label passed to `reportDegraded`. */
    private val reported = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]

    /** Reports that `what` degraded to absent, at most once per label for the process lifetime.
      *
      * A telemetry layer that cannot read the host must say so once, then stay quiet: the failure repeats on
      * every tick, so reporting each occurrence would write a line per second into an application that never
      * asked for host metrics. One `warn` line carries the cause's own message, which for a load failure is
      * the searched paths, the missing symbol and the override property, and the same line is emitted on
      * every platform, including JS where the failure was previously invisible.
      *
      * The cause is rendered into the message rather than handed to the logger as a `Throwable`: the module's
      * contract is a quietly missing metric, never a stack trace in an unrelated application's stderr.
      *
      * @return
      *   true when this call was the one that reported, false when the label had already been reported.
      */
    private[machine] def reportDegraded(what: String, ex: Throwable)(using AllowUnsafe): Boolean =
        if reported.putIfAbsent(what, java.lang.Boolean.TRUE) ne null then false
        else
            given Frame = Frame.internal
            Log.live.unsafe.warn(degradedMessage(what, ex))
            true

    /** The one line a degraded family reports.
      *
      * It carries the cause's own rendering, which for a native-load failure is the searched paths, the
      * missing symbol and the override property the loader documents. That is what makes the same line
      * useful on every platform: the JVM's `FfiLoadError` message is a model of what a good load failure
      * says, and this puts it in front of a JS user, where the failure used to be entirely silent.
      */
    private[machine] def degradedMessage(what: String, ex: Throwable): String =
        s"kyo-stats-machine: $what is unavailable on this host, so its metrics stay absent. Cause: $ex"

end Machine
