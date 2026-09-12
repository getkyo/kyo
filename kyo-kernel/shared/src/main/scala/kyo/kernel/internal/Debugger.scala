package kyo.kernel.internal

import kyo.CompileTimeFlag
import kyo.bug
import kyo.kernel.<
import kyo.kernel.Arrow

/** Hooks the evaluator calls as it runs, for tracing what a computation actually did.
  *
  * Every hook is a no-op by default, so an implementation overrides only what it wants to see. Nothing here is on in a normal build: the
  * call sites go through [[Debugger.enabled]], a compile-time flag that is false, and an `inline if` on a false constant leaves no trace in
  * the bytecode. Turning it on is a recompile, not a runtime switch.
  *
  * That is deliberate. These hooks sit on the hottest paths in the module, and a runtime check on each would show up in the benchmarks
  * whether or not a debugger was installed.
  */
abstract private[kyo] class Debugger:

    def enter(): Boolean = true

    def onAlloc(value: Any): Unit = ()

    def onUnfused(arrow: Arrow[?, ?, ?]): Unit = ()

    def onLoop(value: Any < Nothing, contA: Arrow[?, ?, ?], contB: Arrow[?, ?, ?]): Unit = ()

    def onContext(node: Pending[?, ?], state: Any): Unit = ()

    def onRegionEnter(handler: Handler[?, ?, ?], state: Any): Unit = ()

    def onRegionExit(handler: Handler[?, ?, ?], result: Any): Unit = ()

    def onForeign(suspend: Pending.Suspend[?, ?, ?, ?], handler: Handler[?, ?, ?]): Unit = ()

    def onRelease(handler: Handler[?, ?, ?], ex: Throwable): Unit = ()

    def onRecover(handler: Handler[?, ?, ?], ex: Throwable): Unit = ()

    def onHandle(suspend: Pending.Suspend[?, ?, ?, ?], handler: Handler[?, ?, ?], state: Any): Unit = ()

    def onResult(value: Any): Unit = ()
end Debugger

private[kyo] object Debugger:

    /** Resolved on the compiling JVM, not the running one, so a false value erases every hook below rather than branching over it. */
    inline def enabled: Boolean = CompileTimeFlag.boolean("kyo.kernel.internal.Debugger.enabled", false)

    private var current: Debugger = Noop

    /** Installs `d` as the debugger the hooks call.
      *
      * Refuses in a build where the hooks are erased, and refuses to replace one that is already installed, because both of those produce an
      * empty trace that reads as though the code under inspection never ran.
      */
    def install(d: Debugger): Unit =
        if !enabled then
            bug(
                "Debugger.install called in a build compiled without the debugger. The hooks are erased at compile time, so nothing " +
                    "would be recorded. Recompile with -Dkyo.kernel.internal.Debugger.enabled=true."
            )
        end if
        if current ne Noop then
            bug(
                s"Debugger.install called while $current is already installed. A debugger is global, so the second would silently " +
                    "replace the first. Call Debugger.uninstall() before installing another."
            )
        end if
        current = d
    end install

    /** Removes the installed debugger. Refuses when there is none, since that means an uninstall is running without its install. */
    def uninstall(): Unit =
        if current eq Noop then
            bug("Debugger.uninstall called with no debugger installed.")
        current = Noop
    end uninstall

    def get: Debugger = current

    inline def whenEnabled(inline f: Unit): Unit = inline if enabled then f

    inline def onAlloc(value: Any): Unit              = inline if enabled then get.onAlloc(value)
    inline def onUnfused(arrow: Arrow[?, ?, ?]): Unit = inline if enabled then get.onUnfused(arrow)

    inline def onLoop(value: Any < Nothing, contA: Arrow[?, ?, ?], contB: Arrow[?, ?, ?]): Unit =
        inline if enabled then get.onLoop(value, contA, contB)

    inline def onContext(node: Pending[?, ?], state: Any): Unit           = inline if enabled then get.onContext(node, state)
    inline def onRegionEnter(handler: Handler[?, ?, ?], state: Any): Unit = inline if enabled then get.onRegionEnter(handler, state)
    inline def onRegionExit(handler: Handler[?, ?, ?], result: Any): Unit = inline if enabled then get.onRegionExit(handler, result)
    inline def onRecover(handler: Handler[?, ?, ?], ex: Throwable): Unit  = inline if enabled then get.onRecover(handler, ex)
    inline def onRelease(handler: Handler[?, ?, ?], ex: Throwable): Unit  = inline if enabled then get.onRelease(handler, ex)
    inline def onResult(value: Any): Unit                                 = inline if enabled then get.onResult(value)

    inline def onForeign(suspend: Pending.Suspend[?, ?, ?, ?], handler: Handler[?, ?, ?]): Unit =
        inline if enabled then get.onForeign(suspend, handler)

    inline def onHandle(suspend: Pending.Suspend[?, ?, ?, ?], handler: Handler[?, ?, ?], state: Any): Unit =
        inline if enabled then get.onHandle(suspend, handler, state)

    object Noop extends Debugger
end Debugger
