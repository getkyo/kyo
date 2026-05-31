package kyo

import kyo.*
import scala.annotation.nowarn

/** Base class for all MCP errors, organized into four sealed subcategories by operational stage.
  *
  * The four subcategories map to distinct pipeline stages where errors arise:
  *   - [[McpHandshakeFailure]]: errors surfaced during the MCP initialization handshake
  *   - [[McpDispatchFailure]]: errors surfaced during method routing (unknown tool/resource/prompt)
  *   - [[McpExecutionFailure]]: errors surfaced during handler execution or structured-payload validation
  *   - [[McpApplicationFailure]]: user-domain errors from handler bodies
  *
  * Extends [[JsonRpcApplicationError]], the cross-module extension point for JSON-RPC errors
  * (`kyo-jsonrpc/.../JsonRpcError.scala:423-429`). [[JsonRpcApplicationError]] itself extends
  * [[JsonRpcError]] and [[JsonRpcApplicationFailure]], so every `McpError` is a valid
  * [[JsonRpcError]] and travels through `Abort[JsonRpcError | ...]` rows transparently.
  * The inherited `Schema[JsonRpcError]` at `kyo-jsonrpc/.../JsonRpcError.scala:64-116` encodes
  * any `McpError` as the wire triple `(code, message, data)`. No separate `Schema[McpError]` is
  * needed.
  *
  * @see [[McpHandshakeFailure]]
  * @see [[McpDispatchFailure]]
  * @see [[McpExecutionFailure]]
  * @see [[McpApplicationFailure]]
  */
// flow-allow: Structure carve-out per §11a / INV-021 (forwarded to JsonRpcApplicationError)
sealed abstract class McpError(
    code: Int,
    message: String,
    // flow-allow: Structure carve-out per §11a / INV-021 (forwarded to JsonRpcApplicationError)
    data: Maybe[Structure.Value] = Absent,
    cause: String | Throwable = ""
)(using Frame)
    extends JsonRpcApplicationError(code, message, data, cause)

/** Marks errors arising during the MCP initialization handshake (initialize / notifications/initialized). */
sealed trait McpHandshakeFailure extends McpError

/** Marks errors arising during method dispatch (unknown tool, resource, or prompt). */
sealed trait McpDispatchFailure extends McpError

/** Marks errors arising during handler execution or structured-payload validation. */
sealed trait McpExecutionFailure extends McpError

/** Marks user-domain application errors from handler bodies.
  *
  * Non-sealed: callers extend [[McpApplicationError]] for custom application errors.
  * Already extends [[JsonRpcApplicationFailure]] via [[McpError]]'s [[JsonRpcApplicationError]]
  * base, so this trait does not need to re-declare it.
  */
trait McpApplicationFailure extends McpError

// =============================================================================
// Handshake-failure leaves
// =============================================================================

/** MCP handshake violation: method attempted before the initialize request was received (-32002).
  *
  * Raised when the server receives any request other than `initialize` before the handshake
  * is complete. The `attemptedMethod` field names the premature request.
  *
  * @param attemptedMethod the method name that was called before initialization
  */
case class McpHandshakeNotInitializedError(attemptedMethod: String)(using Frame)
    extends McpError(
        code = -32002,
        message = s"Handshake violation: '$attemptedMethod' attempted before initialize request.",
        data = Absent
    ) with McpHandshakeFailure

/** MCP handshake violation: method attempted after the initialize handshake was already completed (-32002).
  *
  * Raised when the server receives a second `initialize` request or another handshake-stage
  * message after the handshake is already done.
  *
  * @param attemptedMethod the method name that was called after initialization was already done
  */
case class McpHandshakeAlreadyInitializedError(attemptedMethod: String)(using Frame)
    extends McpError(
        code = -32002,
        message = s"Handshake violation: '$attemptedMethod' attempted after initialize was already completed.",
        data = Absent
    ) with McpHandshakeFailure

/** MCP protocol version mismatch during initialization (-32602).
  *
  * Raised when the client requests a protocol version that the server does not support.
  * The `supported` chunk enumerates all versions this server accepts.
  *
  * @param clientRequested the protocol version the client sent in the initialize request
  * @param supported       the protocol versions this server accepts
  */
