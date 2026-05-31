package kyo

/** Typed route descriptor for the MCP route DSL.
  *
  * Users construct routes via the companion factory methods: `tool`, `toolMulti`, `resource`,
  * `resourceTemplate`, `prompt`, `completion`, `custom`. Each factory captures the `Schema`
  * evidence at construction time so the engine can encode/decode request and response payloads.
  *
  * The `+E` type parameter accumulates user-domain error types registered via `.error[E2]`.
  * Domain errors abort the handler and are encoded as JSON-RPC error responses.
  *
  * Mirrors `JsonRpcRoute[In, Out, +E]` at kyo-jsonrpc/.../JsonRpcRoute.scala:42.
  *
  * @tparam In  the request parameter type
  * @tparam Out the response result type
  * @tparam E   the union of user-registered domain error types
  */
sealed trait McpRoute[In, Out, +E]:
    def name: String
    def kind: McpRoute.Kind
    def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): McpRoute[In, Out, E | E2]
    private[kyo] def underlying: JsonRpcRoute[?, ?, ?]
end McpRoute

object McpRoute:

    /** The operational category of a route. */
    enum Kind derives CanEqual:
        case Tool, Resource, ResourceTemplate, Prompt, Notification, Custom

    /** Per-request context supplied to every route handler by the engine.
      *
      * Provides access to the underlying `JsonRpcRoute.Context` (cancellation, requestId, extras)
      * and to the live `McpServer` handle for reverse-direction calls.
      *
      * The constructor is `private[kyo]`; handlers receive instances only from the engine.
      * INV-024: `server` is typed `McpServer` (safe opaque), never `McpServer.Unsafe`.
      */
    final class Context private[kyo] (val underlying: JsonRpcRoute.Context, val server: McpServer):
        export underlying.cancelled
        export underlying.extras
        export underlying.requestId

        /** Reports a progress notification back to the caller. Phase 1 stub; Phase 5 fills. */
        def progress(
            current: Double,
            total: Maybe[Double] = Absent,
            message: Maybe[String] = Absent
        )(using Frame): Unit < (Async & Abort[Closed]) =
            throw new NotImplementedError("Context.progress stub: body filled in Phase 5")

    end Context

    /** Metadata returned by `McpClient.listTools`. */
    final case class ToolMeta(
        name: String,
        description: Maybe[String],
        inputSchema: Json.JsonSchema,
        outputSchema: Maybe[Json.JsonSchema],
        annotations: Maybe[ToolAnnotations]
    ) derives Schema, CanEqual

    /** Optional display and behavioral hints for a tool. */
    final case class ToolAnnotations(
        title: Maybe[String],
        readOnlyHint: Maybe[Boolean],
        destructiveHint: Maybe[Boolean],
        idempotentHint: Maybe[Boolean],
        openWorldHint: Maybe[Boolean]
    ) derives Schema, CanEqual

    /** The result of a `tools/call` request.
      *
      * `structuredContent` is an INV-021 allowlist pass-through: the MCP spec defines
      * `structuredContent` as an open JSON object for typed tool output (Audit-A9).
      */
    final case class ToolCallResult(
        content: Chunk[McpContent],
        isError: Boolean,
        // flow-allow: Structure carve-out per §11a / INV-021
        structuredContent: Maybe[Structure.Value]
    ) derives Schema, CanEqual

    /** Metadata returned by `McpClient.listResources`.
      * INV-022: `uri` is typed `McpResourceUri`, not raw `String`.
      */
    final case class ResourceMeta(
        uri: McpResourceUri,
        name: String,
        description: Maybe[String],
        mimeType: Maybe[String],
        annotations: Maybe[ResourceAnnotations]
    ) derives Schema, CanEqual

    /** Metadata returned by `McpClient.listResourceTemplates`.
      * INV-022: `uriTemplate` is typed `McpResourceUriTemplate`, not raw `String`.
      */
    final case class ResourceTemplateMeta(
        uriTemplate: McpResourceUriTemplate,
        name: String,
        description: Maybe[String],
        mimeType: Maybe[String],
        annotations: Maybe[ResourceAnnotations]
    ) derives Schema, CanEqual

    /** Optional display and filtering hints for a resource. */
    final case class ResourceAnnotations(
        audience: Maybe[Chunk[McpRole]],
        priority: Maybe[Double]
    ) derives Schema, CanEqual

    /** Metadata returned by `McpClient.listPrompts`. */
    final case class PromptMeta(
        name: String,
        description: Maybe[String],
        arguments: Chunk[PromptArgument]
    ) derives Schema, CanEqual

    /** A declared argument for a prompt. */
    final case class PromptArgument(
        name: String,
        description: Maybe[String],
        required: Boolean
    ) derives Schema, CanEqual

    /** The result of a `prompts/get` request. */
    final case class PromptGetResult(
        description: Maybe[String],
        messages: Chunk[PromptMessage]
    ) derives Schema, CanEqual

    /** A single message in a prompt result. */
    final case class PromptMessage(role: McpRole, content: McpContent) derives Schema, CanEqual

    /** Identifies the target of a completion request.
      * INV-022: `Resource.uri` is typed `McpResourceUri`.
      */
    enum CompletionRef derives CanEqual:
        case Prompt(name: String)
        case Resource(uri: McpResourceUri)

    /** Named argument descriptor for a completion request (Audit-A8 / INV-026). */
    final case class CompletionArg(name: String, value: String) derives Schema, CanEqual

    /** The result of a `completion/complete` request. */
    final case class CompletionResult(
        values: Chunk[String],
        total: Maybe[Int],
        hasMore: Maybe[Boolean]
    ) derives Schema, CanEqual

    // --- Factory methods (Phase 1 stubs; Phase 5 provides engine implementations) ---

    /** Registers a single-content tool route. Phase 1 stub; Phase 5 fills the body. */
    def tool[In: Schema, Out <: McpContent: Schema](
        name: String,
        description: String = "",
        annotations: Maybe[ToolAnnotations] = Absent
    )(handler: (In, Context) => Out < (Async & Abort[McpError | JsonRpcResponse.Halt]))(using Frame): McpRoute[In, Out, Nothing] =
        throw new NotImplementedError("McpRoute.tool stub: body filled in Phase 5")

    /** Registers a multi-content tool route returning a `ToolCallResult`. Phase 1 stub. */
    def toolMulti[In: Schema](
        name: String,
        description: String = "",
        annotations: Maybe[ToolAnnotations] = Absent
    )(handler: (In, Context) => ToolCallResult < (Async & Abort[McpError | JsonRpcResponse.Halt]))(using
        Frame
    ): McpRoute[In, ToolCallResult, Nothing] =
        throw new NotImplementedError("McpRoute.toolMulti stub: body filled in Phase 5")

    /** Registers a fixed-URI resource route. Phase 1 stub. */
    def resource(
        uri: McpResourceUri,
        name: String,
        description: String = "",
        mimeType: Maybe[String] = Absent,
        annotations: Maybe[ResourceAnnotations] = Absent
    )(handler: (McpResourceUri, Context) => Chunk[McpResourceContents] < (Async & Abort[McpError | JsonRpcResponse.Halt]))(using
        Frame
    ): McpRoute[McpResourceUri, Chunk[McpResourceContents], Nothing] =
        throw new NotImplementedError("McpRoute.resource stub: body filled in Phase 5")

    /** Registers a URI-template resource route. Phase 1 stub. */
    def resourceTemplate(
        uriTemplate: McpResourceUriTemplate,
        name: String,
        description: String = "",
        mimeType: Maybe[String] = Absent,
        annotations: Maybe[ResourceAnnotations] = Absent
    )(handler: (McpResourceUri, Context) => Chunk[McpResourceContents] < (Async & Abort[McpError | JsonRpcResponse.Halt]))(using
        Frame
    ): McpRoute[McpResourceUri, Chunk[McpResourceContents], Nothing] =
        throw new NotImplementedError("McpRoute.resourceTemplate stub: body filled in Phase 5")

    /** Registers a prompt route. Phase 1 stub. */
    def prompt(
        name: String,
        description: String = "",
        arguments: Chunk[PromptArgument] = Chunk.empty
    )(handler: (Map[String, String], Context) => PromptGetResult < (Async & Abort[McpError | JsonRpcResponse.Halt]))(using
        Frame
    ): McpRoute[Map[String, String], PromptGetResult, Nothing] =
        throw new NotImplementedError("McpRoute.prompt stub: body filled in Phase 5")

    /** Registers a completion handler for a prompt or resource. Phase 1 stub. */
    def completion(
        ref: CompletionRef
    )(handler: (CompletionRef, CompletionArg, Context) => CompletionResult < (Async & Abort[McpError | JsonRpcResponse.Halt]))(using
        Frame
    ): McpRoute[(CompletionRef, CompletionArg), CompletionResult, Nothing] =
        throw new NotImplementedError("McpRoute.completion stub: body filled in Phase 5")

    /** Registers a custom method route. Phase 1 stub. */
    def custom[In: Schema, Out: Schema](method: String)(
        handler: (In, Context) => Out < (Async & Abort[McpError | JsonRpcResponse.Halt])
    )(using Frame): McpRoute[In, Out, Nothing] =
        throw new NotImplementedError("McpRoute.custom stub: body filled in Phase 5")

end McpRoute
