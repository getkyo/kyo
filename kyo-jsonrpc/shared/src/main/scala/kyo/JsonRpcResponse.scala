// PUBLIC response wire-shape with success/failure smart constructors and Schema derivation
package kyo

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Schema
import kyo.Structure

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
