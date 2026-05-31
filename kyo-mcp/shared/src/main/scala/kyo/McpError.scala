package kyo

/** Base class for all MCP errors, organized into four sealed subcategories by operational stage.
  *
  * The four subcategories map to distinct pipeline stages where errors arise:
  *   - [[McpHandshakeFailure]]: errors surfaced during the MCP initialization handshake
  *   - [[McpDispatchFailure]]: errors surfaced during method routing (unknown tool/resource/prompt)
  *   - [[McpExecutionFailure]]: errors surfaced during handler execution or structured-payload validation
  *   - [[McpApplicationFailure]]: user-domain errors from handler bodies
  *
  * Inherits from [[JsonRpcApplicationError]] (the designed extension point at
  * `kyo-jsonrpc/.../JsonRpcError.scala:423-429`) so that every MCP error is a valid
  * `JsonRpcError` and travels through `Abort[JsonRpcError | ...]` rows transparently.
  * `JsonRpcError` is sealed to its source file; `JsonRpcApplicationError` is the
  * non-sealed abstract class designed for cross-module extension.
  *
  * Phase 1 stub: base class and category traits are declared; leaves are added in Phase 2.
  *
  * @see [[McpHandshakeFailure]]
  * @see [[McpDispatchFailure]]
  * @see [[McpExecutionFailure]]
  * @see [[McpApplicationFailure]]
  */
sealed abstract class McpError(
    code: Int,
    message: String,
    // flow-allow: Structure carve-out per §11a / INV-021 (forwarded to JsonRpcApplicationError)
    data: Maybe[Structure.Value] = Absent,
    cause: String | Throwable = ""
)(using Frame)
    extends JsonRpcApplicationError(code, message, data, cause)

/** Category trait for errors arising during the MCP initialization handshake. */
sealed trait McpHandshakeFailure extends McpError

/** Category trait for errors arising during method dispatch (unknown tool, resource, or prompt). */
sealed trait McpDispatchFailure extends McpError

/** Category trait for errors arising during handler execution or payload validation. */
sealed trait McpExecutionFailure extends McpError

/** Category trait for user-domain application errors from handler bodies.
  * Extends both [[McpError]] and [[JsonRpcApplicationFailure]] (already provided via
  * [[JsonRpcApplicationError]] on the base class).
  */
trait McpApplicationFailure extends McpError
