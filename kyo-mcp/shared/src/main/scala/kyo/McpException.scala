package kyo

import kyo.*

/** Base class for all MCP exceptions, organized by the operation that can raise them.
  *
  * Each public MCP operation declares a sealed per-operation trait as its `Abort` row (for
  * example [[McpCallToolFailure]], [[McpReadResourceFailure]], [[McpRequestSamplingFailure]]).
  * A trait names exactly the leaves that operation can produce, so a caller matches one and the
  * compiler narrows the row to prove what remains. Concrete leaves mix in every operation-trait
  * they belong to, so a single leaf such as [[McpConnectionClosedException]] (raised by every
  * operation) lists every trait while [[McpSamplingRejectedException]] lists only the one.
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
  * a given operation's trait without an open extension surface.
  *
  * @see [[McpConnectionClosedException]]
  * @see [[McpRemoteApplicationException]]
  * @see [[McpCapabilityNotAdvertisedException]]
  */
sealed abstract class McpException(
    code: Int,
    message: String,
    data: Maybe[Structure.Value] = Absent,
    cause: Maybe[Throwable] = Absent
)(using Frame)
    extends JsonRpcApplicationError(
        code,
        message,
        data,
        cause.getOrElse("")
    ):
    /** The underlying throwable cause, if any, without reaching through `getCause`. */
    def underlyingCause: Maybe[Throwable] = cause
end McpException

// =============================================================================
// Per-operation failure traits
// =============================================================================
//
// One sealed trait per public operation; the trait IS the operation's `Abort` row. A concrete
// leaf mixes in every trait whose operation can produce it (see the leaf definitions below).

/** Failure row for `McpClient.callTool` (the typed lane). */
sealed trait McpCallToolFailure extends McpException

/** Failure row for `McpClient.callToolRaw`. */
sealed trait McpCallToolRawFailure extends McpException

/** Failure row for `McpClient.readResource` (the typed lane). */
sealed trait McpReadResourceFailure extends McpException

/** Failure row for `McpClient.readResourceRaw`. */
sealed trait McpReadResourceRawFailure extends McpException

/** Failure row for `McpClient.getPrompt` (the typed lane). */
sealed trait McpGetPromptFailure extends McpException

/** Failure row for `McpClient.getPromptRaw`. */
sealed trait McpGetPromptRawFailure extends McpException

/** Failure row for `McpClient.getPromptChecked`. */
sealed trait McpGetPromptCheckedFailure extends McpException

/** Failure row for the `McpClient` list operations (`listTools`, `listResources`,
  * `listResourceTemplates`, `listPrompts`, and their streaming variants).
  */
sealed trait McpListFailure extends McpException

/** Failure row for `McpClient.complete`. */
sealed trait McpCompleteFailure extends McpException

/** Failure row for the unguarded `McpClient` request operations (`setLogLevel`, `ping`,
  * `subscribeResource`, `unsubscribeResource`).
  */
sealed trait McpClientRequestFailure extends McpException

/** Failure row for `McpServer.requestSampling` (and `Mcp.requestSampling`). */
sealed trait McpRequestSamplingFailure extends McpException

/** Failure row for `McpServer.requestElicitation` (and `Mcp.requestElicitation`). */
sealed trait McpRequestElicitationFailure extends McpException

/** Failure row for `McpServer.requestElicitationAs`. */
sealed trait McpRequestElicitationAsFailure extends McpException

/** Failure row for `McpServer.requestRoots` (and `Mcp.requestRoots`). */
sealed trait McpRequestRootsFailure extends McpException

/** Failure row for the `McpServer` and `McpClient` initialization handshake. */
sealed trait McpInitFailure extends McpException

// =============================================================================
// Connection / configuration leaves
// =============================================================================

/** The transport or peer closed the connection (-32603).
  *
  * The typed boundary leaf for a closed transport: every request and notification operation maps
  * the underlying `Closed` signal from the JSON-RPC transport into this leaf, so no raw `Closed`
  * reaches a public row. Mixes into every operation-trait because any operation can observe a
  * closed connection.
  */
case class McpConnectionClosedException()(using Frame)
    extends McpException(code = -32603, message = "Connection closed.")
    with McpCallToolFailure
    with McpCallToolRawFailure
    with McpReadResourceFailure
    with McpReadResourceRawFailure
    with McpGetPromptFailure
    with McpGetPromptRawFailure
    with McpGetPromptCheckedFailure
    with McpListFailure
    with McpCompleteFailure
    with McpClientRequestFailure
    with McpRequestSamplingFailure
    with McpRequestElicitationFailure
    with McpRequestElicitationAsFailure
    with McpRequestRootsFailure
    with McpInitFailure

