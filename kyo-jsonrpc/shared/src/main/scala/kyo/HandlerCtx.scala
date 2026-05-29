// PUBLIC handler-context receiver consumed by user JsonRpcMethod handlers
package kyo

import kyo.Async
import kyo.Closed
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Structure
import kyo.Sync

// Hub.scala:22 smart-constructor pattern; framework creates instances via forTest or JsonRpcEndpointImpl
final class HandlerCtx private[kyo] (
    val cancelled: Fiber.Promise[Unit, Sync],
    val requestId: Maybe[JsonRpcId],
    val extras: Maybe[Structure.Value],
    private[kyo] val progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]
):
    def progress(value: Structure.Value)(using Frame): Unit < (Async & Abort[Closed]) =
        progressSink match
            case Present(sink) => sink(value)
            case Absent        => Sync.defer(())
end HandlerCtx

object HandlerCtx:
    // test-only construction escape hatch consumed by JsonRpcMethodTest
    private[kyo] def forTest(
        cancelled: Fiber.Promise[Unit, Sync],
        requestId: Maybe[JsonRpcId],
        extras: Maybe[Structure.Value],
        progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]
    ): HandlerCtx = new HandlerCtx(cancelled, requestId, extras, progressSink)
end HandlerCtx
