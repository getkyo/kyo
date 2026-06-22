package kyo

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Structure

/** The wire-level discriminated union of all JSON-RPC 2.0 message kinds.
  *
  * Every message passing through a [[JsonRpcTransport]] is represented as one of:
  *  - [[JsonRpcRequest]]: a call that expects a response.
  *  - [[JsonRpcNotification]]: a fire-and-forget message.
  *  - [[JsonRpcResponse]]: a reply carrying either a result or an error.
  *  - [[JsonRpcMalformedMessage]]: a message that could not be decoded; carries the raw value
  *    and a reason string for logging.
  *
  * Consumed by [[JsonRpcTransport]] and [[JsonRpcMessageGate]] implementations.
  *
  * @see [[JsonRpcTransport]]
  * @see [[JsonRpcMessageGate]]
  */
sealed trait JsonRpcEnvelope derives CanEqual

object JsonRpcEnvelope:

    /** The default wire schema: strict JSON-RPC 2.0. Emits `"jsonrpc":"2.0"` on encode and ignores
      * non-standard `extras` fields.
      *
      * @see [[lenientSchema]]
      */
    given Schema[JsonRpcEnvelope] = internal.codec.JsonRpcEnvelopeSchema.strict

    /** The lenient wire schema: omits the `"jsonrpc"` version field on encode and flattens `extras`
      * to the top level, validating that no reserved key is reused.
      *
      * @see the strict given above
      */
    val lenientSchema: Schema[JsonRpcEnvelope] = internal.codec.JsonRpcEnvelopeSchema.lenient

end JsonRpcEnvelope

/** A JSON-RPC 2.0 wire request message carrying a correlation id, method name, and optional params.
  *
  * Produced by the codec when decoding an incoming message with both an id and a method field.
  * The framework routes this to the matching registered handler and sends a [[JsonRpcResponse]]
  * with the same id when the handler completes.
  *
  * The `extras` field carries any non-standard fields present in the wire object (e.g.
  * protocol-extension fields like `sessionId` or `_meta`). The codec populates this from
  * fields not defined by the base JSON-RPC 2.0 spec.
  *
  * Extends [[JsonRpcEnvelope]]; exhaustive pattern matching over all envelope kinds is supported.
  *
  * @param id      correlation id; must be echoed in the paired [[JsonRpcResponse]].
  * @param method  the method name being invoked.
  * @param params  optional structured parameters; decoded by the matching route's schema.
  * @param extras  optional map of non-standard fields from the wire object.
  *
  * @see [[JsonRpcEnvelope]]
  * @see [[JsonRpcResponse]]
  * @see [[JsonRpcRoute]]
  */
case class JsonRpcRequest(
    id: JsonRpcId,
    method: String,
    params: Maybe[Structure.Value],
    extras: Maybe[Structure.Value]
) extends JsonRpcEnvelope derives CanEqual

/** A JSON-RPC 2.0 wire response message, carrying the correlation id, an optional result, and an
  * optional error.
  *
  * Produced by the framework when a handler completes (result path) or fails with a
  * [[JsonRpcError]] (error path). Also produced by the codec when decoding an incoming response
  * from a remote peer.
  *
  * At most one of `result` and `error` is `Present` on a well-formed wire response. The codec
  * surfaces a message with both fields set as a [[JsonRpcMalformedMessage]] instead.
  *
  * The `extras` field carries any non-standard fields present in the wire object.
  *
  * Extends [[JsonRpcEnvelope]]; exhaustive pattern matching over all envelope kinds is supported.
  *
  * @param id     correlation id matching the originating [[JsonRpcRequest]].
  * @param result the successful result value, encoded by the route's output schema.
  * @param error  the error value, if the handler or peer raised an error.
  * @param extras optional map of non-standard fields from the wire object.
  *
  * @see [[JsonRpcEnvelope]]
  * @see [[JsonRpcRequest]]
  * @see [[JsonRpcError]]
  */
case class JsonRpcResponse(
    id: JsonRpcId,
    result: Maybe[Structure.Value],
    error: Maybe[JsonRpcError],
    extras: Maybe[Structure.Value]
) extends JsonRpcEnvelope derives CanEqual

object JsonRpcResponse:

    /** Constructs a successful response with a result value and no error. */
    def success(id: JsonRpcId, result: Structure.Value)(using Frame): JsonRpcResponse =
        JsonRpcResponse(id, Present(result), Absent, Absent)

    /** Constructs an error response with the given error and no result. */
    def failure(id: JsonRpcId, error: JsonRpcError)(using Frame): JsonRpcResponse =
        JsonRpcResponse(id, Absent, Present(error), Absent)

    /** Short-circuit signal for routes and gates to abort request processing and send a response
      * immediately. Used with `Abort.fail(Halt(response))` or the convenience
      * `JsonRpcResponse.halt(response)`. When a route or gate aborts with Halt, the framework
      * skips remaining processing and sends the wrapped response directly.
      */
    case class Halt(response: JsonRpcResponse)

    /** Convenience for short-circuiting: aborts with the given response immediately. */
    def halt(response: JsonRpcResponse)(using Frame): Nothing < Abort[Halt] =
        Abort.fail(Halt(response))

end JsonRpcResponse

/** A JSON-RPC 2.0 wire notification message: a method call without a correlation id, expecting
  * no response.
  *
  * Produced by the codec when decoding an incoming message that has a method field but no id
  * field (or an id field explicitly set to null). The framework dispatches this to the matching
  * registered handler and does not send any reply.
  *
  * The `extras` field carries any non-standard fields present in the wire object (e.g.
  * protocol-extension fields like `sessionId` or `_meta`). The codec populates this from
  * fields not defined by the base JSON-RPC 2.0 spec.
  *
  * Extends [[JsonRpcEnvelope]]; exhaustive pattern matching over all envelope kinds is supported.
  *
  * @param method  the method name being notified.
  * @param params  optional structured parameters; decoded by the matching route's schema.
  * @param extras  optional map of non-standard fields from the wire object.
  *
  * @see [[JsonRpcEnvelope]]
  * @see [[JsonRpcRoute.notification]]
  */
case class JsonRpcNotification(
    method: String,
    params: Maybe[Structure.Value],
    extras: Maybe[Structure.Value]
) extends JsonRpcEnvelope derives CanEqual

/** A JSON-RPC 2.0 wire message that could not be classified into one of the three well-formed
  * envelope types ([[JsonRpcRequest]], [[JsonRpcResponse]], [[JsonRpcNotification]]).
  *
  * The codec produces a [[JsonRpcMalformedMessage]] in three situations:
  *  - The top-level value is not a JSON object.
  *  - A response message has both `result` and `error` fields set.
  *  - A response message has an `error` field that is not a JSON object.
  *  - The message has neither a `method` field nor both `id` and (`result` or `error`) fields.
  *
  * The framework logs and routes malformed messages as follows:
  *  - If `id` is `Present` and matches a pending caller, the caller is failed with an
  *    `InvalidRequest` error.
  *  - If `id` is `Absent`, the message is silently dropped.
  *
  * Extends [[JsonRpcEnvelope]]; exhaustive pattern matching over all envelope kinds is supported.
  *
  * @param id     the id extracted from the wire object, if one was present and parseable.
  * @param reason a human-readable description of why decoding failed.
  * @param raw    the raw [[Structure.Value]] that could not be classified.
  *
  * @see [[JsonRpcEnvelope]]
  */
case class JsonRpcMalformedMessage(
    id: Maybe[JsonRpcId],
    reason: String,
    raw: Structure.Value
) extends JsonRpcEnvelope derives CanEqual
