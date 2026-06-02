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
  * val route = JsonRpcRoute.request[Req, Resp]("doThing")(handler)
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
      */
    def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): JsonRpcRoute[In, Out, E | E2]
    // Sealed-protocol members consumed by the framework; not part of the public API.
    private[kyo] def schemaIn: Schema[In]
    private[kyo] def schemaOut: Schema[?]
    private[kyo] def errorMappings: Chunk[JsonRpcRoute.ErrorMapping[?]]
    // Returns Abort[JsonRpcError | JsonRpcResponse.Halt] so the engine can detect Halt short-circuits
    // and send the wrapped response directly.
    private[kyo] def handle(params: Structure.Value, ctx: JsonRpcRoute.Context)(using
        Frame
    ): Structure.Value < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt])
end JsonRpcRoute

object JsonRpcRoute:
    enum Kind derives CanEqual:
        case Request, Notification

    /** A mapping from a typed domain error type to a wire code and message.
      * Built by [[JsonRpcRoute.error]] and stored on each route. The engine uses these to encode
      * `Abort[E]` failures as `JsonRpcError` wire responses.
      */
    final class ErrorMapping[E](val code: Int, val message: String)(using val schema: Schema[E], val tag: ConcreteTag[E]):
        def matches(e: Any): Boolean = tag.accepts(e)
        // Encode the matched abort value into a JSON-RPC `data` Structure.Value using the
        // registered Schema. Cast is safe at the call site: matches(e) confirmed `e: E` at runtime.
        def encode(e: Any)(using Frame): Structure.Value = Structure.encode[E](e.asInstanceOf[E])(using schema)
    end ErrorMapping

    /** Per-request context supplied to every [[JsonRpcRoute]] handler by the framework.
      *
      * Provides access to:
      *  - `cancelled`: a `Fiber.Promise` that is completed when the peer sends a cancellation for
      *    the current request.
      *  - `requestId`: the JSON-RPC id of the incoming request, or `Absent` for notifications.
      *  - `extras`: protocol-specific extra fields from the incoming envelope, if any.
      *  - `progress`: reports a progress notification back to the caller via the notification method
      *    configured on the active [[JsonRpcProgressPolicy]].
      *
      * @see [[JsonRpcRoute]]
      * @see [[JsonRpcProgressPolicy]]
      */
    // Smart-constructor pattern; framework creates instances via forTest or JsonRpcEndpointImpl.
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

    /** Constructs a request route whose closure aborts only with user-domain errors `E` or with
      * `JsonRpcResponse.Halt` for short-circuit. Framework wire errors (`JsonRpcError` family) are
      * raised by the engine itself; user closures stay free of them. Domain errors are wire-encoded
      * via the `.error[E2](code, message)` mappings registered on the returned route.
      *
      * The factory keeps tight closure types on the user-facing surface; type erasure is confined
      * to the engine dispatch edge.
      *
      * Symmetric with [[notification]].
      */
    def request[In: Schema, Out: Schema](name: String)[E](
        handler: (In, JsonRpcRoute.Context) => Out < (Async & Abort[E | JsonRpcResponse.Halt])
    )(using Frame): JsonRpcRoute[In, Out, E] =
        val capturedSchemaIn: Schema[In]   = summon[Schema[In]]
        val capturedSchemaOut: Schema[Out] = summon[Schema[Out]]
        new RequestRoute[In, Out, E](name, capturedSchemaIn, capturedSchemaOut, handler, Chunk.empty)
    end request

    /** Mirror of [[request]] for notification routes (no response). */
    def notification[In: Schema](name: String)[E](
        handler: (In, JsonRpcRoute.Context) => Unit < (Async & Abort[E | JsonRpcResponse.Halt])
    )(using Frame): JsonRpcRoute[In, Unit, E] =
        val capturedSchemaIn: Schema[In] = summon[Schema[In]]
        new NotificationRoute[In, E](name, capturedSchemaIn, handler, Chunk.empty)
    end notification

    /** Applies a chunk of typed error mappings at a dispatch boundary.
      *
      * Use from indirection routes that look up user handlers by name and invoke them inline
      * (MCP `tools/call`, `resources/read`, `prompts/get`, `completion/complete`). The wire-level
      * `JsonRpcRoute.request("tools/call")` does not own the user handler's per-handler
      * `.error[E2]` mappings ; those are stored on the carrier. Without this helper, a user
      * `Abort.fail(MyError(...))` falls through the route's empty `errorMappings` and is wrapped
      * as `JsonRpcInternalError` (-32603) instead of the registered code.
      *
      * Captures any abort via `Abort.run[Any]`, then re-routes:
      *   - `JsonRpcResponse.Halt`: propagated for the engine to emit verbatim.
      *   - matching `ErrorMapping`: encoded as `JsonRpcCustomError(code, message, data)`.
      *   - `JsonRpcError`: propagated verbatim.
      *   - any other value: wrapped in `JsonRpcInternalError` (unmapped handler error).
      *   - panics: wrapped in `JsonRpcHandlerPanicError`.
      */
    private[kyo] def applyMappingsAtBoundary[A](
        routeName: String,
        body: A < (Async & Abort[Any]),
        mappings: Chunk[ErrorMapping[?]]
    )(using fr: Frame): A < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt]) =
        Abort.run[Any](body).map:
            case Result.Success(a)                          => a
            case Result.Failure(halt: JsonRpcResponse.Halt) => Abort.fail(halt)
            case Result.Failure(err) =>
                mappings.iterator.find(_.matches(err)) match
                    case Some(mapping) =>
                        Abort.fail(JsonRpcCustomError(
                            mapping.code,
                            mapping.message,
                            data = Present(mapping.encode(err))
                        )(using fr))
                    case None =>
                        err match
                            case e: JsonRpcError => Abort.fail(e)
                            case other =>
                                Abort.fail(JsonRpcInternalError(
                                    JsonRpcInternalError.Operation.Other,
                                    new RuntimeException(s"unmapped handler error in '$routeName': $other")
                                )(using fr))
            case Result.Panic(t) => Abort.fail(JsonRpcHandlerPanicError(routeName, t)(using fr))
    end applyMappingsAtBoundary

    /** Dispatches `params` to the named route in `routes`. Returns Absent for unknown route.
      * Internal helper for non-engine consumers (one-shot stdio loop, HTTP POST endpoints,
      * custom routers); keeps `JsonRpcRoute.handle` private[kyo]. For Notification kind the
      * inner result is `Structure.Value.Null` after the handler completes.
      * Use `JsonRpcHandler.Unsafe.dispatch` for engine-level route dispatch.
      */
    private[kyo] def dispatch(
        name: String,
        routes: Seq[JsonRpcRoute[?, ?, ?]],
        params: Structure.Value,
        ctx: JsonRpcRoute.Context
    )(using Frame): Maybe[Structure.Value < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt])] =
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
        handler: (In, JsonRpcRoute.Context) => Out < (Async & Abort[E | JsonRpcResponse.Halt]),
        val errorMappings: Chunk[JsonRpcRoute.ErrorMapping[?]]
    ) extends JsonRpcRoute[In, Out, E]:
        val kind                              = Kind.Request
        private[kyo] val schemaOut: Schema[?] = out

        def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): JsonRpcRoute[In, Out, E | E2] =
            new RequestRoute[In, Out, E | E2](name, schemaIn, out, handler, errorMappings.append(new ErrorMapping[E2](code, message)))

        private[kyo] def handle(params: Structure.Value, ctx: JsonRpcRoute.Context)(using
            fr: Frame
        ): Structure.Value < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt]) =
            // JSON-RPC 2.0 + MCP spec: `params` is optional. A request without a `params` field
            // (or with `params: null`) must be accepted, and the handler's typed `In` is expected
            // to be constructible from the empty payload (case classes with all default values,
            // or singleton-like records). Coerce Null -> empty Record so kyo-schema's decoder
            // can use default field values rather than failing with a type-mismatch.
            val normalised = params match
                case Structure.Value.Null => Structure.Value.Record(Chunk.empty)
                case other                => other
            Structure.decode[In](normalised)(using schemaIn, fr) match
                case Result.Success(decoded) =>
                    // Engine dispatch boundary: Abort.run[Any] captures the closure's E aborts and
                    // Halt short-circuits without needing a ConcreteTag for the abstract E. Mirrors
                    // kyo-http UnsafeServerDispatch's edge erasure (UnsafeServerDispatch.scala:517).
                    // E remains type-safe on the public surface; Any only appears here, inside the
                    // engine's runtime dispatch, where heterogeneous routes converge.
                    Abort.run[Any](handler(decoded, ctx)).map:
                        case Result.Success(result) =>
                            Structure.encode[Out](result)(using out, fr)
                        case Result.Failure(halt: JsonRpcResponse.Halt) =>
                            // Propagate Halt so the engine emits the wrapped response directly.
                            Abort.fail(halt)
                        case Result.Failure(err) =>
                            // Dispatch via registered ErrorMappings first. Each `.error[E2]`
                            // registers a ConcreteTag matcher and a Schema. If the matcher accepts
                            // the abort value, encode the value via the registered Schema into the
                            // JsonRpcCustomError.data slot.
                            errorMappings.iterator.find(_.matches(err)) match
                                case Some(mapping) =>
                                    Abort.fail(JsonRpcCustomError(
                                        mapping.code,
                                        mapping.message,
                                        data = Present(mapping.encode(err))
                                    )(using fr))
                                case None =>
                                    err match
                                        case e: JsonRpcError =>
                                            Abort.fail(e)
                                        case other =>
                                            Abort.fail(JsonRpcInternalError(
                                                JsonRpcInternalError.Operation.Other,
                                                new RuntimeException(s"unmapped handler error: $other")
                                            )(using fr))
                                    end match
                            end match
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
            end match
        end handle
    end RequestRoute

    final private class NotificationRoute[In, +E](
        val name: String,
        in: Schema[In],
        handler: (In, JsonRpcRoute.Context) => Unit < (Async & Abort[E | JsonRpcResponse.Halt]),
        val errorMappings: Chunk[JsonRpcRoute.ErrorMapping[?]]
    ) extends JsonRpcRoute[In, Unit, E]:
        val kind                              = Kind.Notification
        private[kyo] val schemaIn: Schema[In] = in
        private[kyo] val schemaOut: Schema[?] = summon[Schema[Unit]]

        def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): JsonRpcRoute[In, Unit, E | E2] =
            new NotificationRoute[In, E | E2](name, in, handler, errorMappings.append(new ErrorMapping[E2](code, message)))

        private[kyo] def handle(params: Structure.Value, ctx: JsonRpcRoute.Context)(using
            fr: Frame
        ): Structure.Value < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt]) =
            // Null params -> empty Record, same rationale as RequestRoute.handle.
            val normalised = params match
                case Structure.Value.Null => Structure.Value.Record(Chunk.empty)
                case other                => other
            Structure.decode[In](normalised)(using in, fr) match
                case Result.Success(decoded) =>
                    Abort.run[Any](handler(decoded, ctx)).map:
                        case Result.Success(_) =>
                            (Structure.Value.Null: Structure.Value)
                        case Result.Failure(halt: JsonRpcResponse.Halt) =>
                            Abort.fail(halt)
                        case Result.Failure(err) =>
                            errorMappings.iterator.find(_.matches(err)) match
                                case Some(mapping) =>
                                    Abort.fail(JsonRpcCustomError(
                                        mapping.code,
                                        mapping.message,
                                        data = Present(mapping.encode(err))
                                    )(using fr))
                                case None =>
                                    err match
                                        case e: JsonRpcError =>
                                            Abort.fail(e)
                                        case other =>
                                            Abort.fail(JsonRpcInternalError(
                                                JsonRpcInternalError.Operation.Other,
                                                new RuntimeException(s"unmapped handler error: $other")
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
            end match
        end handle
    end NotificationRoute
end JsonRpcRoute