/** Invalid MCP configuration detected at initialization (-32603).
  *
  * Thrown as a panic by `McpConfig.require` (and the engine's reserved-error-code check) when a
  * configuration field is rejected (for example an empty `supportedProtocolVersions` set or a
  * non-positive `handshakeTimeout`), or when a handler registers an error code in the
  * framework-reserved range. These are construction-time programmer errors, not recoverable runtime
  * failures, so they panic rather than appear on a tracked `Abort` row, matching the sibling
  * `JsonRpcHandler.Config.require`. The `setting` field names the offending configuration slot and
  * `reason` describes the violation.
  *
  * @param setting the configuration setting that is invalid
  * @param reason  a brief description of why the setting is invalid
  */
case class McpConfigurationError(setting: String, reason: String)(using Frame)
    extends McpException(code = -32603, message = s"Invalid MCP configuration for '$setting': $reason")

// =============================================================================
// Handshake-failure leaves
// =============================================================================

/** MCP handshake violation: method attempted before the initialize request was received (-32002).
  *
  * Raised when the server receives any request other than `initialize` before the handshake
  * is complete. The `attemptedMethod` field names the premature request. Client-side, it also
  * stands in for a handshake that failed or timed out, so it mixes into [[McpInitFailure]].
  *
  * @param attemptedMethod the method name that was called before initialization
  */
case class McpHandshakeNotInitializedException(attemptedMethod: String)(using Frame)
    extends McpException(
        code = -32002,
        message = s"Handshake violation: '$attemptedMethod' attempted before initialize request."
    )
    with McpInitFailure

/** MCP handshake violation: method attempted after the initialize handshake was already completed (-32002).
  *
  * Raised when the server receives a second `initialize` request or another handshake-stage
  * message after the handshake is already done. Engine-internal: it is produced server-side during
  * dispatch and never appears on a client-facing operation row.
  *
  * @param attemptedMethod the method name that was called after initialization was already done
  */
case class McpHandshakeAlreadyInitializedException(attemptedMethod: String)(using Frame)
    extends McpException(
        code = -32002,
        message = s"Handshake violation: '$attemptedMethod' attempted after initialize was already completed."
    )

/** MCP protocol version mismatch during initialization (-32602).
  *
  * Raised when the client requests a protocol version that the server does not support.
  * The `supported` chunk enumerates all versions this server accepts. Engine-internal: it is a
  * panic-class guard for the zero-supported-versions case and never reaches a public row.
  *
  * @param clientRequested the protocol version the client sent in the initialize request
  * @param supported       the protocol versions this server accepts
  */