case class McpProtocolVersionMismatchError(
    clientRequested: McpProtocolVersion,
    supported: Chunk[McpProtocolVersion]
)(using Frame)
    extends McpError(
        code = -32602,
        message =
            s"""Protocol version mismatch: client requested ${clientRequested.asString}.

  Server supports: ${supported.map(_.asString).toList.sorted.mkString(", ")}""",
        data = Absent
    ) with McpHandshakeFailure

// =============================================================================
// Dispatch-failure leaves
// =============================================================================

/** Unknown tool name dispatched by the client (-32602).
  *
  * Raised when a `tools/call` request names a tool that has no registered handler.
  *
  * @param name       the tool name that was not found
  * @param registered the tool names currently registered on this server
  */
case class McpUnknownToolError(name: String, registered: Chunk[String])(using Frame)
    extends McpError(
        code = -32602,
        message =
            s"""Unknown tool '$name'.

  Registered: ${if registered.isEmpty then "(none)" else registered.mkString(", ")}""",
        data = Absent
    ) with McpDispatchFailure

/** Unknown resource URI dispatched by the client (-32002).
  *
  * Raised when a `resources/read` request names a resource URI that has no registered handler.
  *
  * @param uri        the resource URI that was not found
  * @param registered the resource URIs currently registered on this server
  */
case class McpUnknownResourceError(uri: McpResourceUri, registered: Chunk[McpResourceUri])(using Frame)
    extends McpError(
        code = -32002,
        message =
            s"""Unknown resource '${uri.asString}'.

  Registered: ${if registered.isEmpty then "(none)" else registered.iterator.map(_.asString).mkString(", ")}""",
        data = Absent
    ) with McpDispatchFailure

/** Unknown prompt name dispatched by the client (-32602).
  *
  * Raised when a `prompts/get` request names a prompt that has no registered handler.
  *
  * @param name       the prompt name that was not found
  * @param registered the prompt names currently registered on this server
  */
case class McpUnknownPromptError(name: String, registered: Chunk[String])(using Frame)
    extends McpError(
        code = -32602,
        message =
            s"""Unknown prompt '$name'.

  Registered: ${if registered.isEmpty then "(none)" else registered.mkString(", ")}""",
        data = Absent
    ) with McpDispatchFailure

/** Capability required by a method was not advertised by the peer (-32601).
  *
  * Raised when a client calls a method whose required capability was not declared during
  * the handshake. The `peer` field identifies whether the server or client failed to
  * advertise the capability.
  *
  * @param method             the method name that requires the capability
  * @param requiredCapability the capability name that was not advertised
  * @param peer               whether the server or client is the peer that failed to advertise
  */
case class McpCapabilityNotAdvertisedError(
    method: String,
    requiredCapability: String,
    peer: McpCapabilityNotAdvertisedError.Peer
)(using Frame)
    extends McpError(
        code = -32601,
        message = s"Method '$method' requires ${peer.describe} capability '$requiredCapability' which was not advertised.",
        data = Absent
    ) with McpDispatchFailure

object McpCapabilityNotAdvertisedError:
    /** Identifies which side of the connection failed to advertise a required capability. */
    enum Peer derives CanEqual:
        case Server, Client
        def describe: String = this.toString.toLowerCase
    end Peer
end McpCapabilityNotAdvertisedError

/** Invalid argument value for a method parameter (-32602).
  *
  * Raised when a method argument fails validation before the handler is invoked. The
  * `field` and `reason` fields provide field-level diagnostic context.
  *
  * @param method the method name whose parameter failed validation
  * @param field  the parameter field name that is invalid
  * @param reason a brief description of the validation failure
  */
case class McpInvalidArgumentError(method: String, field: String, reason: String)(using Frame)
    extends McpError(
        code = -32602,
        message = s"Invalid argument '$field' for '$method': $reason",
        data = Absent
    ) with McpDispatchFailure

// =============================================================================
// Execution-failure leaves
// =============================================================================

/** Typed tool call returned no structured content (-32603).
  *
  * Raised when `McpClient.callTool[In, Out]` (the typed overload) is used and the server
  * returns a `ToolCallResult` with `structuredContent = Absent`. The caller should use
  * the untyped `callTool[In]` overload for tools that return unstructured content.
  *
  * @param tool the tool name that failed to provide structured content
  */
