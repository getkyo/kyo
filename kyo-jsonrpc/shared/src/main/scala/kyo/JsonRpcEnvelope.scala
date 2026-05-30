package kyo

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Structure

/** The wire-level discriminated union of all JSON-RPC 2.0 message kinds.
  *
  * Every message passing through a [[JsonRpcTransport]] is represented as one of:
  *  - [[JsonRpcEnvelope.Request]]: a call that expects a response.
  *  - [[JsonRpcEnvelope.Notification]]: a fire-and-forget message.
  *  - [[JsonRpcEnvelope.Response]]: a reply carrying either a result or an error.
  *  - [[JsonRpcEnvelope.Malformed]]: a message that could not be decoded; carries the raw value
  *    and a reason string for logging.
  *
  * Consumed by [[JsonRpcTransport]] and [[MessageGate]] implementations.
  *
  * @see [[JsonRpcTransport]]
  * @see [[MessageGate]]
  */
enum JsonRpcEnvelope derives CanEqual:
    case Request(
        id: JsonRpcId,
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
        id: JsonRpcId,
        result: Maybe[Structure.Value],
        error: Maybe[JsonRpcError],
        extras: Maybe[Structure.Value]
    )
    case Malformed(id: Maybe[JsonRpcId], reason: String, raw: Structure.Value)
end JsonRpcEnvelope

object JsonRpcEnvelope:
    object Response:
        def success(id: JsonRpcId, result: Structure.Value)(using Frame): JsonRpcEnvelope.Response =
            JsonRpcEnvelope.Response(id, Present(result), Absent, Absent)

        def failure(id: JsonRpcId, error: JsonRpcError)(using Frame): JsonRpcEnvelope.Response =
            JsonRpcEnvelope.Response(id, Absent, Present(error), Absent)
    end Response
end JsonRpcEnvelope
