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

/** Describes a single JSON-RPC method binding: a name, a kind (Request or Notification),
  * and a typed handler function.
  *
  * Use the companion factories to construct instances:
  *  - [[JsonRpcMethod.apply]] for request/response methods.
  *  - [[JsonRpcMethod.notification]] for fire-and-forget notifications.
  *
  * The handler type parameter `S` captures the effect set required by the handler. The
  * framework constrains `S` to include at minimum `Async & Abort[JsonRpcError]`. Pass the
  * resulting `JsonRpcMethod` to `JsonRpcEndpoint.init`.
  *
  * @tparam S the effect set of the handler; must satisfy `(Async & Abort[JsonRpcError]) <:< S`
  * @see JsonRpcEndpoint
  */
sealed trait JsonRpcMethod[+S]:
    def name: String
    def kind: JsonRpcMethod.Kind
    // Stream.scala:48 sealed-protocol with framework-only abstract members
    private[kyo] def schemaIn: Schema[?]
    // Stream.scala:48 sealed-protocol with framework-only abstract members
    private[kyo] def schemaOut: Schema[?]
    // Stream.scala:48 sealed-protocol with framework-only abstract members
    private[kyo] def handle(params: Structure.Value, ctx: JsonRpcMethod.Context)(using
        Frame
    ): Structure.Value < (Async & Abort[JsonRpcError])
end JsonRpcMethod

object JsonRpcMethod:
    enum Kind derives CanEqual:
        case Request, Notification

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
      * @see [[JsonRpcEndpoint.ProgressPolicy]]
      */
    // Hub.scala:22 smart-constructor pattern; framework creates instances via forTest or JsonRpcEndpointImpl
    final class Context private[kyo] (
        val cancelled: Fiber.Promise[Unit, Sync],
        val requestId: Maybe[JsonRpcEnvelope.Id],
        val extras: Maybe[Structure.Value],
        private[kyo] val progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]
    ):
        def progress(value: Structure.Value)(using Frame): Unit < (Async & Abort[Closed]) =
            progressSink match
                case Present(sink) => sink(value)
                case Absent        => Sync.defer(())
    end Context

    object Context:
        // test-only construction escape hatch consumed by JsonRpcMethodTest
        private[kyo] def forTest(
            cancelled: Fiber.Promise[Unit, Sync],
            requestId: Maybe[JsonRpcEnvelope.Id],
            extras: Maybe[Structure.Value],
            progressSink: Maybe[Structure.Value => Unit < (Async & Abort[Closed])]
        ): Context = new Context(cancelled, requestId, extras, progressSink)
    end Context

    def apply[In: Schema, Out: Schema, S](name: String)(handler: (In, JsonRpcMethod.Context) => Out < S)(
        using
        Frame,
        (Async & Abort[JsonRpcError]) <:< S
    ): JsonRpcMethod[S] =
        val capturedName                   = name
        val capturedSchemaIn: Schema[In]   = summon[Schema[In]]
        val capturedSchemaOut: Schema[Out] = summon[Schema[Out]]
        val ev                             = summon[(Async & Abort[JsonRpcError]) <:< S]
        new RequestMethod(capturedName, capturedSchemaIn, capturedSchemaOut, handler, ev)
    end apply

    def apply[In: Schema, Out: Schema, S](name: String)(handler: In => Out < S)(
        using
        Frame,
        (Async & Abort[JsonRpcError]) <:< S
    ): JsonRpcMethod[S] =
        apply[In, Out, S](name)((in, _ctx) => handler(in))

    def notification[In: Schema, S](name: String)(handler: (In, JsonRpcMethod.Context) => Unit < S)(
        using
        Frame,
        (Async & Abort[JsonRpcError]) <:< S
    ): JsonRpcMethod[S] =
        val capturedName                 = name
        val capturedSchemaIn: Schema[In] = summon[Schema[In]]
        val ev                           = summon[(Async & Abort[JsonRpcError]) <:< S]
        new NotificationMethod(capturedName, capturedSchemaIn, handler, ev)
    end notification

    /** Dispatches `params` to the named method in `methods`. Returns Absent for unknown method.
      * Public reach-in for non-engine consumers (one-shot stdio loop, HTTP POST endpoints,
      * custom routers); keeps `JsonRpcMethod.handle` private[kyo]. For Notification kind the
      * inner result is `Structure.Value.Null` after the handler completes.
      */
    def dispatch[S](
        name: String,
        methods: Seq[JsonRpcMethod[S]],
        params: Structure.Value,
        ctx: JsonRpcMethod.Context
    )(using Frame, (Async & Abort[JsonRpcError]) <:< S): Maybe[Structure.Value < (Async & Abort[JsonRpcError])] =
        val methodMap: Map[String, JsonRpcMethod[S]] =
            methods.iterator.map(m => (m.name, m)).toMap
        // Map.get returns scala.Option; match arms are interop, not kyo code
        methodMap.get(name) match
            // scala.Option arm; interop with Map.get
            case Some(method) => Present(method.handle(params, ctx))
            // scala.Option arm; interop with Map.get
            case None => Absent
        end match
    end dispatch

    final private class RequestMethod[In, Out, S](
        val name: String,
        in: Schema[In],
        out: Schema[Out],
        handler: (In, JsonRpcMethod.Context) => Out < S,
        ev: (Async & Abort[JsonRpcError]) <:< S
    ) extends JsonRpcMethod[S]:
        val kind                              = Kind.Request
        private[kyo] val schemaIn: Schema[?]  = in
        private[kyo] val schemaOut: Schema[?] = out

        private[kyo] def handle(params: Structure.Value, ctx: JsonRpcMethod.Context)(using
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
                                Abort.fail(JsonRpcError.internalError("Internal error", Present(Structure.Value.Str(t.getMessage))))
                    case Result.Failure(e) =>
                        Abort.fail(JsonRpcError.invalidParams(e.getMessage))
                    case Result.Panic(t) =>
                        Abort.fail(JsonRpcError.internalError("Internal error", Present(Structure.Value.Str(t.getMessage))))
            ev.liftContra[[X] =>> Structure.Value < (X & Abort[JsonRpcError])].apply(computation)
        end handle
    end RequestMethod

    final private class NotificationMethod[In, S](
        val name: String,
        in: Schema[In],
        handler: (In, JsonRpcMethod.Context) => Unit < S,
        ev: (Async & Abort[JsonRpcError]) <:< S
    ) extends JsonRpcMethod[S]:
        val kind                              = Kind.Notification
        private[kyo] val schemaIn: Schema[?]  = in
        private[kyo] val schemaOut: Schema[?] = summon[Schema[Unit]]

        private[kyo] def handle(params: Structure.Value, ctx: JsonRpcMethod.Context)(using
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
                                Abort.fail(JsonRpcError.internalError("Internal error", Present(Structure.Value.Str(t.getMessage))))
                    case Result.Failure(e) =>
                        Abort.fail(JsonRpcError.invalidParams(e.getMessage))
                    case Result.Panic(t) =>
                        Abort.fail(JsonRpcError.internalError("Internal error", Present(Structure.Value.Str(t.getMessage))))
            ev.liftContra[[X] =>> Structure.Value < (X & Abort[JsonRpcError])].apply(computation)
        end handle
    end NotificationMethod
end JsonRpcMethod
