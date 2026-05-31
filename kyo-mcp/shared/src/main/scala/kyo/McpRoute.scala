package kyo

/** Metadata-only route descriptor for the MCP route DSL.
  *
  * `McpRoute[In]` carries only the descriptive surface of an MCP endpoint (name, kind,
  * schema, capability hints, annotations). The closure that implements the route lives on
  * [[McpHandler]], produced by calling `.handler[Out](f)` (or `.handlerWith[Out]` for the
  * completion factory which needs additional handler parameters).
  *
  * This split mirrors `HttpRoute` vs `HttpHandler` in `kyo-http`: a route is the typed
  * contract, a handler is the contract paired with an implementation.
  *
  * Users construct routes via the companion factory methods: `tool`, `toolMulti`, `resource`,
  * `resourceTemplate`, `prompt`, `completion`, `custom`. Each factory captures the `Schema`
  * evidence at construction time so the engine can encode/decode request and response payloads.
  *
  * Factory clauses interleave `[In](...)(using Schema[In])` and `.handler[Out](f)(using Schema[Out])`
  * so call sites only need to annotate `[In]`; `Out` is inferred from the handler's return type.
  *
  * @tparam In the request parameter type
  */
sealed trait McpRoute[In]:
    def name: String
    def kind: McpRoute.Kind
end McpRoute

