package kyo.proto.kernel.internal

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

    inline val enabled = false

    private var current: Debugger = Noop

    def install(d: Debugger): Unit = current = d

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
