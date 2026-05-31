package kyo

import kyo.*

/** Base class for all MCP exceptions, organized into four sealed subcategories by operational stage.
  *
  * The four subcategories map to distinct pipeline stages where errors arise:
  *   - [[McpHandshakeException]]: errors surfaced during the MCP initialization handshake
  *   - [[McpDispatchException]]: errors surfaced during method routing (unknown tool/resource/prompt)
  *   - [[McpExecutionException]]: errors surfaced during handler execution or structured-payload validation
  *   - [[McpApplicationException]]: user-domain errors from handler bodies
  *
  * Extends [[JsonRpcApplicationError]], the cross-module extension point for JSON-RPC errors
  * (`kyo-jsonrpc/.../JsonRpcError.scala:423-429`). [[JsonRpcApplicationError]] itself extends
  * [[JsonRpcError]] and [[JsonRpcApplicationFailure]], so every `McpException` is a valid
  * [[JsonRpcError]] and travels through `Abort[JsonRpcError | ...]` rows transparently.
  * The inherited `Schema[JsonRpcError]` at `kyo-jsonrpc/.../JsonRpcError.scala:64-116` encodes
  * any `McpException` as the wire triple `(code, message, data)`. No separate `Schema[McpException]`
  * is needed.
  *
  * User-domain errors are registered per-route via `route.error[E2](code, message)` rather than by
  * extending this hierarchy directly; the hierarchy is sealed so callers exhaust pattern matches on
  * the four subcategories above without an open extension surface.
  *
  * @see [[McpHandshakeException]]
  * @see [[McpDispatchException]]
  * @see [[McpExecutionException]]
  * @see [[McpApplicationException]]
  */
// flow-allow: Structure carve-out per §11a / INV-021 (forwarded to JsonRpcApplicationError)
sealed abstract class McpException(
    code: Int,
    message: Text,
    // flow-allow: Structure carve-out per §11a / INV-021 (forwarded to JsonRpcApplicationError)
    data: Maybe[Structure.Value] = Absent,
    cause: Text | Throwable = ""
)(using Frame)
    extends JsonRpcApplicationError(
        code,
        message.show,
        data,
        McpException.toRpcCause(cause)
    )

object McpException:
    private[kyo] def toRpcCause(cause: Text | Throwable): String | Throwable =
        cause match
            case t: Throwable => t
            case s: String    => s
            case other        => other.asInstanceOf[Text].show
end McpException

// =============================================================================
// Subcategory bases
// =============================================================================

/** Marks errors arising during the MCP initialization handshake (initialize / notifications/initialized). */
sealed abstract class McpHandshakeException(code: Int, message: Text, cause: Text | Throwable = "")(using Frame)
    extends McpException(code, message, Absent, cause)

/** Marks errors arising during method dispatch (unknown tool, resource, or prompt). */
sealed abstract class McpDispatchException(code: Int, message: Text, cause: Text | Throwable = "")(using Frame)
    extends McpException(code, message, Absent, cause)

/** Marks errors arising during handler execution or structured-payload validation. */
sealed abstract class McpExecutionException(code: Int, message: Text, cause: Text | Throwable = "")(using Frame)
    extends McpException(code, message, Absent, cause)

/** Marks user-domain application errors from handler bodies.
  *
  * Sealed: callers register additional typed errors via `route.error[E2](code, message)` on the
  * route rather than by extending this class directly.
  */
sealed abstract class McpApplicationException(code: Int, message: Text, cause: Text | Throwable = "")(using Frame)
    extends McpException(code, message, Absent, cause)

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
case class McpHandshakeNotInitializedException(attemptedMethod: String)(using Frame)
    extends McpHandshakeException(
        code = -32002,
        message = s"Handshake violation: '$attemptedMethod' attempted before initialize request."
    )

/** MCP handshake violation: method attempted after the initialize handshake was already completed (-32002).
  *
  * Raised when the server receives a second `initialize` request or another handshake-stage
  * message after the handshake is already done.
  *
  * @param attemptedMethod the method name that was called after initialization was already done
  */
case class McpHandshakeAlreadyInitializedException(attemptedMethod: String)(using Frame)
    extends McpHandshakeException(
        code = -32002,
        message = s"Handshake violation: '$attemptedMethod' attempted after initialize was already completed."
    )

/** MCP protocol version mismatch during initialization (-32602).
  *
  * Raised when the client requests a protocol version that the server does not support.
  * The `supported` chunk enumerates all versions this server accepts.
  *
  * @param clientRequested the protocol version the client sent in the initialize request
  * @param supported       the protocol versions this server accepts
  */
case class McpProtocolVersionMismatchException(
    clientRequested: McpProtocolVersion,
    supported: Chunk[McpProtocolVersion]
)(using Frame)
    extends McpHandshakeException(
        code = -32602,
        message =
            s"""Protocol version mismatch: client requested ${clientRequested.asString}.

  Server supports: ${supported.map(_.asString).toList.sorted.mkString(", ")}"""
    )

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
case class McpUnknownToolException(name: String, registered: Chunk[String])(using Frame)
    extends McpDispatchException(
        code = -32602,
        message =
            s"""Unknown tool '$name'.

  Registered: ${if registered.isEmpty then "(none)" else registered.mkString(", ")}"""
    )

