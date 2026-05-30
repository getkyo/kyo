package kyo

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Schema
import kyo.Structure
import kyo.TypeMismatchException
import scala.annotation.nowarn

/** The wire-level discriminated union of all JSON-RPC 2.0 message kinds.
  *
  * Every message passing through a [[JsonRpcTransport]] is represented as one of:
  *  - [[JsonRpcEnvelope.Request]]: a call that expects a response.
  *  - [[JsonRpcEnvelope.Notification]]: a fire-and-forget message.
  *  - [[JsonRpcEnvelope.Response]]: a reply carrying either a result or an error.
  *  - [[JsonRpcEnvelope.Malformed]]: a message that could not be decoded; carries the raw value
  *    and a reason string for logging.
  *
  * Consumed by [[JsonRpcTransport]] and [[JsonRpcEndpoint.MessageGate]] implementations.
  *
  * @see [[JsonRpcTransport]]
  * @see [[JsonRpcEndpoint.MessageGate]]
  */
enum JsonRpcEnvelope derives CanEqual:
    case Request(
        id: JsonRpcEnvelope.Id,
        method: String,
        params: Maybe[Structure.Value],
        extras: Maybe[Structure.Value]
    )
    case Notification(
        method: String,
        params: Maybe[Structure.Value],
        extras: Maybe[Structure.Value]
    )
    case Response(
        id: JsonRpcEnvelope.Id,
        result: Maybe[Structure.Value],
        error: Maybe[JsonRpcError],
        extras: Maybe[Structure.Value]
    )
    case Malformed(id: Maybe[JsonRpcEnvelope.Id], reason: String, raw: Structure.Value)
end JsonRpcEnvelope

object JsonRpcEnvelope:

    /** Typed identifier for a JSON-RPC request.
      *
      * JSON-RPC 2.0 §5 allows ids to be a string, a number, or null. This enum models the
      * non-null cases; a null id is represented by `Maybe.Absent` at the call site.
      *
      * Used in `JsonRpcEndpoint.cancel`, `JsonRpcEndpoint.Pending.id`, `JsonRpcEndpoint.ExtrasEncoder`,
      * and `JsonRpcMethod.Context.requestId`.
      *
      * @see [[JsonRpcEnvelope.Request]]
      * @see [[JsonRpcEnvelope.Response]]
      */
    enum Id derives CanEqual:
        case Num(value: Long)
        case Str(value: String)
    end Id

    object Id:
        @nowarn("msg=anonymous")
        given schema: Schema[Id] = Schema.init[Id](
            writeFn = (id, writer) =>
                id match
                    case Id.Num(n) => writer.long(n)
                    case Id.Str(s) => writer.string(s),
            readFn = reader =>
                if reader.isNil() then
                    // word in string literal describes the JSON absent-value type, not a reference
                    throw TypeMismatchException(Seq.empty, "number or string", "null")(using reader.frame)
                else
                    try Id.Num(reader.long())
                    catch case _: TypeMismatchException => Id.Str(reader.string())
        )
    end Id

    object Response:
        def success(id: JsonRpcEnvelope.Id, result: Structure.Value)(using Frame): JsonRpcEnvelope.Response =
            JsonRpcEnvelope.Response(id, Present(result), Absent, Absent)

        def failure(id: JsonRpcEnvelope.Id, error: JsonRpcError)(using Frame): JsonRpcEnvelope.Response =
            JsonRpcEnvelope.Response(id, Absent, Present(error), Absent)
    end Response
end JsonRpcEnvelope
