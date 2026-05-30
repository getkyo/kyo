package kyo

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Schema
import kyo.Structure

/** Wire-level response shape for a JSON-RPC 2.0 response object.
  *
  * Contains either a `result` value (on success) or an `error` value (on failure). Use the
  * companion factories `success` and `failure` to construct instances; the primary constructor
  * is internal.
  *
  * Note: this type will be merged into [[JsonRpcEnvelope.Response]] in a future phase.
  *
  * @see [[JsonRpcEnvelope]]
  */
// Hub.scala:22 smart-constructor pattern; users construct JsonRpcResponse through .success / .failure factories
case class JsonRpcResponse private[kyo] (
    id: Maybe[JsonRpcId],
    result: Maybe[Structure.Value],
    error: Maybe[JsonRpcError]
) derives Schema, CanEqual

object JsonRpcResponse:
    def success(id: JsonRpcId, result: Structure.Value)(using Frame): JsonRpcResponse =
        JsonRpcResponse(Present(id), Present(result), Absent)

    def failure(id: JsonRpcId, error: JsonRpcError)(using Frame): JsonRpcResponse =
        JsonRpcResponse(Present(id), Absent, Present(error))
end JsonRpcResponse
