package kyo.internal

import kyo.*
import kyo.kernel.Isolate

abstract private[kyo] class FlowInterpreter[S]:

    def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]): V < S

    def onOutput[V](name: String, computation: V < Sync, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]): V < S

    /** Read a stored field by name. Returns Absent if the field does not exist. */
    def getField[V](name: String)(using Tag[V], Json[V]): Maybe[V] < S

    def onStep(name: String, computation: Unit < Sync, frame: Frame, meta: Flow.Meta): Unit < S

    def onSleep(name: String, duration: Duration, frame: Frame, meta: Flow.Meta): Unit < S

    def onZip(
        left: Record[Any] < S,
        right: Record[Any] < S,
        ctx: Record[Any],
        isolate: Isolate[Any, Abort[FlowException] & Async, Any]
    ): Record[Any] < S

    def onRace(
        left: Record[Any] < S,
        right: Record[Any] < S,
        isolate: Isolate[Any, Abort[FlowException] & Async, Any]
    ): Record[Any] < S

    /** Check if the execution has been cancelled. Used by loops to break between iterations. */
    def checkCancelled: Boolean < S = false

    /** Called before compensation handlers run. */
    def onCompensationStart: Unit < S = ()

    /** Called after all compensation handlers complete. */
    def onCompensationComplete: Unit < S = ()

    /** Called when a compensation handler throws. */
    def onCompensationFailed(error: Throwable): Unit < S = ()

end FlowInterpreter
