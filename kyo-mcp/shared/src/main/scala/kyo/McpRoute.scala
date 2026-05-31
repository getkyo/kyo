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
  * Factory methods use clause interleaving on `[In](...)(using Schema[In])[Out](handler)(using Schema[Out])`
  * so the user can write `McpRoute.tool[AddIn]("add") { (in, _) => ... }` and the compiler infers
  * `Out` from the handler's return type. The explicit `[In, Out]` form remains valid for callers
  * that prefer to pin both type arguments.
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

        /** Reports a progress notification back to the caller. */
        def progress(
            progress: Double,
            total: Maybe[Double] = Absent,
            message: Maybe[String] = Absent
        )(using Frame): Unit < (Async & Abort[Closed]) =
            internal.mcp.McpProgressPolicy.report(underlying, progress, total, message)

    end Context

    /** Metadata returned by `McpClient.listTools`. */
    final case class ToolMeta(
        name: String,
        description: Maybe[String],
        inputSchema: Json.JsonSchema,
        outputSchema: Maybe[Json.JsonSchema],
        annotations: Maybe[ToolAnnotations]
    ) derives Schema, CanEqual

    /** Optional display and behavioral hints for a tool.
      *
      * `ToolAnnotations.noop` is the empty record used as the default parameter for every tool
      * factory; the factory translates `.noop` into `Absent` on the wire so the field is omitted.
      */
    final case class ToolAnnotations(
        title: Maybe[String] = Absent,
        readOnlyHint: Maybe[Boolean] = Absent,
        destructiveHint: Maybe[Boolean] = Absent,
        idempotentHint: Maybe[Boolean] = Absent,
        openWorldHint: Maybe[Boolean] = Absent
    ) derives Schema, CanEqual

    object ToolAnnotations:
        /** The empty annotations record used as the factory default. */
        val noop: ToolAnnotations = ToolAnnotations()
    end ToolAnnotations

    /** The result of a `tools/call` request.
      *
      * `structuredContent` is an INV-021 allowlist pass-through: the MCP spec defines
      * `structuredContent` as an open JSON object for typed tool output (Audit-A9).
      * `meta` is an INV-021 allowlist pass-through for the MCP spec `_meta` advisory field (§3.7).
      */
    final case class ToolCallResult(
        content: Chunk[McpContent],
        isError: Boolean,
        // flow-allow: Structure carve-out per §11a / INV-021
        structuredContent: Maybe[Structure.Value],
        // flow-allow: Structure carve-out per §11a / INV-021
        meta: Maybe[Structure.Value] = Absent
    ) derives Schema, CanEqual

    /** Metadata returned by `McpClient.listResources`.
      * INV-022: `uri` is typed `McpResourceUri`, not raw `String`.
      */
    final case class ResourceMeta(
        uri: McpResourceUri,
        name: String,
        description: Maybe[String],
        mimeType: Maybe[McpMimeType],
        annotations: Maybe[ResourceAnnotations]
    ) derives Schema, CanEqual

    /** Metadata returned by `McpClient.listResourceTemplates`.
      * INV-022: `uriTemplate` is typed `McpResourceUriTemplate`, not raw `String`.
      */
    final case class ResourceTemplateMeta(
        uriTemplate: McpResourceUriTemplate,
        name: String,
        description: Maybe[String],
        mimeType: Maybe[McpMimeType],
        annotations: Maybe[ResourceAnnotations]
    ) derives Schema, CanEqual

    /** Optional display and filtering hints for a resource.
      *
      * `ResourceAnnotations.noop` is the empty record used as the default parameter for every
      * resource and resource-template factory; the factory translates `.noop` into `Absent` on
      * the wire so the field is omitted.
      */
    final case class ResourceAnnotations(
        audience: Maybe[Chunk[McpRole]] = Absent,
        priority: Maybe[Double] = Absent
    ) derives Schema, CanEqual

    object ResourceAnnotations:
        /** The empty resource annotations record used as the factory default. */
        val noop: ResourceAnnotations = ResourceAnnotations()
    end ResourceAnnotations

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

    /** The result of a `prompts/get` request.
      *
      * `meta` is an INV-021 allowlist pass-through for the MCP spec `_meta` advisory field (§3.7).
      */
    final case class PromptGetResult(
        description: Maybe[String],
        messages: Chunk[PromptMessage],
        // flow-allow: Structure carve-out per §11a / INV-021
        meta: Maybe[Structure.Value] = Absent
    ) derives Schema, CanEqual

    /** A single message in a prompt result. */
    final case class PromptMessage(role: McpRole, content: McpContent) derives Schema, CanEqual

    /** Identifies the target of a completion request.
      * INV-022: `Resource.uri` is typed `McpResourceUri`.
      * The on-wire discriminator key is `"type"` with values `"ref/prompt"` and `"ref/resource"`.
      * The hand-rolled Schema lives in `kyo/internal/McpCompletionRefSchema.scala`.
      */
    enum CompletionRef derives CanEqual:
        case Prompt(name: String)
        case Resource(uri: McpResourceUri)

    object CompletionRef:
        given Schema[CompletionRef] = internal.McpCompletionRefSchema.schema

    /** Named argument descriptor for a completion request (Audit-A8 / INV-026). */
    final case class CompletionArg(name: String, value: String) derives Schema, CanEqual

    object CompletionArg:
        /** Additional context for a completion request, carrying previously filled argument values (§3.17). */
        final case class Context(arguments: Map[String, String]) derives Schema, CanEqual
    end CompletionArg

    /** The result of a `completion/complete` request. */
    final case class CompletionResult(
        values: Chunk[String],
        total: Maybe[Int],
        hasMore: Maybe[Boolean]
    ) derives Schema, CanEqual

    // --- Factory methods ---

    // Helpers: translate the noop sentinel into the wire-friendly Maybe shape used by the meta records.
    private inline def toolAnnotationsMaybe(a: ToolAnnotations): Maybe[ToolAnnotations] =
        if a == ToolAnnotations.noop then Absent else Present(a)
    private inline def resourceAnnotationsMaybe(a: ResourceAnnotations): Maybe[ResourceAnnotations] =
        if a == ResourceAnnotations.noop then Absent else Present(a)

    /** Registers a single-content tool route.
      *
      * The handler receives a typed `In` parameter and returns a single `Out <: McpContent`.
      * INV-020: distinct from `toolMulti` which returns `ToolCallResult`.
      *
      * Clause interleaving lets callers write `McpRoute.tool[AddIn]("add") { ... }` and the
      * compiler infers `Out` from the handler's return type. The explicit `[In, Out]` form
      * also compiles for callers that prefer to pin both type arguments.
      */
    def tool[In](
        name: String,
        description: String = "",
        annotations: ToolAnnotations = ToolAnnotations.noop
    )(using
        inSchema: Schema[In]
    )[Out <: McpContent](
        handler: (In, Context) => Out < (Async & Abort[McpError | JsonRpcResponse.Halt])
    )(using outSchema: Schema[Out], frame: Frame): McpRoute[In, Out, Nothing] =
        // AllowUnsafe: AtomicRef for forward McpServer reference (Decision 2).
        val serverRef = AtomicRef.Unsafe.init[Maybe[McpServer.Unsafe]](Absent)(using AllowUnsafe.embrace.danger).safe
        val meta = ToolMeta(
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            inputSchema = Json.jsonSchema[In],
            outputSchema = Present(Json.jsonSchema[Out]),
            annotations = toolAnnotationsMaybe(annotations)
        )
        val underlying = JsonRpcRoute.request[In, ToolCallResult](name) { (in, jrCtx) =>
            val server = serverRef.unsafe.get()(using AllowUnsafe.embrace.danger).getOrElse(
                throw new IllegalStateException(s"McpServer not initialized for tool '$name'")
            )
            val ctx = new Context(jrCtx, server.safe)
            handler(in, ctx).map(out => ToolCallResult(Chunk(out), isError = false, structuredContent = Absent))
        }(using inSchema, summon[Schema[ToolCallResult]])
        McpRouteCarrier.Tool(name, meta, inSchema, outSchema, handler, serverRef, underlying)
    end tool

    /** Registers a multi-content tool route returning a `ToolCallResult`. INV-020. */
    def toolMulti[In](
        name: String,
        description: String = "",
        annotations: ToolAnnotations = ToolAnnotations.noop
    )(using
        inSchema: Schema[In]
    )(
        handler: (In, Context) => ToolCallResult < (Async & Abort[McpError | JsonRpcResponse.Halt])
    )(using Frame): McpRoute[In, ToolCallResult, Nothing] =
        // AllowUnsafe: AtomicRef for forward McpServer reference (Decision 2).
        val serverRef = AtomicRef.Unsafe.init[Maybe[McpServer.Unsafe]](Absent)(using AllowUnsafe.embrace.danger).safe
        val meta = ToolMeta(
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            inputSchema = Json.jsonSchema[In],
            outputSchema = Absent,
            annotations = toolAnnotationsMaybe(annotations)
        )
        val underlying = JsonRpcRoute.request[In, ToolCallResult](name) { (in, jrCtx) =>
            val server = serverRef.unsafe.get()(using AllowUnsafe.embrace.danger).getOrElse(
                throw new IllegalStateException(s"McpServer not initialized for toolMulti '$name'")
            )
            val ctx = new Context(jrCtx, server.safe)
            handler(in, ctx)
        }(using inSchema, summon[Schema[ToolCallResult]])
        McpRouteCarrier.ToolMulti(name, meta, inSchema, handler, serverRef, underlying)
    end toolMulti

    /** Registers a fixed-URI resource route.
      *
      * @param subscribe when `true`, this resource opts into the subscription protocol (§3.4 / Q9).
      *                  The server advertises `resources.subscribe = true` when any resource route
      *                  sets this flag. Clients may then call `subscribeResource` / `unsubscribeResource`.
      */
    def resource(
        uri: McpResourceUri,
        name: String,
        description: String = "",
        mimeType: Maybe[McpMimeType] = Absent,
        annotations: ResourceAnnotations = ResourceAnnotations.noop,
        subscribe: Boolean = false
    )(handler: (McpResourceUri, Context) => Chunk[McpResourceContents] < (Async & Abort[McpError | JsonRpcResponse.Halt]))(using
        Frame
    ): McpRoute[McpResourceUri, Chunk[McpResourceContents], Nothing] =
        // AllowUnsafe: AtomicRef for forward McpServer reference (Decision 2).
        val serverRef = AtomicRef.Unsafe.init[Maybe[McpServer.Unsafe]](Absent)(using AllowUnsafe.embrace.danger).safe
        val meta = ResourceMeta(
            uri = uri,
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            mimeType = mimeType,
            annotations = resourceAnnotationsMaybe(annotations)
        )
        val underlying = JsonRpcRoute.request[McpResourceUri, Chunk[McpResourceContents]](uri.asString) { (u, jrCtx) =>
            val server = serverRef.unsafe.get()(using AllowUnsafe.embrace.danger).getOrElse(
                throw new IllegalStateException(s"McpServer not initialized for resource '${uri.asString}'")
            )
            val ctx = new Context(jrCtx, server.safe)
            handler(u, ctx)
        }
        McpRouteCarrier.Resource(name, meta, handler, serverRef, underlying, subscribe)
    end resource

    /** Registers a URI-template resource route. */
    def resourceTemplate(
        uriTemplate: McpResourceUriTemplate,
        name: String,
        description: String = "",
        mimeType: Maybe[McpMimeType] = Absent,
        annotations: ResourceAnnotations = ResourceAnnotations.noop
    )(handler: (McpResourceUri, Context) => Chunk[McpResourceContents] < (Async & Abort[McpError | JsonRpcResponse.Halt]))(using
        Frame
    ): McpRoute[McpResourceUri, Chunk[McpResourceContents], Nothing] =
        // AllowUnsafe: AtomicRef for forward McpServer reference (Decision 2).
        val serverRef = AtomicRef.Unsafe.init[Maybe[McpServer.Unsafe]](Absent)(using AllowUnsafe.embrace.danger).safe
        val meta = ResourceTemplateMeta(
            uriTemplate = uriTemplate,
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            mimeType = mimeType,
            annotations = resourceAnnotationsMaybe(annotations)
        )
        val underlying = JsonRpcRoute.request[McpResourceUri, Chunk[McpResourceContents]](uriTemplate.asString) { (u, jrCtx) =>
            val server = serverRef.unsafe.get()(using AllowUnsafe.embrace.danger).getOrElse(
                throw new IllegalStateException(s"McpServer not initialized for resourceTemplate '${uriTemplate.asString}'")
            )
            val ctx = new Context(jrCtx, server.safe)
            handler(u, ctx)
        }
        McpRouteCarrier.ResourceTemplate(name, meta, handler, serverRef, underlying)
    end resourceTemplate

    /** Registers a prompt route. */
    def prompt(
        name: String,
        description: String = "",
        arguments: Chunk[PromptArgument] = Chunk.empty
    )(handler: (Map[String, String], Context) => PromptGetResult < (Async & Abort[McpError | JsonRpcResponse.Halt]))(using
        Frame
    ): McpRoute[Map[String, String], PromptGetResult, Nothing] =
        // AllowUnsafe: AtomicRef for forward McpServer reference (Decision 2).
        val serverRef = AtomicRef.Unsafe.init[Maybe[McpServer.Unsafe]](Absent)(using AllowUnsafe.embrace.danger).safe
        val meta =
            PromptMeta(name = name, description = if description.isEmpty then Absent else Present(description), arguments = arguments)
        val underlying = JsonRpcRoute.request[Map[String, String], PromptGetResult](name) { (args, jrCtx) =>
            val server = serverRef.unsafe.get()(using AllowUnsafe.embrace.danger).getOrElse(
                throw new IllegalStateException(s"McpServer not initialized for prompt '$name'")
            )
            val ctx = new Context(jrCtx, server.safe)
            handler(args, ctx)
        }
        McpRouteCarrier.Prompt(name, meta, handler, serverRef, underlying)
    end prompt

    /** Registers a completion handler for a prompt or resource. INV-026: `CompletionArg` is a named record.
      *
      * The handler receives the ref, argument, optional context (carrying previously filled argument values
      * per §3.17), and the request context.
      */
    def completion(
        ref: CompletionRef
    )(handler: (
        CompletionRef,
        CompletionArg,
        Maybe[CompletionArg.Context],
        Context
    ) => CompletionResult < (Async & Abort[McpError | JsonRpcResponse.Halt]))(using
        Frame
    ): McpRoute[(CompletionRef, CompletionArg), CompletionResult, Nothing] =
        // AllowUnsafe: AtomicRef for forward McpServer reference (Decision 2).
        val serverRef = AtomicRef.Unsafe.init[Maybe[McpServer.Unsafe]](Absent)(using AllowUnsafe.embrace.danger).safe
        val routeName = ref match
            case CompletionRef.Prompt(n)   => s"completion/prompt/$n"
            case CompletionRef.Resource(u) => s"completion/resource/${u.asString}"
        val underlying = JsonRpcRoute.request[(CompletionRef, CompletionArg), CompletionResult](routeName) { (refAndArg, jrCtx) =>
            val server = serverRef.unsafe.get()(using AllowUnsafe.embrace.danger).getOrElse(
                throw new IllegalStateException(s"McpServer not initialized for completion '$routeName'")
            )
            val ctx = new Context(jrCtx, server.safe)
            handler(refAndArg._1, refAndArg._2, Absent, ctx)
        }
        McpRouteCarrier.Completion(routeName, ref, handler, serverRef, underlying)
    end completion

    /** Registers a custom method route.
      *
      * Clause interleaving lets callers write `McpRoute.custom[In](method) { ... }` and the
      * compiler infers `Out` from the handler's return type.
      */
    def custom[In](method: String)(using
        inSchema: Schema[In]
    )[Out](
        handler: (In, Context) => Out < (Async & Abort[McpError | JsonRpcResponse.Halt])
    )(using outSchema: Schema[Out], frame: Frame): McpRoute[In, Out, Nothing] =
        // AllowUnsafe: AtomicRef for forward McpServer reference (Decision 2).
        val serverRef = AtomicRef.Unsafe.init[Maybe[McpServer.Unsafe]](Absent)(using AllowUnsafe.embrace.danger).safe
        val underlying = JsonRpcRoute.request[In, Out](method) { (in, jrCtx) =>
            val server = serverRef.unsafe.get()(using AllowUnsafe.embrace.danger).getOrElse(
                throw new IllegalStateException(s"McpServer not initialized for custom method '$method'")
            )
            val ctx = new Context(jrCtx, server.safe)
            handler(in, ctx)
        }(using inSchema, outSchema)
        McpRouteCarrier.Custom(method, method, handler, serverRef, underlying)
    end custom

