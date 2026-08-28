package kyo.proto.kernel.internal

/** The walker's observability seam: one installed instance, one no-op default, and nothing else in the kernel.
  *
  * Every hook is a pure observation returning Unit, so the no-op makes the kernel behave exactly as if the class did not exist. The
  * zero-cost mechanism is class hierarchy analysis, not a flag: real implementations live outside the kernel (the demo installs a console
  * tracer), so a production classpath never loads one, the no-op defaults stay monomorphic, and every call site devirtualizes and inlines
  * to nothing. Hooks receive structured operands, never strings, so no formatting or stack capture happens unless an installed
  * implementation asks for it.
  *
  * The eval reads the installed instance once at entry; the constructor hooks (onAlloc, onUnfused) read the cell directly since they fire
  * outside any eval extent.
  */
abstract class Debugger:

    /** A node or outcome allocated; fired from the constructors. */
    def onAlloc(value: Any): Unit = ()

    /** An arrow applied through the unfused one-argument entry. */
    def onUnfused(arrow: Any): Unit = ()

    /** The eval loop observing its current value and pending continuations. */
    def onLoop(value: Any, contA: Any, contB: Any): Unit = ()

    /** A context operation answered from the propagated context. */
    def onContext(suspend: Any, state: Any): Unit = ()

    /** A context operation answered by the boundary default. */
    def onContextDefault(suspend: Any, state: Any): Unit = ()

    /** A region opened for a handler. */
    def onRegionEnter(handler: Any, state: Any): Unit = ()

    /** A region closed with its result. */
    def onRegionExit(handler: Any, result: Any): Unit = ()

    /** A suspend bubbling past a region that does not handle it. */
    def onForeign(suspend: Any, handler: Any): Unit = ()

    /** A suspend delivered to its handler. */
    def onHandle(suspend: Any, handler: Any, state: Any): Unit = ()

    /** The value a handler clause produced. */
    def onResult(value: Any): Unit = ()
end Debugger

object Debugger:

    // a plain module cell: the proto runs single-threaded demos, and the eval reads the debugger once at entry
    private var current: Debugger = Noop

    def install(d: Debugger): Unit = current = d

    def uninstall(): Unit = current = Noop

    def get: Debugger = current

    object Noop extends Debugger
end Debugger
