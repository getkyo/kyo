package kyo

import kyo.Async
import kyo.Closed
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Structure
import kyo.Sync

/** Per-request context supplied to every [[JsonRpcMethod]] handler by the endpoint.
  *
  * Provides access to:
  *  - `cancelled`: a `Fiber.Promise` that is completed when the peer sends a cancellation for
  *    the current request.
  *  - `requestId`: the JSON-RPC id of the incoming request, or `Absent` for notifications.
  *  - `extras`: protocol-specific extra fields from the incoming envelope, if any.
  *  - `progress`: reports a progress notification back to the caller via `$.progress` (LSP) or
  *    `notifications/progress` (MCP), depending on the active `ProgressPolicy`.
  *
  * @see [[JsonRpcMethod]]
  * @see [[ProgressPolicy]]
  */
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
