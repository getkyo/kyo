package kyo.scheduler

abstract class IOPromisePlatformSpecific:

    class VarHandle:
        def compareAndSet(a: Any, b: Any, c: Any): Boolean = ???

    private[scheduler] val stateHandle: VarHandle = null
end IOPromisePlatformSpecific
