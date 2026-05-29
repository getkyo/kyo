// flow-allow: PUBLIC error-channel ADT appearing in JsonRpcEndpoint Abort rows and user error matching
package kyo

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Schema
import kyo.Structure

case class JsonRpcError(code: Int, message: String, data: Maybe[Structure.Value]) derives Schema, CanEqual

object JsonRpcError:
    val ParseError: JsonRpcError           = JsonRpcError(-32700, "Parse error", Absent)
    val InvalidRequest: JsonRpcError       = JsonRpcError(-32600, "Invalid Request", Absent)
    val MethodNotFound: JsonRpcError       = JsonRpcError(-32601, "Method not found", Absent)
    val InvalidParams: JsonRpcError        = JsonRpcError(-32602, "Invalid params", Absent)
    val InternalError: JsonRpcError        = JsonRpcError(-32603, "Internal error", Absent)
    val ServerNotInitialized: JsonRpcError = JsonRpcError(-32002, "Server not initialized", Absent)
    val UnknownErrorCode: JsonRpcError     = JsonRpcError(-32001, "Unknown error code", Absent)
    val RequestCancelled: JsonRpcError     = JsonRpcError(-32800, "Request cancelled", Absent)
    val ContentModified: JsonRpcError      = JsonRpcError(-32801, "Content modified", Absent)
    val ServerCancelled: JsonRpcError      = JsonRpcError(-32802, "Server cancelled", Absent)
    val RequestFailed: JsonRpcError        = JsonRpcError(-32803, "Request failed", Absent)

    def methodNotFound(name: String)(using Frame): JsonRpcError =
        JsonRpcError(-32601, s"Method not found: $name", Absent)

    def invalidRequest(reason: String)(using Frame): JsonRpcError =
        JsonRpcError(-32600, "Invalid Request", Present(Structure.Value.Str(reason)))

    def invalidParams(reason: String)(using Frame): JsonRpcError =
        JsonRpcError(-32602, "Invalid params", Present(Structure.Value.Str(reason)))

    def internalError(cause: String, data: Maybe[Structure.Value] = Absent)(using Frame): JsonRpcError =
        JsonRpcError(-32603, cause, data)

    def cancelled(reason: Maybe[String] = Absent)(using Frame): JsonRpcError =
        val data = reason.map(r => Structure.Value.Str(r))
        JsonRpcError(-32800, "Request cancelled", data)
end JsonRpcError
