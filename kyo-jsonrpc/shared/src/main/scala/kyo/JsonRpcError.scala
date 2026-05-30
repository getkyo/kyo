package kyo

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Schema
import kyo.Structure

/** A JSON-RPC 2.0 error object (§5.1).
  *
  * Carries a numeric `code`, a human-readable `message`, and an optional `data` value.
  * Predefined constants and smart constructors in the companion cover the standard JSON-RPC
  * and LSP error codes.
  *
  * Appears in the `Abort[JsonRpcError]` effect row of `JsonRpcHandler.call` and handler
  * return types.
  *
  * @param code    integer error code (negative values are reserved by JSON-RPC 2.0 §5.1)
  * @param message short description of the error
  * @param data    optional additional information, protocol-defined
  * @see [[JsonRpcHandler]]
  */
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
