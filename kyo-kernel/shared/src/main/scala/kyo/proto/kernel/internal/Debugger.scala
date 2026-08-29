package kyo.proto.kernel.internal

/** The walker's observability seam: one installed instance, one no-op default, and nothing else in the kernel.
  *
  * Every hook is a pure observation returning Unit, and every site reaches it through the companion's inline forwarders, gated on the
  * `enabled` constant. The zero-cost mechanism is compile-time erasure, not devirtualization: with the constant false, the typer folds
  * each forwarder call to nothing, so the emitted bytecode carries no trace of the hooks, node constructors leak no reference to `this`,
  * and there is nothing left for the JIT to prove dead. An instrumented build (the constant flipped to true) dispatches through the
  * installed instance; real implementations live outside the kernel (the demo installs a console tracer). Hooks receive structured
  * operands, never strings, so no formatting or stack capture happens unless an installed implementation asks for it.
  */
abstract class Debugger:

    /** Whether a strict application may run inline; false routes it through the eval. The strict-path gate consulted by
      * `Safepoint.enterPark`: a session drains its eval's slot so every strict application lands on that cold path, keeping the hot
      * `Safepoint.enter` byte-identical for everyone else. Frameless on purpose, since even a frame operand alone taxes every call site; a
      * session that wants frames routes the application and reads it in the eval. Consulted per application, so a session that refuses must
      * allow the delivery retry or the application defers against its own refusal forever.
      */
    def enter(): Boolean = true

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

    /** A region told its extent was abandoned. */
    def onRelease(handler: Any, ex: Any): Unit = ()

    /** A throw answered by a region's recover. */
    def onRecover(handler: Any, ex: Any): Unit = ()

    /** A suspend delivered to its handler. */
    def onHandle(suspend: Any, handler: Any, state: Any): Unit = ()

    /** The value a handler clause produced. */
    def onResult(value: Any): Unit = ()
end Debugger

object Debugger:

    /** The compile-time gate. The forwarders below splice their dispatch only when this constant is true; false folds every call site to
      * nothing at the typer, so a production build carries no bytecode at the sites and no `this` escapes the node constructors.
      * Instrumenting a build is a source edit here.
      */
    inline val enabled = true

    // a plain module cell: the proto runs single-threaded demos
    private var current: Debugger = Noop

    def install(d: Debugger): Unit = current = d

    def uninstall(): Unit = current = Noop

    def get: Debugger = current

    inline def onAlloc(value: Any): Unit                              = inline if enabled then get.onAlloc(value)
    inline def onUnfused(arrow: Any): Unit                            = inline if enabled then get.onUnfused(arrow)
    inline def onLoop(value: Any, contA: Any, contB: Any): Unit       = inline if enabled then get.onLoop(value, contA, contB)
    inline def onContext(suspend: Any, state: Any): Unit              = inline if enabled then get.onContext(suspend, state)
    inline def onContextDefault(suspend: Any, state: Any): Unit       = inline if enabled then get.onContextDefault(suspend, state)
    inline def onRegionEnter(handler: Any, state: Any): Unit          = inline if enabled then get.onRegionEnter(handler, state)
    inline def onRegionExit(handler: Any, result: Any): Unit          = inline if enabled then get.onRegionExit(handler, result)
    inline def onForeign(suspend: Any, handler: Any): Unit            = inline if enabled then get.onForeign(suspend, handler)
    inline def onRecover(handler: Any, ex: Any): Unit                 = inline if enabled then get.onRecover(handler, ex)
    inline def onRelease(handler: Any, ex: Any): Unit                 = inline if enabled then get.onRelease(handler, ex)
    inline def onHandle(suspend: Any, handler: Any, state: Any): Unit = inline if enabled then get.onHandle(suspend, handler, state)
    inline def onResult(value: Any): Unit                             = inline if enabled then get.onResult(value)

    object Noop extends Debugger
end Debugger
