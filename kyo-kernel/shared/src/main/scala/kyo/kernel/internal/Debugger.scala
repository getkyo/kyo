package kyo.kernel.internal

import kyo.CompileTimeFlag

/** Hooks the evaluator calls as it runs, for tracing what a computation actually did.
  *
  * Every hook is a no-op by default, so an implementation overrides only what it wants to see. Nothing here is on in a normal build: the
  * call sites go through [[Debugger.enabled]], a compile-time flag that is false, and an `inline if` on a false constant leaves no trace in
  * the bytecode. Turning it on is a recompile, not a runtime switch.
  *
  * That is deliberate. These hooks sit on the hottest paths in the module, and a runtime check on each would show up in the benchmarks
  * whether or not a debugger was installed.
  */
// TODO let's properly type the apis here, there's no need to use ANy for everything
abstract private[kyo] class Debugger:

    def enter(): Boolean = true

    def onAlloc(value: Any): Unit = ()

    def onUnfused(arrow: Any): Unit = ()

    def onLoop(value: Any, contA: Any, contB: Any): Unit = ()

    def onContext(suspend: Any, state: Any): Unit = ()

    def onRegionEnter(handler: Any, state: Any): Unit = ()

    def onRegionExit(handler: Any, result: Any): Unit = ()

    def onForeign(suspend: Any, handler: Any): Unit = ()

    def onRelease(handler: Any, ex: Any): Unit = ()

    def onRecover(handler: Any, ex: Any): Unit = ()

    def onHandle(suspend: Any, handler: Any, state: Any): Unit = ()

    def onResult(value: Any): Unit = ()
end Debugger

private[kyo] object Debugger:

    /** Resolved on the compiling JVM, not the running one, so a false value erases every hook below rather than branching over it. */
    inline def enabled: Boolean = CompileTimeFlag.boolean("kyo.kernel.internal.Debugger.enabled", false)

    private var current: Debugger = Noop

    // TODO let's add logging here and fail if there's different one installed already. Also fail if enabled is false
    def install(d: Debugger): Unit = current = d

    // TODO log and fail if nothing is installed
    def uninstall(): Unit = current = Noop

    def get: Debugger = current

    inline def whenEnabled(inline f: Unit): Unit = inline if enabled then f

    inline def onAlloc(value: Any): Unit                              = inline if enabled then get.onAlloc(value)
    inline def onUnfused(arrow: Any): Unit                            = inline if enabled then get.onUnfused(arrow)
    inline def onLoop(value: Any, contA: Any, contB: Any): Unit       = inline if enabled then get.onLoop(value, contA, contB)
    inline def onContext(suspend: Any, state: Any): Unit              = inline if enabled then get.onContext(suspend, state)
    inline def onRegionEnter(handler: Any, state: Any): Unit          = inline if enabled then get.onRegionEnter(handler, state)
    inline def onRegionExit(handler: Any, result: Any): Unit          = inline if enabled then get.onRegionExit(handler, result)
    inline def onForeign(suspend: Any, handler: Any): Unit            = inline if enabled then get.onForeign(suspend, handler)
    inline def onRecover(handler: Any, ex: Any): Unit                 = inline if enabled then get.onRecover(handler, ex)
    inline def onRelease(handler: Any, ex: Any): Unit                 = inline if enabled then get.onRelease(handler, ex)
    inline def onHandle(suspend: Any, handler: Any, state: Any): Unit = inline if enabled then get.onHandle(suspend, handler, state)
    inline def onResult(value: Any): Unit                             = inline if enabled then get.onResult(value)

    object Noop extends Debugger
end Debugger
