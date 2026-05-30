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

/** Describes a single JSON-RPC route binding: a name, a kind (Request or Notification), typed
  * handler input and output, and an optional set of user-domain error types.
  *
  * Use the companion factories to construct instances:
  *  - [[JsonRpcRoute.apply]] for request/response routes.
  *  - [[JsonRpcRoute.notification]] for fire-and-forget notifications.
  *
  * Chain [[error]] to register typed domain errors that the handler may abort with. Domain errors
  * are mapped to JSON-RPC error responses using the registered code and message:
  * {{{
  * val route = JsonRpcRoute[Req, Resp]("doThing")(handler)
  *   .error[NotFound](-32001, "Not found")
  *   .error[Forbidden](-32002, "Forbidden")
  * // route: JsonRpcRoute[Req, Resp, NotFound | Forbidden]
  * }}}
  *
  * The handler effect row is fixed to `Async & Abort[JsonRpcError | JsonRpcResponse.Halt]`.
  * Domain errors registered via `.error[E2]` accumulate in the `E` type parameter for tracking;
  * the engine uses the stored mappings to encode aborted domain errors as wire error responses.
  *
  * Pass the resulting [[JsonRpcRoute]] to [[JsonRpcHandler.init]].
  *
  * @tparam In  the decoded request parameter type; must have a [[Schema]]
  * @tparam Out the encoded response result type; must have a [[Schema]]
  * @tparam E   the union of user-domain error types registered via `.error[E2]`
  *
  * @see JsonRpcHandler
  * @see JsonRpcResponse.Halt
  */
sealed trait JsonRpcRoute[In, Out, +E]:
    def name: String
    def kind: JsonRpcRoute.Kind

    /** Registers a typed domain error. When the handler aborts with `Abort.fail(e: E2)`, the
      * framework encodes the error with the given `code` and `message` and sends it as a
      * JSON-RPC error response. Multiple error types accumulate via union: the returned route
      * has type `JsonRpcRoute[In, Out, E | E2]`.
      *
      * Mirrors `HttpRoute.error[E2]` at kyo-http/shared/src/main/scala/kyo/HttpRoute.scala:82.
      */
    def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): JsonRpcRoute[In, Out, E | E2]
    // Sealed-protocol members consumed by the framework; not part of the public API.
    private[kyo] def schemaIn: Schema[In]
    private[kyo] def schemaOut: Schema[?]
    private[kyo] def errorMappings: Chunk[JsonRpcRoute.ErrorMapping[?]]
    // Stream.scala:48 sealed-protocol with framework-only abstract members
    private[kyo] def handle(params: Structure.Value, ctx: JsonRpcRoute.Context)(using
        Frame
    ): Structure.Value < (Async & Abort[JsonRpcError])
end JsonRpcRoute