end McpRoute

// Internal carrier trait: extends McpRoute in the same source file to satisfy the sealed constraint.
// private[kyo] so engine code in kyo.internal.mcp can pattern-match on the concrete carrier types.
sealed private[kyo] trait McpRouteCarrier[In, Out, +E] extends McpRoute[In, Out, E]:
    private[kyo] def serverRef: AtomicRef[Maybe[McpServer.Unsafe]]

private[kyo] object McpRouteCarrier:

    /** Marker trait for routes that register a logging hook.
      * Used by [[kyo.internal.mcp.McpCatalog]] to auto-derive the `logging` server capability.
      * A route that extends this marker causes the catalog to set `logging = Present(...)`.
      */
    sealed trait LoggingHook

    /** Carrier for a single-content tool route. */
    final class Tool[In, Out <: McpContent] private[kyo] (
        val name: String,
        val toolMeta: McpRoute.ToolMeta,
        val inSchema: Schema[In],
        val outSchema: Schema[Out],
        val handler: (In, McpRoute.Context) => Out < (Async & Abort[McpError | JsonRpcResponse.Halt]),
        val serverRef: AtomicRef[Maybe[McpServer.Unsafe]],
        private[kyo] val underlyingRoute: JsonRpcRoute[?, ?, ?]
    ) extends McpRouteCarrier[In, Out, Nothing]:
        val kind: McpRoute.Kind = McpRoute.Kind.Tool
        def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): McpRoute[In, Out, Nothing | E2] =
            this
        private[kyo] def underlying: JsonRpcRoute[?, ?, ?] = underlyingRoute
    end Tool

    /** Carrier for a multi-content tool route. */
    final class ToolMulti[In] private[kyo] (
        val name: String,
        val toolMeta: McpRoute.ToolMeta,
        val inSchema: Schema[In],
        val handler: (In, McpRoute.Context) => McpRoute.ToolCallResult < (Async & Abort[McpError | JsonRpcResponse.Halt]),
        val serverRef: AtomicRef[Maybe[McpServer.Unsafe]],
        private[kyo] val underlyingRoute: JsonRpcRoute[?, ?, ?]
    ) extends McpRouteCarrier[In, McpRoute.ToolCallResult, Nothing]:
        val kind: McpRoute.Kind = McpRoute.Kind.Tool
        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpRoute[In, McpRoute.ToolCallResult, Nothing | E2] =
            this
        private[kyo] def underlying: JsonRpcRoute[?, ?, ?] = underlyingRoute
    end ToolMulti

    /** Carrier for a fixed-URI resource route. */
    final class Resource[Out] private[kyo] (
        val name: String,
        val resourceMeta: McpRoute.ResourceMeta,
        val handler: (McpResourceUri, McpRoute.Context) => Chunk[McpResourceContents] < (Async & Abort[McpError | JsonRpcResponse.Halt]),
        val serverRef: AtomicRef[Maybe[McpServer.Unsafe]],
        private[kyo] val underlyingRoute: JsonRpcRoute[?, ?, ?],
        val subscribable: Boolean = false
    ) extends McpRouteCarrier[McpResourceUri, Chunk[McpResourceContents], Nothing]:
        val kind: McpRoute.Kind = McpRoute.Kind.Resource
        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpRoute[McpResourceUri, Chunk[McpResourceContents], Nothing | E2] =
            this
        private[kyo] def underlying: JsonRpcRoute[?, ?, ?] = underlyingRoute
    end Resource

    /** Carrier for a URI-template resource route. */
    final class ResourceTemplate[Out] private[kyo] (
        val name: String,
        val resourceTemplateMeta: McpRoute.ResourceTemplateMeta,
        val handler: (McpResourceUri, McpRoute.Context) => Chunk[McpResourceContents] < (Async & Abort[McpError | JsonRpcResponse.Halt]),
        val serverRef: AtomicRef[Maybe[McpServer.Unsafe]],
        private[kyo] val underlyingRoute: JsonRpcRoute[?, ?, ?]
    ) extends McpRouteCarrier[McpResourceUri, Chunk[McpResourceContents], Nothing]:
        val kind: McpRoute.Kind = McpRoute.Kind.ResourceTemplate
        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpRoute[McpResourceUri, Chunk[McpResourceContents], Nothing | E2] =
            this
        private[kyo] def underlying: JsonRpcRoute[?, ?, ?] = underlyingRoute
    end ResourceTemplate

    /** Carrier for a prompt route. */
    final class Prompt[Out] private[kyo] (
        val name: String,
        val promptMeta: McpRoute.PromptMeta,
        val handler: (Map[String, String], McpRoute.Context) => McpRoute.PromptGetResult < (Async & Abort[McpError | JsonRpcResponse.Halt]),
        val serverRef: AtomicRef[Maybe[McpServer.Unsafe]],
        private[kyo] val underlyingRoute: JsonRpcRoute[?, ?, ?]
    ) extends McpRouteCarrier[Map[String, String], McpRoute.PromptGetResult, Nothing]:
        val kind: McpRoute.Kind = McpRoute.Kind.Prompt
        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpRoute[Map[String, String], McpRoute.PromptGetResult, Nothing | E2] =
            this
        private[kyo] def underlying: JsonRpcRoute[?, ?, ?] = underlyingRoute
    end Prompt

    /** Carrier for a completion route. */
    final class Completion private[kyo] (
        val name: String,
        val ref: McpRoute.CompletionRef,
        val handler: (
            McpRoute.CompletionRef,
            McpRoute.CompletionArg,
            Maybe[McpRoute.CompletionArg.Context],
            McpRoute.Context
        ) => McpRoute.CompletionResult < (Async & Abort[McpError | JsonRpcResponse.Halt]),
        val serverRef: AtomicRef[Maybe[McpServer.Unsafe]],
        private[kyo] val underlyingRoute: JsonRpcRoute[?, ?, ?]
    ) extends McpRouteCarrier[(McpRoute.CompletionRef, McpRoute.CompletionArg), McpRoute.CompletionResult, Nothing]:
        val kind: McpRoute.Kind = McpRoute.Kind.Custom
        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpRoute[(McpRoute.CompletionRef, McpRoute.CompletionArg), McpRoute.CompletionResult, Nothing | E2] =
            this
        private[kyo] def underlying: JsonRpcRoute[?, ?, ?] = underlyingRoute
    end Completion

    /** Carrier for a custom method route. */
    final class Custom[In, Out] private[kyo] (
        val name: String,
        val method: String,
        val handler: (In, McpRoute.Context) => Out < (Async & Abort[McpError | JsonRpcResponse.Halt]),
        val serverRef: AtomicRef[Maybe[McpServer.Unsafe]],
        private[kyo] val underlyingRoute: JsonRpcRoute[?, ?, ?]
    ) extends McpRouteCarrier[In, Out, Nothing]:
        val kind: McpRoute.Kind = McpRoute.Kind.Custom
        def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): McpRoute[In, Out, Nothing | E2] =
            this
        private[kyo] def underlying: JsonRpcRoute[?, ?, ?] = underlyingRoute
    end Custom

end McpRouteCarrier