/** Unknown resource URI dispatched by the client (-32002).
  *
  * Raised when a `resources/read` request names a resource URI that has no registered handler.
  *
  * @param uri        the resource URI that was not found
  * @param registered the resource URIs currently registered on this server
  */
case class McpUnknownResourceException(uri: McpResourceUri, registered: Chunk[McpResourceUri])(using Frame)
    extends McpDispatchException(
        code = -32002,
        message =
            s"""Unknown resource '${uri.asString}'.

  Registered: ${if registered.isEmpty then "(none)" else registered.iterator.map(_.asString).mkString(", ")}"""
    )

/** Unknown prompt name dispatched by the client (-32602).
  *
  * Raised when a `prompts/get` request names a prompt that has no registered handler.
  *
  * @param name       the prompt name that was not found
  * @param registered the prompt names currently registered on this server
  */
case class McpUnknownPromptException(name: String, registered: Chunk[String])(using Frame)
    extends McpDispatchException(
        code = -32602,
        message =
            s"""Unknown prompt '$name'.

  Registered: ${if registered.isEmpty then "(none)" else registered.mkString(", ")}"""
    )

/** Capability required by a method was not advertised by the peer (-32601).
  *
  * Raised when a client calls a method whose required capability was not declared during
  * the handshake. The `peer` field identifies whether the server or client failed to
  * advertise the capability. `requiredCapability` is the typed [[McpCapabilities.Name]]
  * enum value so the surface never carries a raw `String` for a closed-set capability.
  *
  * @param method             the method name that requires the capability
  * @param requiredCapability the capability name that was not advertised
  * @param peer               whether the server or client is the peer that failed to advertise
  */
case class McpCapabilityNotAdvertisedException(
    method: String,
    requiredCapability: McpCapabilities.Name,
    peer: McpCapabilityNotAdvertisedException.Peer
)(using Frame)
    extends McpDispatchException(
        code = -32601,
        message =
            s"Method '$method' requires ${peer.describe} capability '${requiredCapability.toString.toLowerCase}' which was not advertised."
    )

object McpCapabilityNotAdvertisedException:
    /** Identifies which side of the connection failed to advertise a required capability. */
    enum Peer derives CanEqual:
        case Server, Client
        def describe: String = this.toString.toLowerCase
    end Peer
end McpCapabilityNotAdvertisedException

/** Invalid argument value for a method parameter (-32602).
  *
  * Raised when a method argument fails validation before the handler is invoked. The
  * `field` and `reason` fields provide field-level diagnostic context.
  *
  * @param method the method name whose parameter failed validation
  * @param field  the parameter field name that is invalid
  * @param reason a brief description of the validation failure
  */
case class McpInvalidArgumentException(method: String, field: String, reason: String)(using Frame)
    extends McpDispatchException(
        code = -32602,
        message = s"Invalid argument '$field' for '$method': $reason"
    )

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
case class McpToolStructuredMissingException(tool: String)(using Frame)
    extends McpExecutionException(
        code = -32603,
        message =
            s"""Tool '$tool' returned no structured content for typed call.

  Use the untyped overload `client.callTool[In](name, args)` if the tool
  emits unstructured content, or fix the server to populate
  `ToolCallResult.structuredContent`."""
    )

/** Sampling request rejected by the client (-32603).
  *
  * Raised when the server issues a `sampling/createMessage` request and the client
  * declines to fulfill it.
  *
  * @param reason a brief description of why the sampling request was rejected
  */
case class McpSamplingRejectedException(reason: String)(using Frame)
    extends McpExecutionException(
        code = -32603,
        message = s"Sampling request rejected: $reason"
    )

/** Elicitation request declined by the client (-32603).
  *
  * Raised when the server issues an `elicitation/create` request and the client
  * chooses to decline or cancel it.
  *
  * @param reason a brief description of why the elicitation was declined
  */
case class McpElicitationDeclinedException(reason: String)(using Frame)
    extends McpExecutionException(
        code = -32603,
        message = s"Elicitation declined: $reason"
    )

// =============================================================================
// Application-failure leaves
// =============================================================================

/** Tool handler execution failed (-32000).
  *
  * Raised when a tool's handler body fails during execution. The `reason` field
  * describes what went wrong; the optional `cause` chains the underlying throwable.
  *
  * @param tool   the name of the tool whose handler failed
  * @param reason a brief description of the execution failure
  * @param cause  optional underlying throwable or string cause
  */
case class McpToolExecutionException(tool: String, reason: String, cause: Throwable | Text = "")(using Frame)
    extends McpApplicationException(
        code = -32000,
        message = s"Tool '$tool' execution failed: $reason",
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
case class McpResourceReadException(uri: McpResourceUri, reason: String, cause: Throwable | Text = "")(using Frame)
    extends McpApplicationException(
        code = -32001,
        message = s"Failed to read resource '${uri.asString}': $reason",
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
case class McpPromptRenderException(name: String, reason: String, cause: Throwable | Text = "")(using Frame)
    extends McpApplicationException(
        code = -32003,
        message = s"Failed to render prompt '$name': $reason",
        cause = cause
    )