object JsonRpcRoute:
    enum Kind derives CanEqual:
        case Request, Notification

    /** A mapping from a typed domain error type to a wire code and message.
      * Built by [[JsonRpcRoute.error]] and stored on each route. The engine uses these to encode
      * `Abort[E]` failures as `JsonRpcError` wire responses.
      */
    final class ErrorMapping[E](val code: Int, val message: String)(using val tag: ConcreteTag[E]):
        def matches(e: Any): Boolean = tag.accepts(e)
    end ErrorMapping

    /** Per-request context supplied to every [[JsonRpcRoute]] handler by the framework.
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

    /** Factory for request/response routes. The handler effect row is fixed to
      * `Async & Abort[JsonRpcError | JsonRpcResponse.Halt]`. Domain errors are registered
      * separately via `.error[E2](code, message)` and are tracked in the `E` type parameter.
      *
      * Mirrors the `HttpRoute.handler` approach at
      * kyo-http/shared/src/main/scala/kyo/HttpRoute.scala:95.
      */
    def apply[In: Schema, Out: Schema](name: String)(
        handler: (In, JsonRpcRoute.Context) => Out < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt])
    )(using Frame): JsonRpcRoute[In, Out, Nothing] =
        val capturedSchemaIn: Schema[In]   = summon[Schema[In]]
        val capturedSchemaOut: Schema[Out] = summon[Schema[Out]]
        new RequestRoute[In, Out, Nothing](name, capturedSchemaIn, capturedSchemaOut, handler, Chunk.empty)
    end apply

    def notification[In: Schema](name: String)(
        handler: (In, JsonRpcRoute.Context) => Unit < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt])
    )(using Frame): JsonRpcRoute[In, Unit, Nothing] =
        val capturedSchemaIn: Schema[In] = summon[Schema[In]]
        new NotificationRoute[In, Nothing](name, capturedSchemaIn, handler, Chunk.empty)
    end notification

    /** Dispatches `params` to the named route in `routes`. Returns Absent for unknown route.
      * Public reach-in for non-engine consumers (one-shot stdio loop, HTTP POST endpoints,
      * custom routers); keeps `JsonRpcRoute.handle` private[kyo]. For Notification kind the
      * inner result is `Structure.Value.Null` after the handler completes.
      */
    def dispatch(
        name: String,
        routes: Seq[JsonRpcRoute[?, ?, ?]],
        params: Structure.Value,
        ctx: JsonRpcRoute.Context
    )(using Frame): Maybe[Structure.Value < (Async & Abort[JsonRpcError])] =
        val routeMap: Map[String, JsonRpcRoute[?, ?, ?]] =
            routes.iterator.map(m => (m.name, m)).toMap
        // Map.get returns scala.Option; match arms are interop, not kyo code
        routeMap.get(name) match
            // scala.Option arm; interop with Map.get
            case Some(route) => Present(route.handle(params, ctx))
            // scala.Option arm; interop with Map.get
            case None => Absent
        end match
    end dispatch

    final private class RequestRoute[In, Out, +E](
        val name: String,
        val schemaIn: Schema[In],
        out: Schema[Out],
        handler: (In, JsonRpcRoute.Context) => Out < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt]),
        val errorMappings: Chunk[JsonRpcRoute.ErrorMapping[?]]
    ) extends JsonRpcRoute[In, Out, E]:
        val kind                              = Kind.Request
        private[kyo] val schemaOut: Schema[?] = out

        def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): JsonRpcRoute[In, Out, E | E2] =
            new RequestRoute[In, Out, E | E2](name, schemaIn, out, handler, errorMappings.append(new ErrorMapping[E2](code, message)))

        private[kyo] def handle(params: Structure.Value, ctx: JsonRpcRoute.Context)(using
            fr: Frame
        ): Structure.Value < (Async & Abort[JsonRpcError]) =
            Structure.decode[In](params)(using schemaIn, fr) match
                case Result.Success(decoded) =>
                    Abort.run[JsonRpcError | JsonRpcResponse.Halt](handler(decoded, ctx)).map:
                        case Result.Success(result) =>
                            Structure.encode[Out](result)(using out, fr)
                        case Result.Failure(err: JsonRpcError) =>
                            Abort.fail(err)
                        case Result.Failure(_: JsonRpcResponse.Halt) =>
                            // Halt used in a request route handler is not meaningful without a
                            // gate id; surface as a handler panic to expose the misuse.
                            Abort.fail(JsonRpcHandlerPanicError(
                                name,
                                new IllegalStateException(
                                    s"JsonRpcResponse.Halt used in request route '$name' without a gate id"
                                )
                            )(using fr))
                        case Result.Panic(t) =>
                            Abort.fail(JsonRpcHandlerPanicError(name, t)(using fr))
                case Result.Failure(e) =>
                    Abort.fail(JsonRpcInvalidParamsError(
                        name,
                        Absent,
                        Chunk(JsonRpcInvalidParamsError.ParamError(
                            "params",
                            JsonRpcInvalidParamsError.Problem.TypeMismatch("expected", e.getMessage)
                        ))
                    )(using fr))
                case Result.Panic(t) =>
                    Abort.fail(JsonRpcHandlerPanicError(name, t)(using fr))
    end RequestRoute

    final private class NotificationRoute[In, +E](
        val name: String,
        in: Schema[In],
        handler: (In, JsonRpcRoute.Context) => Unit < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt]),
        val errorMappings: Chunk[JsonRpcRoute.ErrorMapping[?]]
    ) extends JsonRpcRoute[In, Unit, E]:
        val kind                              = Kind.Notification
        private[kyo] val schemaIn: Schema[In] = in
        private[kyo] val schemaOut: Schema[?] = summon[Schema[Unit]]

        def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): JsonRpcRoute[In, Unit, E | E2] =
            new NotificationRoute[In, E | E2](name, in, handler, errorMappings.append(new ErrorMapping[E2](code, message)))

        private[kyo] def handle(params: Structure.Value, ctx: JsonRpcRoute.Context)(using
            fr: Frame
        ): Structure.Value < (Async & Abort[JsonRpcError]) =
            Structure.decode[In](params)(using in, fr) match
                case Result.Success(decoded) =>
                    Abort.run[JsonRpcError | JsonRpcResponse.Halt](handler(decoded, ctx)).map:
                        case Result.Success(_) =>
                            (Structure.Value.Null: Structure.Value)
                        case Result.Failure(err: JsonRpcError) =>
                            Abort.fail(err)
                        case Result.Failure(_: JsonRpcResponse.Halt) =>
                            Abort.fail(JsonRpcHandlerPanicError(
                                name,
                                new IllegalStateException(
                                    s"JsonRpcResponse.Halt used in notification route '$name' without a gate id"
                                )
                            )(using fr))
                        case Result.Panic(t) =>
                            Abort.fail(JsonRpcHandlerPanicError(name, t)(using fr))
                case Result.Failure(e) =>
                    Abort.fail(JsonRpcInvalidParamsError(
                        name,
                        Absent,
                        Chunk(JsonRpcInvalidParamsError.ParamError(
                            "params",
                            JsonRpcInvalidParamsError.Problem.TypeMismatch("expected", e.getMessage)
                        ))
                    )(using fr))
                case Result.Panic(t) =>
                    Abort.fail(JsonRpcHandlerPanicError(name, t)(using fr))
    end NotificationRoute
end JsonRpcRoute