case class McpProtocolVersionMismatchException(
    clientRequested: McpConfig.ProtocolVersion,
    supported: Chunk[McpConfig.ProtocolVersion]
)(using Frame)
    extends McpException(
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
  * Raised server-side when a `tools/call` request names a tool that has no registered handler.
  * Engine-internal: it is emitted at the dispatch boundary and travels to the client as a remote
  * wire error, never on a client-facing operation row directly.
  *
  * @param name       the tool name that was not found
  * @param registered the tool names currently registered on this server
  */
case class McpUnknownToolException(name: String, registered: Chunk[String])(using Frame)
    extends McpException(
        code = -32602,
        message =
            s"""Unknown tool '$name'.

  Registered: ${if registered.isEmpty then "(none)" else registered.mkString(", ")}"""
    )

/** Unknown resource URI dispatched by the client (-32002).
  *
  * Raised server-side when a `resources/read` request names a resource URI that matches neither
  * a registered concrete resource nor any registered resource-template pattern. Engine-internal.
  *
  * @param uri        the resource URI that was not found
  * @param registered the concrete resource URIs registered on this server
  * @param templates  the resource-template URI patterns registered on this server
  */
case class McpUnknownResourceException(
    uri: McpResourceUri,
    registered: Chunk[McpResourceUri],
    templates: Chunk[McpResourceUri.Template] = Chunk.empty
)(using Frame)
    extends McpException(
        code = -32002,
        message =
            val concretePart  = if registered.isEmpty then "(none)" else registered.iterator.map(_.asString).mkString(", ")
            val templatesPart = if templates.isEmpty then "" else s"\n  Templates: ${templates.iterator.map(_.asString).mkString(", ")}"
            s"""Unknown resource '${uri.asString}'.

  Registered: $concretePart$templatesPart"""
    )

/** Unknown prompt name dispatched by the client (-32602).
  *
  * Raised server-side when a `prompts/get` request names a prompt that has no registered handler.
  * Engine-internal.
  *
  * @param name       the prompt name that was not found
  * @param registered the prompt names currently registered on this server
  */
case class McpUnknownPromptException(name: String, registered: Chunk[String])(using Frame)
    extends McpException(
        code = -32602,
        message =
            s"""Unknown prompt '$name'.

  Registered: ${if registered.isEmpty then "(none)" else registered.mkString(", ")}"""
    )

/** Capability required by a method was not advertised by the peer (-32601).
  *
  * Raised when a method's required capability was not declared during the handshake. The `peer`
  * field identifies whether the server or client failed to advertise the capability.
  * `requiredCapability` is the typed [[McpCapabilities.Name]] enum value so the surface never
  * carries a raw `String` for a closed-set capability. Mixes into every operation whose row can
  * surface a missing capability: the guarded client forward operations, the server reverse
  * sampling/roots calls, and init.
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
    extends McpException(
        code = -32601,
        message =
            s"Method '$method' requires ${peer.describe} capability '${requiredCapability.toString.toLowerCase}' which was not advertised."
    )
    with McpCallToolFailure
    with McpCallToolRawFailure
    with McpReadResourceFailure
    with McpReadResourceRawFailure
    with McpGetPromptFailure
    with McpGetPromptRawFailure
    with McpGetPromptCheckedFailure
    with McpListFailure
    with McpCompleteFailure
    with McpRequestSamplingFailure
    with McpRequestRootsFailure
    with McpInitFailure

object McpCapabilityNotAdvertisedException:
    /** Identifies which side of the connection failed to advertise a required capability. */
    enum Peer derives CanEqual:
        case Server, Client
        def describe: String = this.toString.toLowerCase
    end Peer
end McpCapabilityNotAdvertisedException

/** Invalid argument value for a method parameter (-32602).
  *
  * Raised when a method argument fails validation before the handler is invoked. The `field` and
  * `reason` fields provide field-level diagnostic context. Reaches a public row for the argument
  * checks of `getPromptChecked` and the server-side `requestRoots` response validation.
  *
  * @param method the method name whose parameter failed validation
  * @param field  the parameter field name that is invalid
  * @param reason a brief description of the validation failure
  */
case class McpInvalidArgumentException(method: String, field: String, reason: String)(using Frame)
    extends McpException(
        code = -32602,
        message = s"Invalid argument '$field' for '$method': $reason"
    )
    with McpGetPromptCheckedFailure
    with McpRequestRootsFailure

// =============================================================================
// Execution-failure leaves
// =============================================================================

/** Typed call returned no structured content (-32603).
  *
  * Raised when a typed `callTool[Out]`, `readResource[Out]`, or `getPrompt[Out]` finds the
  * structured-content / payload slot `Absent`. The caller should use the raw overload for content
  * that is not structured.
  *
  * @param tool the tool (or resource/prompt) name that failed to provide structured content
  */
case class McpToolStructuredMissingException(tool: String)(using Frame)
    extends McpException(
        code = -32603,
        message =
            s"""Tool '$tool' returned no structured content for typed call.

  Use the raw overload `client.callToolRaw[In](name, args)` if the tool
  emits unstructured content, or fix the server to populate
  `ToolOutcome.structuredContent`."""
    )
    with McpCallToolFailure
    with McpReadResourceFailure
    with McpGetPromptFailure
    with McpGetPromptCheckedFailure

/** Typed call received structured content that did not decode to the requested type (-32603).
  *
  * Raised when a typed `callTool[Out]` / `readResource[Out]` / `getPrompt[Out]` finds the
  * payload PRESENT but the decode step fails, and when `requestElicitationAs` cannot decode the
  * accepted elicitation payload. Distinct from [[McpToolStructuredMissingException]], which is
  * reserved for the `Absent` case.
  *
  * @param tool   the tool (or resource/prompt) name whose payload failed to decode
  * @param reason the decode failure detail
  * @param cause  the underlying decode throwable, if any
  */
case class McpToolStructuredDecodeException(tool: String, reason: String, cause: Maybe[Throwable] = Absent)(using Frame)
    extends McpException(
        code = -32603,
        message = s"Tool '$tool' structured content did not decode: $reason"
    )
    with McpCallToolFailure
    with McpReadResourceFailure
    with McpGetPromptFailure
    with McpGetPromptCheckedFailure
    with McpRequestElicitationAsFailure

/** Open-payload reader decode failure (-32603).
  *
  * Raised when a typed open-payload reader (`Mcp.extras[T]`, the `metaAs` / `structuredContentAs`
  * projections) finds a value present but non-conforming to `T`. Carries its own narrow row on
  * those helpers (`Abort[McpDecodeException]`), so it mixes into no operation-trait.
  *
  * @param field  the slot being decoded (e.g. "_meta", "structuredContent")
  * @param reason the decode failure detail
  * @param cause  the underlying decode throwable, if any
  */
case class McpDecodeException(field: String, reason: String, cause: Maybe[Throwable] = Absent)(using Frame)
    extends McpException(
        code = -32603,
        message = s"Failed to decode '$field': $reason"
    )

/** Sampling request rejected by the client (-32603).
  *
  * Raised when the server issues a `sampling/createMessage` request and the client declines to
  * fulfill it.
  *
  * @param reason a brief description of why the sampling request was rejected
  */
case class McpSamplingRejectedException(reason: String)(using Frame)
    extends McpException(
        code = -32603,
        message = s"Sampling request rejected: $reason"
    )
    with McpRequestSamplingFailure

/** Elicitation request declined by the client (-32603).
  *
  * Raised when the server issues an `elicitation/create` request and the client chooses to decline
  * or cancel it.
  *
  * @param reason a brief description of why the elicitation was declined
  */
case class McpElicitationDeclinedException(reason: String)(using Frame)
    extends McpException(
        code = -32603,
        message = s"Elicitation declined: $reason"
    )
    with McpRequestElicitationFailure
    with McpRequestElicitationAsFailure

// =============================================================================
// Application-failure leaves
// =============================================================================

/** Tool handler execution failed (-32000).
  *
  * Available for a handler body that wants to signal a tool-execution failure. The `reason` field
  * describes what went wrong; the optional `cause` chains the underlying throwable. Not produced by
  * the engine, so it mixes into no operation-trait.
  *
  * @param tool   the name of the tool whose handler failed
  * @param reason a brief description of the execution failure
  * @param cause  optional underlying throwable or string cause
  */
case class McpToolExecutionException(tool: String, reason: String, cause: Maybe[Throwable] = Absent)(using Frame)
    extends McpException(
        code = -32000,
        message = s"Tool '$tool' execution failed: $reason",
        cause = cause
    )

/** Resource read failed (-32001).
  *
  * Available for a handler body that wants to signal a resource-read failure. The `uri` field
  * identifies the resource; the optional `cause` chains the underlying throwable. Not produced by
  * the engine, so it mixes into no operation-trait.
  *
  * @param uri    the URI of the resource that could not be read
  * @param reason a brief description of the read failure
  * @param cause  optional underlying throwable or string cause
  */
case class McpResourceReadException(uri: McpResourceUri, reason: String, cause: Maybe[Throwable] = Absent)(using Frame)
    extends McpException(
        code = -32001,
        message = s"Failed to read resource '${uri.asString}': $reason",
        cause = cause
    )

/** Prompt render failed (-32003).
  *
  * Available for a handler body that wants to signal a prompt-render failure. The `name` field
  * identifies the prompt; the optional `cause` chains the underlying throwable. Not produced by the
  * engine, so it mixes into no operation-trait.
  *
  * @param name   the name of the prompt that failed to render
  * @param reason a brief description of the render failure
  * @param cause  optional underlying throwable or string cause
  */
case class McpPromptRenderException(name: String, reason: String, cause: Maybe[Throwable] = Absent)(using Frame)
    extends McpException(
        code = -32003,
        message = s"Failed to render prompt '$name': $reason",
        cause = cause
    )

/** Remote application error received from the peer.
  *
  * Surfaces user-defined error types that the remote registered via
  * `route.error[E2](code, message)` on a server-side handler. The wire code, message, and data
  * triple are preserved verbatim; the caller pattern-matches on `code` to discriminate across
  * user-defined error families. The client forward operations map every non-recovered remote
  * `JsonRpcError` into this leaf, so it mixes into each of those rows.
  *
  * @param remoteCode    the JSON-RPC error code from the wire response
  * @param remoteMessage the JSON-RPC error message from the wire response
  * @param remoteData    the Schema-encoded `data` payload (Absent when the peer did not include one)
  */
case class McpRemoteApplicationException(
    remoteCode: Int,
    remoteMessage: String,
    remoteData: Maybe[Structure.Value] = Absent
)(using Frame)
    extends McpException(code = remoteCode, message = remoteMessage, cause = Absent, data = remoteData)
    with McpCallToolFailure
    with McpCallToolRawFailure
    with McpReadResourceFailure
    with McpReadResourceRawFailure
    with McpGetPromptFailure
    with McpGetPromptRawFailure
    with McpGetPromptCheckedFailure
    with McpListFailure
    with McpCompleteFailure
    with McpClientRequestFailure
