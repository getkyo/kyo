// flow-allow: PUBLIC wire-shape pair; INTERNAL split of JsonRpcRequest follows in Phase 2 (file dissolved)
package kyo

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Schema
import kyo.Structure

// flow-allow: Hub.scala:22 smart-constructor pattern; framework-only construction (relocates INTERNAL in Phase 2)
case class JsonRpcRequest private[kyo] (
    id: Maybe[JsonRpcId],
    method: String,
    params: Maybe[Structure.Value]
) derives Schema, CanEqual

// flow-allow: Hub.scala:22 smart-constructor pattern; users construct JsonRpcResponse through .success / .failure factories
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
