package kyo

import kyo.Abort
import kyo.Async
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Result
import kyo.Schema
import kyo.Structure

/** Describes a single JSON-RPC route binding: a name, a kind (Request or Notification),
  * and a typed handler function.
  *
  * Use the companion factories to construct instances:
  *  - [[JsonRpcRoute.apply]] for request/response routes.
  *  - [[JsonRpcRoute.notification]] for fire-and-forget notifications.
  *
  * The handler type parameter `S` captures the effect set required by the handler. The
  * framework constrains `S` to include at minimum `Async & Abort[JsonRpcError]`. Pass the
  * resulting `JsonRpcRoute` to `JsonRpcHandler.init`.
  *
  * @tparam S the effect set of the handler; must satisfy `(Async & Abort[JsonRpcError]) <:< S`
  * @see JsonRpcHandler
  */
sealed trait JsonRpcRoute[+S]:
    def name: String
    def kind: JsonRpcRoute.Kind
    // Stream.scala:48 sealed-protocol with framework-only abstract members
    private[kyo] def schemaIn: Schema[?]
    // Stream.scala:48 sealed-protocol with framework-only abstract members
    private[kyo] def schemaOut: Schema[?]
    // Stream.scala:48 sealed-protocol with framework-only abstract members
    private[kyo] def handle(params: Structure.Value, ctx: JsonRpcRoute.Context)(using
        Frame
    ): Structure.Value < (Async & Abort[JsonRpcError])
end JsonRpcRoute