object McpRoute:

    /** The operational category of a route. */
    enum Kind derives CanEqual:
        case Tool, Resource, ResourceTemplate, Prompt, Notification, Custom

    /** Metadata returned by `McpClient.listTools`. */
    final case class ToolMeta(
        name: String,
        description: Maybe[String],
        inputSchema: Json.JsonSchema,
        outputSchema: Maybe[Json.JsonSchema],
        annotations: Maybe[ToolAnnotations],
        title: Maybe[String] = Absent
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
        annotations: Maybe[ResourceAnnotations],
        title: Maybe[String] = Absent,
        lastModified: Maybe[String] = Absent,
        size: Maybe[Long] = Absent
    ) derives Schema, CanEqual

    /** Metadata returned by `McpClient.listResourceTemplates`.
      * INV-022: `uriTemplate` is typed `McpResourceUriTemplate`, not raw `String`.
      */
    final case class ResourceTemplateMeta(
        uriTemplate: McpResourceUriTemplate,
        name: String,
        description: Maybe[String],
        mimeType: Maybe[McpMimeType],
        annotations: Maybe[ResourceAnnotations],
        title: Maybe[String] = Absent
    ) derives Schema, CanEqual

    /** Optional display and filtering hints for a resource.
      *
      * `ResourceAnnotations.noop` is the empty record used as the default parameter for every
      * resource and resource-template factory; the factory translates `.noop` into `Absent` on
      * the wire so the field is omitted.
      */
    final case class ResourceAnnotations(
        audience: Maybe[Chunk[McpRole]] = Absent,
        priority: Maybe[Double] = Absent,
        lastModified: Maybe[String] = Absent
    ) derives Schema, CanEqual

    object ResourceAnnotations:
        /** The empty resource annotations record used as the factory default. */
        val noop: ResourceAnnotations = ResourceAnnotations()
    end ResourceAnnotations

    /** Metadata returned by `McpClient.listPrompts`. */
    final case class PromptMeta(
        name: String,
        description: Maybe[String],
        arguments: Chunk[PromptArgument],
        title: Maybe[String] = Absent
    ) derives Schema, CanEqual

    /** A declared argument for a prompt. */
    final case class PromptArgument(
        name: String,
        description: Maybe[String],
        required: Boolean,
        title: Maybe[String] = Absent
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

    /** Registers a single-content tool route descriptor.
      *
      * Call `.handler[Out](f)` on the returned value to bind a handler closure and produce an
      * [[McpHandler]]. `Out <: McpContent`; the engine wraps the handler's return in a
      * `ToolCallResult` with `content = Chunk(out)`. INV-020: distinct from `toolMulti` which
      * returns `ToolCallResult` directly.
      */
    def tool[In](
        name: String,
        description: String = "",
        annotations: ToolAnnotations = ToolAnnotations.noop
    )(using inSchema: Schema[In]): ToolRoute[In] =
        val meta = ToolMeta(
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            inputSchema = Json.jsonSchema[In],
            outputSchema = Absent,
            annotations = toolAnnotationsMaybe(annotations)
        )
        ToolRoute(meta, inSchema)
    end tool

    /** Registers a multi-content tool route descriptor. INV-020.
      *
      * Call `.handler(f)` on the returned value to bind a handler closure that returns
      * `ToolCallResult` directly (no engine wrapping).
      */
    def toolMulti[In](
        name: String,
        description: String = "",
        annotations: ToolAnnotations = ToolAnnotations.noop
    )(using inSchema: Schema[In]): ToolMultiRoute[In] =
        val meta = ToolMeta(
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            inputSchema = Json.jsonSchema[In],
            outputSchema = Absent,
            annotations = toolAnnotationsMaybe(annotations)
        )
        ToolMultiRoute(meta, inSchema)
    end toolMulti

    /** Registers a fixed-URI resource route descriptor.
      *
      * Call `.handler(f)` on the returned value to bind a handler closure.
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
    ): ResourceRoute =
        val meta = ResourceMeta(
            uri = uri,
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            mimeType = mimeType,
            annotations = resourceAnnotationsMaybe(annotations)
        )
        ResourceRoute(meta, subscribe)
    end resource

    /** Registers a URI-template resource route descriptor.
      *
      * Call `.handler(f)` on the returned value to bind a handler closure.
      */
    def resourceTemplate(
        uriTemplate: McpResourceUriTemplate,
        name: String,
        description: String = "",
        mimeType: Maybe[McpMimeType] = Absent,
        annotations: ResourceAnnotations = ResourceAnnotations.noop
    ): ResourceTemplateRoute =
        val meta = ResourceTemplateMeta(
            uriTemplate = uriTemplate,
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            mimeType = mimeType,
            annotations = resourceAnnotationsMaybe(annotations)
        )
        ResourceTemplateRoute(meta)
    end resourceTemplate

    /** Registers a prompt route descriptor.
      *
      * Call `.handler(f)` on the returned value to bind a handler closure receiving the typed
      * `Map[String, String]` argument map.
      */
    def prompt(
        name: String,
        description: String = "",
        arguments: Chunk[PromptArgument] = Chunk.empty
    ): PromptRoute =
        val meta =
            PromptMeta(name = name, description = if description.isEmpty then Absent else Present(description), arguments = arguments)
        PromptRoute(meta)
    end prompt

    /** Registers a completion handler descriptor for a prompt or resource. INV-026.
      *
      * Call `.handler(f)` for a 1-arg handler `(arg) => ...`, or `.handlerWith(f)` for the full
      * 3-arg `(ref, arg, contextOpt) => ...` shape that needs the previously-filled argument
      * values per §3.17.
      */
    def completion(ref: CompletionRef): CompletionRoute =
        CompletionRoute(ref)

    /** Registers a custom method route descriptor.
      *
      * Call `.handler[Out](f)` on the returned value. The compiler infers `Out` from the handler's
      * return type via clause interleaving.
      */
    def custom[In](method: String)(using inSchema: Schema[In]): CustomRoute[In] =
        CustomRoute(method, inSchema)

    // --- Concrete route-descriptor types ---

    /** Single-content tool route descriptor. */
    final class ToolRoute[In] private[kyo] (
        val toolMeta: ToolMeta,
        private[kyo] val inSchema: Schema[In]
    ) extends McpRoute[In]:
        val name: String = toolMeta.name
        val kind: Kind   = Kind.Tool

        /** Binds a handler closure returning a single `Out <: McpContent` leaf. The engine wraps
          * the result in a `ToolCallResult` with `content = Chunk(out)`.
          */
        inline def handler[Out <: McpContent](
            f: In => Out < (Async & Abort[McpException | JsonRpcResponse.Halt])
        )(using outSchema: Schema[Out], frame: Frame): McpHandler[In, Out, McpException] =
            McpHandler.makeTool(this, outSchema, f)
    end ToolRoute

    /** Multi-content tool route descriptor. */
    final class ToolMultiRoute[In] private[kyo] (
        val toolMeta: ToolMeta,
        private[kyo] val inSchema: Schema[In]
    ) extends McpRoute[In]:
        val name: String = toolMeta.name
        val kind: Kind   = Kind.Tool

        /** Binds a handler closure returning a full `ToolCallResult`. */
        inline def handler(
            f: In => ToolCallResult < (Async & Abort[McpException | JsonRpcResponse.Halt])
        )(using frame: Frame): McpHandler[In, ToolCallResult, McpException] =
            McpHandler.makeToolMulti(this, f)
    end ToolMultiRoute

    /** Fixed-URI resource route descriptor. */
    final class ResourceRoute private[kyo] (
        val resourceMeta: ResourceMeta,
        val subscribable: Boolean
    ) extends McpRoute[McpResourceUri]:
        val name: String = resourceMeta.name
        val kind: Kind   = Kind.Resource

        /** Binds a handler closure that takes the resolved URI and returns the resource contents. */
        inline def handler(
            f: McpResourceUri => Chunk[McpResourceContents] < (Async & Abort[McpException | JsonRpcResponse.Halt])
        )(using frame: Frame): McpHandler[McpResourceUri, Chunk[McpResourceContents], McpException] =
            McpHandler.makeResource(this, f)
    end ResourceRoute

    /** URI-template resource route descriptor. */
    final class ResourceTemplateRoute private[kyo] (
        val resourceTemplateMeta: ResourceTemplateMeta
    ) extends McpRoute[McpResourceUri]:
        val name: String = resourceTemplateMeta.name
        val kind: Kind   = Kind.ResourceTemplate

        inline def handler(
            f: McpResourceUri => Chunk[McpResourceContents] < (Async & Abort[McpException | JsonRpcResponse.Halt])
        )(using frame: Frame): McpHandler[McpResourceUri, Chunk[McpResourceContents], McpException] =
            McpHandler.makeResourceTemplate(this, f)
    end ResourceTemplateRoute

    /** Prompt route descriptor. */
    final class PromptRoute private[kyo] (
        val promptMeta: PromptMeta
    ) extends McpRoute[Map[String, String]]:
        val name: String = promptMeta.name
        val kind: Kind   = Kind.Prompt

        inline def handler(
            f: Map[String, String] => PromptGetResult < (Async & Abort[McpException | JsonRpcResponse.Halt])
        )(using frame: Frame): McpHandler[Map[String, String], PromptGetResult, McpException] =
            McpHandler.makePrompt(this, f)
    end PromptRoute

    /** Completion route descriptor. */
    final class CompletionRoute private[kyo] (
        val ref: CompletionRef
    ) extends McpRoute[(CompletionRef, CompletionArg)]:
        val name: String = ref match
            case CompletionRef.Prompt(n)   => s"completion/prompt/$n"
            case CompletionRef.Resource(u) => s"completion/resource/${u.asString}"
        val kind: Kind = Kind.Custom

        /** Binds a 1-arg handler receiving only the `CompletionArg`. The `ref` and optional
          * `Context` are discarded.
          */
        inline def handler(
            f: CompletionArg => CompletionResult < (Async & Abort[McpException | JsonRpcResponse.Halt])
        )(using frame: Frame): McpHandler[(CompletionRef, CompletionArg), CompletionResult, McpException] =
            McpHandler.makeCompletion(this, (_, arg, _) => f(arg))

        /** Binds the full 3-arg handler receiving `(ref, arg, contextOpt)` per §3.17. */
        inline def handlerWith(
            f: (
                CompletionRef,
                CompletionArg,
                Maybe[CompletionArg.Context]
            ) => CompletionResult < (Async & Abort[McpException | JsonRpcResponse.Halt])
        )(using frame: Frame): McpHandler[(CompletionRef, CompletionArg), CompletionResult, McpException] =
            McpHandler.makeCompletion(this, f)
    end CompletionRoute

    /** Custom method route descriptor. */
    final class CustomRoute[In] private[kyo] (
        val method: String,
        private[kyo] val inSchema: Schema[In]
    ) extends McpRoute[In]:
        val name: String = method
        val kind: Kind   = Kind.Custom

        inline def handler[Out](
            f: In => Out < (Async & Abort[McpException | JsonRpcResponse.Halt])
        )(using outSchema: Schema[Out], frame: Frame): McpHandler[In, Out, McpException] =
            McpHandler.makeCustom(this, outSchema, f)
    end CustomRoute

end McpRoute