case class McpToolStructuredMissingError(tool: String)(using Frame)
    extends McpError(
        code = -32603,
        message =
            s"""Tool '$tool' returned no structured content for typed call.

  Use the untyped overload `client.callTool[In](name, args)` if the tool
  emits unstructured content, or fix the server to populate
  `ToolCallResult.structuredContent`.""",
        data = Absent
    ) with McpExecutionFailure

/** Sampling request rejected by the client (-32603).
  *
  * Raised when the server issues a `sampling/createMessage` request and the client
  * declines to fulfill it.
  *
  * @param reason a brief description of why the sampling request was rejected
  */
case class McpSamplingRejectedError(reason: String)(using Frame)
    extends McpError(
        code = -32603,
        message = s"Sampling request rejected: $reason",
        data = Absent
    ) with McpExecutionFailure

/** Elicitation request declined by the client (-32603).
  *
  * Raised when the server issues an `elicitation/create` request and the client
  * chooses to decline or cancel it.
  *
  * @param reason a brief description of why the elicitation was declined
  */
case class McpElicitationDeclinedError(reason: String)(using Frame)
    extends McpError(
        code = -32603,
        message = s"Elicitation declined: $reason",
        data = Absent
    ) with McpExecutionFailure

// =============================================================================
// Application-error base and leaves
// =============================================================================

/** Open base class for user-domain MCP errors with caller-defined codes.
  *
  * Non-sealed: callers extend this to define their own typed application errors:
  *
  * {{{
  * case class MyDomainError(id: Int)(using Frame)
  *     extends McpApplicationError(-32010, s"Domain error: $id")
  * }}}
  *
  * Mirrors [[JsonRpcApplicationError]] at `kyo-jsonrpc/.../JsonRpcError.scala:423-429`.
  * Automatically participates in [[McpApplicationFailure]] and [[JsonRpcApplicationFailure]]
  * category traits.
  *
  * @param code    caller-defined integer error code
  * @param message caller-defined human-readable message
  * @param data    optional structured data for the wire error object
  * @param cause   optional underlying throwable or string cause
  */
abstract class McpApplicationError(
    code: Int,
    message: String,
    data: Maybe[Structure.Value] = Absent,
    cause: String | Throwable = ""
)(using Frame)
    extends McpError(code, message, data, cause) with McpApplicationFailure

/** Tool handler execution failed (-32000).
  *
  * Raised when a tool's handler body fails during execution. The `reason` field
  * describes what went wrong; the optional `cause` chains the underlying throwable.
  *
  * @param tool   the name of the tool whose handler failed
  * @param reason a brief description of the execution failure
  * @param cause  optional underlying throwable or string cause
  */
case class McpToolExecutionError(tool: String, reason: String, cause: Throwable | String = "")(using Frame)
    extends McpApplicationError(
        code = -32000,
        message = s"Tool '$tool' execution failed: $reason",
        data = Absent,
        cause = cause
    )

/** Resource read failed (-32001).
  *
  * Raised when a resource handler fails to read the requested resource. The `uri`
  * field identifies the resource; the optional `cause` chains the underlying throwable.
  *
  * @param uri    the URI of the resource that could not be read
  * @param reason a brief description of the read failure
  * @param cause  optional underlying throwable or string cause
  */
case class McpResourceReadError(uri: McpResourceUri, reason: String, cause: Throwable | String = "")(using Frame)
    extends McpApplicationError(
        code = -32001,
        message = s"Failed to read resource '${uri.asString}': $reason",
        data = Absent,
        cause = cause
    )

/** Prompt render failed (-32003).
  *
  * Raised when a prompt handler fails to render the requested prompt. The `name`
  * field identifies the prompt; the optional `cause` chains the underlying throwable.
  *
  * @param name   the name of the prompt that failed to render
  * @param reason a brief description of the render failure
  * @param cause  optional underlying throwable or string cause
  */
case class McpPromptRenderError(name: String, reason: String, cause: Throwable | String = "")(using Frame)
    extends McpApplicationError(
        code = -32003,
        message = s"Failed to render prompt '$name': $reason",
        data = Absent,
        cause = cause
    )