object JsonRpcRoute:
    enum Kind derives CanEqual:
        case Request, Notification

    /** Per-request context supplied to every [[JsonRpcRoute]] handler by the handler.
      *
      * Provides access to:
      *  - `cancelled`: a `Fiber.Promise` that is completed when the peer sends a cancellation for
      *    the current request.
      *  - `requestId`: the JSON-RPC id of the incoming request, or `Absent` for notifications.
      *  - `extras`: protocol-specific extra fields from the incoming envelope, if any.
      *  - `progress`: reports a progress notification back to the caller via `$.progress` (LSP) or
      *    `notifications/progress` (MCP), depending on the active `ProgressPolicy`.
      *
      * @see [[JsonRpcRoute]]
      * @see [[JsonRpcHandler.ProgressPolicy]]
      */
    // Hub.scala:22 smart-constructor pattern; framework creates instances via forTest or JsonRpcEndpointImpl
    final class Context private[kyo] (
        val cancelled: Fiber.Promise[Unit, Sync],
        val requestId: Maybe[JsonRpcId],
        val extras: Maybe[Structure.Value],
        private[kyo] val progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]
    ):
        def progress(value: Structure.Value)(using Frame): Unit < (Async & Abort[Closed]) =
            progressSink match
                case Present(sink) => sink(value)
                case Absent        => Sync.defer(())
    end Context

    object Context:
        // test-only construction escape hatch consumed by JsonRpcRouteTest
        private[kyo] def forTest(
            cancelled: Fiber.Promise[Unit, Sync],
            requestId: Maybe[JsonRpcId],
            extras: Maybe[Structure.Value],
            progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]
        ): Context = new Context(cancelled, requestId, extras, progressSink)
    end Context

    def apply[In: Schema, Out: Schema, S](name: String)(handler: (In, JsonRpcRoute.Context) => Out < S)(
        using
        Frame,
        (Async & Abort[JsonRpcError]) <:< S
    ): JsonRpcRoute[S] =
        val capturedName                   = name
        val capturedSchemaIn: Schema[In]   = summon[Schema[In]]
        val capturedSchemaOut: Schema[Out] = summon[Schema[Out]]
        val ev                             = summon[(Async & Abort[JsonRpcError]) <:< S]
        new RequestRoute(capturedName, capturedSchemaIn, capturedSchemaOut, handler, ev)
    end apply

    def notification[In: Schema, S](name: String)(handler: (In, JsonRpcRoute.Context) => Unit < S)(
        using
        Frame,
        (Async & Abort[JsonRpcError]) <:< S
    ): JsonRpcRoute[S] =
        val capturedName                 = name
        val capturedSchemaIn: Schema[In] = summon[Schema[In]]
        val ev                           = summon[(Async & Abort[JsonRpcError]) <:< S]
        new NotificationRoute(capturedName, capturedSchemaIn, handler, ev)
    end notification

    /** Dispatches `params` to the named route in `routes`. Returns Absent for unknown route.
      * Public reach-in for non-engine consumers (one-shot stdio loop, HTTP POST endpoints,
      * custom routers); keeps `JsonRpcRoute.handle` private[kyo]. For Notification kind the
      * inner result is `Structure.Value.Null` after the handler completes.
      */
    def dispatch[S](
        name: String,
        routes: Seq[JsonRpcRoute[S]],
        params: Structure.Value,
        ctx: JsonRpcRoute.Context
    )(using Frame, (Async & Abort[JsonRpcError]) <:< S): Maybe[Structure.Value < (Async & Abort[JsonRpcError])] =
        val routeMap: Map[String, JsonRpcRoute[S]] =
            routes.iterator.map(m => (m.name, m)).toMap
        // Map.get returns scala.Option; match arms are interop, not kyo code
        routeMap.get(name) match
            // scala.Option arm; interop with Map.get
            case Some(route) => Present(route.handle(params, ctx))
            // scala.Option arm; interop with Map.get
            case None => Absent
        end match
    end dispatch

    final private class RequestRoute[In, Out, S](
        val name: String,
        in: Schema[In],
        out: Schema[Out],
        handler: (In, JsonRpcRoute.Context) => Out < S,
        ev: (Async & Abort[JsonRpcError]) <:< S
    ) extends JsonRpcRoute[S]:
        val kind                              = Kind.Request
        private[kyo] val schemaIn: Schema[?]  = in
        private[kyo] val schemaOut: Schema[?] = out

        private[kyo] def handle(params: Structure.Value, ctx: JsonRpcRoute.Context)(using
            fr: Frame
        ): Structure.Value < (Async & Abort[JsonRpcError]) =
            val computation: Structure.Value < (S & Abort[JsonRpcError]) =
                Structure.decode[In](params)(using in, fr) match
                    case Result.Success(decoded) =>
                        Abort.run[JsonRpcError](handler(decoded, ctx)).map:
                            case Result.Success(result) =>
                                Structure.encode[Out](result)(using out, fr)
                            case Result.Failure(err) =>
                                Abort.fail(err)
                            case Result.Panic(t) =>
                                Abort.fail(JsonRpcHandlerPanicError(name, t))
                    case Result.Failure(e) =>
                        Abort.fail(JsonRpcInvalidParamsError(
                            name,
                            Absent,
                            Chunk(JsonRpcInvalidParamsError.ParamError(
                                "params",
                                JsonRpcInvalidParamsError.Problem.TypeMismatch("expected", e.getMessage)
                            ))
                        ))
                    case Result.Panic(t) =>
                        Abort.fail(JsonRpcHandlerPanicError(name, t))
            ev.liftContra[[X] =>> Structure.Value < (X & Abort[JsonRpcError])].apply(computation)
        end handle
    end RequestRoute

    final private class NotificationRoute[In, S](
        val name: String,
        in: Schema[In],
        handler: (In, JsonRpcRoute.Context) => Unit < S,
        ev: (Async & Abort[JsonRpcError]) <:< S
    ) extends JsonRpcRoute[S]:
        val kind                              = Kind.Notification
        private[kyo] val schemaIn: Schema[?]  = in
        private[kyo] val schemaOut: Schema[?] = summon[Schema[Unit]]

        private[kyo] def handle(params: Structure.Value, ctx: JsonRpcRoute.Context)(using
            fr: Frame
        ): Structure.Value < (Async & Abort[JsonRpcError]) =
            val computation: Structure.Value < (S & Abort[JsonRpcError]) =
                Structure.decode[In](params)(using in, fr) match
                    case Result.Success(decoded) =>
                        Abort.run[JsonRpcError](handler(decoded, ctx)).map:
                            case Result.Success(_) =>
                                (Structure.Value.Null: Structure.Value)
                            case Result.Failure(err) =>
                                Abort.fail(err)
                            case Result.Panic(t) =>
                                Abort.fail(JsonRpcHandlerPanicError(name, t))
                    case Result.Failure(e) =>
                        Abort.fail(JsonRpcInvalidParamsError(
                            name,
                            Absent,
                            Chunk(JsonRpcInvalidParamsError.ParamError(
                                "params",
                                JsonRpcInvalidParamsError.Problem.TypeMismatch("expected", e.getMessage)
                            ))
                        ))
                    case Result.Panic(t) =>
                        Abort.fail(JsonRpcHandlerPanicError(name, t))
            ev.liftContra[[X] =>> Structure.Value < (X & Abort[JsonRpcError])].apply(computation)
        end handle
    end NotificationRoute
end JsonRpcRoute
