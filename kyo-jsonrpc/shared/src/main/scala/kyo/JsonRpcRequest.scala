package kyo

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Schema
import kyo.Structure

case class JsonRpcRequest private[kyo] (
    id: Maybe[JsonRpcId],
    method: String,
    params: Maybe[Structure.Value]
) derives Schema, CanEqual

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
