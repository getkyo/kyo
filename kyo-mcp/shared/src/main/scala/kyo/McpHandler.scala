package kyo

/** Unified route + handler for an MCP endpoint.
  *
  * An `McpHandler[In, Out, +E]` pairs the declarative metadata (name, schemas, capability hints,
  * annotations) for a single MCP endpoint with the implementation closure the engine invokes on
  * each inbound request. The engine lifts each value to a `JsonRpcRoute` at `McpServer.init` time,
  * wrapping the handler closure in `Mcp.local.let` so route bodies can reach the per-request
  * context through the `Mcp.*` accessors.
  *
  * Construct values via the companion factories:
  *
  *   - [[tool]] / [[toolMulti]] ; `tools/call` endpoints (single-content vs multi-content)
  *   - [[resource]] ; fixed-URI `resources/read` endpoint
  *   - [[resourceTemplate]] ; URI-template `resources/read` endpoint
  *   - [[prompt]] ; `prompts/get` endpoint
  *   - [[completion]] / [[completionWith]] ; `completion/complete` endpoint (1-arg / 3-arg)
  *   - [[custom]] ; arbitrary JSON-RPC method
  *
  * Each factory takes the handler closure directly. `[In]` is annotated at the call site;
  * `[Out]` is inferred from the handler's return type via clause interleaving so callers only
  * annotate `[In]`.
  *
  * Domain-error mappings are added with `.error[E2](code, message)`, mirroring `JsonRpcRoute.error`.
  *
  * @tparam In  the request parameter type
  * @tparam Out the response payload type
  * @tparam E   the union of user-registered domain error types
  */
sealed trait McpHandler[In, Out, +E]:
    /** The endpoint's wire-level name (the tool name, the resource URI, the prompt name, etc.). */
    def name: String

    /** Engine dispatch flavor for this handler. */
    private[kyo] def kind: McpHandler.Kind

    /** Per-handler error mappings registered via `.error[E2]`. */
    private[kyo] def errorMappings: Chunk[McpHandler.ErrorMapping[?]]

    /** Adds a typed-error mapping. When the handler aborts with a value of type `E2`, the engine
      * emits a JSON-RPC error with the supplied `code` and `message`. Mirrors `JsonRpcRoute.error[E2]`.
      */
    def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): McpHandler[In, Out, E | E2]
end McpHandler

object McpHandler:

    // --- Internal types -------------------------------------------------------

    /** Engine dispatch flavor for a handler. */
    private[kyo] enum Kind derives CanEqual:
        case Tool, Resource, ResourceTemplate, Prompt, Custom

    /** Marker for handlers that register a logging hook. Used by [[kyo.internal.mcp.McpCatalog]]
      * to auto-derive the `logging` server capability.
      */
    sealed private[kyo] trait LoggingHook

    /** A single `.error[E2]` registration: the type tag, the schema, and the wire `code`/`message`.
      * Aliased to `JsonRpcRoute.ErrorMapping` so the engine can apply the mapping at the dispatch
      * boundary of MCP indirection routes (`tools/call`, `resources/read`, ...) without converting.
      */
    private[kyo] type ErrorMapping[E] = JsonRpcRoute.ErrorMapping[E]

    // --- ResourceContents -----------------------------------------------------

    /** Sealed union of MCP resource content types returned from `resources/read`.
      *
      * Both leaves carry a typed `uri: McpResourceUri` field per INV-022 / Audit-A2.
      * `mimeType` is the opaque `McpMimeType` so all media-type-carrying surface stays typed.
      * Use the companion factory methods `text` and `blob` to construct values.
      *
      * The `given Schema[ResourceContents]` hand-rolls a tagged-union schema discriminating on
      * `"type"` in `kyo/internal/McpContentSchema.scala`.
      */
    sealed trait ResourceContents derives CanEqual:
        def uri: McpResourceUri
        def mimeType: Maybe[McpMimeType]

    object ResourceContents:

        /** Text resource content. */
        final case class Text(uri: McpResourceUri, mimeType: Maybe[McpMimeType], text: String) extends ResourceContents

        /** Binary blob resource content encoded as base-64. */
        final case class Blob(uri: McpResourceUri, mimeType: Maybe[McpMimeType], blob: String) extends ResourceContents

        /** Constructs a text resource content value. */
        def text(uri: McpResourceUri, text: String, mimeType: Maybe[McpMimeType] = Absent): ResourceContents =
            Text(uri, mimeType, text)

        /** Constructs a blob resource content value. */
        def blob(uri: McpResourceUri, blob: String, mimeType: Maybe[McpMimeType] = Absent): ResourceContents =
            Blob(uri, mimeType, blob)

        // Hand-rolled tagged-union schema. Implementation in kyo/internal/McpContentSchema.scala.
        // Wire discriminator key: "type"; tags: "text" | "blob" (INV-006).
        given Schema[ResourceContents] = internal.McpContentSchema.resourceContentsSchema

    end ResourceContents

    // --- Tool -----------------------------------------------------------------

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

    /** The outcome of a `tools/call` request.
      *
      * `structuredContent` is an INV-021 allowlist pass-through: the MCP spec defines
      * `structuredContent` as an open JSON object for typed tool output (Audit-A9).
      * `meta` is an INV-021 allowlist pass-through for the MCP spec `_meta` advisory field (§3.7).
      */
    final case class ToolOutcome(
        content: Chunk[McpContent],
        isError: Boolean,
        // flow-allow: Structure carve-out per §11a / INV-021
        structuredContent: Maybe[Structure.Value],
        // flow-allow: Structure carve-out per §11a / INV-021
        meta: Maybe[Structure.Value] = Absent
    ) derives Schema, CanEqual

    // --- Resource -------------------------------------------------------------

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
      * INV-022: `uriTemplate` is typed `McpResourceUri.Template`, not raw `String`.
      */
    final case class ResourceTemplateMeta(
        uriTemplate: McpResourceUri.Template,
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
        audience: Maybe[Chunk[McpContent.Role]] = Absent,
        priority: Maybe[Double] = Absent,
        lastModified: Maybe[String] = Absent
    ) derives Schema, CanEqual

    object ResourceAnnotations:
        /** The empty resource annotations record used as the factory default. */
        val noop: ResourceAnnotations = ResourceAnnotations()
    end ResourceAnnotations

    // --- Prompt ---------------------------------------------------------------

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

    /** The outcome of a `prompts/get` request.
      *
      * `meta` is an INV-021 allowlist pass-through for the MCP spec `_meta` advisory field (§3.7).
      */
    final case class PromptOutcome(
        description: Maybe[String],
        messages: Chunk[PromptMessage],
        // flow-allow: Structure carve-out per §11a / INV-021
        meta: Maybe[Structure.Value] = Absent
    ) derives Schema, CanEqual

    /** A single message in a prompt outcome. */
    final case class PromptMessage(role: McpContent.Role, content: McpContent) derives Schema, CanEqual

    // --- Completion -----------------------------------------------------------

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

    /** The outcome of a `completion/complete` request. */
    final case class CompletionOutcome(
        values: Chunk[String],
        total: Maybe[Int],
        hasMore: Maybe[Boolean]
    ) derives Schema, CanEqual

    // --- Helpers --------------------------------------------------------------

    private inline def toolAnnotationsMaybe(a: ToolAnnotations): Maybe[ToolAnnotations] =
        if a == ToolAnnotations.noop then Absent else Present(a)
    private inline def resourceAnnotationsMaybe(a: ResourceAnnotations): Maybe[ResourceAnnotations] =
        if a == ResourceAnnotations.noop then Absent else Present(a)

    // --- Factories ------------------------------------------------------------

    /** Constructs a single-content tool handler.
      *
      * The handler returns a single `Out <: McpContent` leaf; the engine wraps it in a
      * [[ToolOutcome]] with `content = Chunk(out)`. INV-020 distinguishes this from
      * [[toolMulti]], which returns a full `ToolOutcome` directly.
      *
      * Call sites annotate `[In]` and the compiler infers `[Out]` from the handler's return
      * type via clause interleaving.
      */
    def tool[In](
        name: String,
        description: String = "",
        annotations: ToolAnnotations = ToolAnnotations.noop
    )(using
        inSchema: Schema[In]
    )[Out <: McpContent, E](
        handler: In => Out < (Async & Abort[McpException | JsonRpcResponse.Halt | E])
    )(using outSchema: Schema[Out], frame: Frame): McpHandler[In, Out, McpException | E] =
        val meta = ToolMeta(
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            inputSchema = Json.jsonSchema[In],
            outputSchema = Absent,
            annotations = toolAnnotationsMaybe(annotations)
        )
        new ToolHandler[In, Out, McpException | E](meta, inSchema, outSchema, handler, Chunk.empty)
    end tool

    /** Constructs a multi-content tool handler that returns a full [[ToolOutcome]] directly. INV-020. */
    def toolMulti[In](
        name: String,
        description: String = "",
        annotations: ToolAnnotations = ToolAnnotations.noop
    )[E](
        handler: In => ToolOutcome < (Async & Abort[McpException | JsonRpcResponse.Halt | E])
    )(using inSchema: Schema[In], frame: Frame): McpHandler[In, ToolOutcome, McpException | E] =
        val meta = ToolMeta(
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            inputSchema = Json.jsonSchema[In],
            outputSchema = Absent,
            annotations = toolAnnotationsMaybe(annotations)
        )
        new ToolMultiHandler[In, McpException | E](meta, inSchema, handler, Chunk.empty)
    end toolMulti

    /** Constructs a fixed-URI resource handler.
      *
      * The handler is a by-name effectful value because the URI is fully known at registration
      * time: the engine only dispatches `resources/read` to this handler when the inbound URI
      * equals the registered `uri`. The closure has no input parameter; reference the captured
      * `uri` val from the surrounding scope if the body needs it in the produced `ResourceContents`.
      * For URI-template resources where the URI is dynamic per request, use [[resourceTemplate]].
      *
      * @param subscribe when `true`, this resource opts into the subscription protocol (§3.4 / Q9).
      *                  The server advertises `resources.subscribe = true` when any resource handler
      *                  sets this flag. Clients may then call `subscribeResource` / `unsubscribeResource`.
      */
    def resource(
        uri: McpResourceUri,
        name: String,
        description: String = "",
        mimeType: Maybe[McpMimeType] = Absent,
        annotations: ResourceAnnotations = ResourceAnnotations.noop,
        subscribe: Boolean = false
    )[E](
        handler: => Chunk[ResourceContents] < (Async & Abort[McpException | JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpHandler[McpResourceUri, Chunk[ResourceContents], McpException | E] =
        val meta = ResourceMeta(
            uri = uri,
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            mimeType = mimeType,
            annotations = resourceAnnotationsMaybe(annotations)
        )
        new ResourceHandler[McpException | E](meta, subscribe, () => handler, Chunk.empty)
    end resource

    /** Constructs a URI-template resource handler. */
    def resourceTemplate(
        uriTemplate: McpResourceUri.Template,
        name: String,
        description: String = "",
        mimeType: Maybe[McpMimeType] = Absent,
        annotations: ResourceAnnotations = ResourceAnnotations.noop
    )[E](
        handler: McpResourceUri => Chunk[ResourceContents] < (Async & Abort[McpException | JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpHandler[McpResourceUri, Chunk[ResourceContents], McpException | E] =
        val meta = ResourceTemplateMeta(
            uriTemplate = uriTemplate,
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            mimeType = mimeType,
            annotations = resourceAnnotationsMaybe(annotations)
        )
        new ResourceTemplateHandler[McpException | E](meta, handler, Chunk.empty)
    end resourceTemplate

    /** Constructs a prompt handler. */
    def prompt(
        name: String,
        description: String = "",
        arguments: Chunk[PromptArgument] = Chunk.empty
    )[E](
        handler: Map[String, String] => PromptOutcome < (Async & Abort[McpException | JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpHandler[Map[String, String], PromptOutcome, McpException | E] =
        val meta = PromptMeta(
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            arguments = arguments
        )
        new PromptHandler[McpException | E](meta, handler, Chunk.empty)
    end prompt

    /** Constructs a 1-arg completion handler (just the [[CompletionArg]]). The previously-filled
      * context is discarded. Use [[completionWith]] when the handler also needs the optional
      * `Context` carrying values the user has already filled for other arguments of the same
      * prompt (per §3.17).
      */
    def completion(ref: CompletionRef)[E](
        handler: CompletionArg => CompletionOutcome < (Async & Abort[McpException | JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpHandler[(CompletionRef, CompletionArg), CompletionOutcome, McpException | E] =
        new CompletionHandler[McpException | E](ref, (arg, _) => handler(arg), Chunk.empty)

    /** Constructs a 2-arg completion handler receiving `(arg, contextOpt)`. The `ref` argument
      * from the wire is omitted from the closure because it is always equal to the registered
      * `ref`: the engine dispatches `completion/complete` to this handler only when the inbound
      * ref matches.
      */
    def completionWith(ref: CompletionRef)[E](
        handler: (
            CompletionArg,
            Maybe[CompletionArg.Context]
        ) => CompletionOutcome < (Async & Abort[McpException | JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpHandler[(CompletionRef, CompletionArg), CompletionOutcome, McpException | E] =
        new CompletionHandler[McpException | E](ref, handler, Chunk.empty)

    /** Constructs an arbitrary JSON-RPC method handler.
      *
      * Call sites annotate `[In]` and the compiler infers `[Out]` from the handler's return type
      * via clause interleaving.
      */
    def custom[In](method: String)(using
        inSchema: Schema[In]
    )[Out, E](
        handler: In => Out < (Async & Abort[McpException | JsonRpcResponse.Halt | E])
    )(using outSchema: Schema[Out], frame: Frame): McpHandler[In, Out, McpException | E] =
        new CustomHandler[In, Out, McpException | E](method, inSchema, outSchema, handler, Chunk.empty)

    // --- Concrete handler carriers (internal) ---------------------------------

    final private[kyo] class ToolHandler[In, Out <: McpContent, +E] private[kyo] (
        val toolMeta: ToolMeta,
        val inSchema: Schema[In],
        val outSchema: Schema[Out],
        val toolHandler: In => Out < (Async & Abort[McpException | JsonRpcResponse.Halt | E]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[In, Out, E]:
        def name: String = toolMeta.name
        def kind: Kind   = Kind.Tool

        def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): McpHandler[In, Out, E | E2] =
            new ToolHandler[In, Out, E | E2](
                toolMeta,
                inSchema,
                outSchema,
                toolHandler,
                errorMappings.append(new ErrorMapping[E2](code, message))
            )
    end ToolHandler

    final private[kyo] class ToolMultiHandler[In, +E] private[kyo] (
        val toolMeta: ToolMeta,
        val inSchema: Schema[In],
        val toolHandler: In => ToolOutcome < (Async & Abort[McpException | JsonRpcResponse.Halt | E]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[In, ToolOutcome, E]:
        def name: String = toolMeta.name
        def kind: Kind   = Kind.Tool

        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpHandler[In, ToolOutcome, E | E2] =
            new ToolMultiHandler[In, E | E2](toolMeta, inSchema, toolHandler, errorMappings.append(new ErrorMapping[E2](code, message)))
    end ToolMultiHandler

    final private[kyo] class ResourceHandler[+E] private[kyo] (
        val resourceMeta: ResourceMeta,
        val subscribable: Boolean,
        val resourceHandler: () => Chunk[ResourceContents] < (Async & Abort[McpException | JsonRpcResponse.Halt | E]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[McpResourceUri, Chunk[ResourceContents], E]:
        def name: String = resourceMeta.name
        def kind: Kind   = Kind.Resource

        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpHandler[McpResourceUri, Chunk[ResourceContents], E | E2] =
            new ResourceHandler[E | E2](
                resourceMeta,
                subscribable,
                resourceHandler,
                errorMappings.append(new ErrorMapping[E2](code, message))
            )
    end ResourceHandler

    final private[kyo] class ResourceTemplateHandler[+E] private[kyo] (
        val resourceTemplateMeta: ResourceTemplateMeta,
        val resourceTemplateHandler: McpResourceUri => Chunk[
            ResourceContents
        ] < (Async & Abort[McpException | JsonRpcResponse.Halt | E]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[McpResourceUri, Chunk[ResourceContents], E]:
        def name: String = resourceTemplateMeta.name
        def kind: Kind   = Kind.ResourceTemplate

        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpHandler[McpResourceUri, Chunk[ResourceContents], E | E2] =
            new ResourceTemplateHandler[E | E2](
                resourceTemplateMeta,
                resourceTemplateHandler,
                errorMappings.append(new ErrorMapping[E2](code, message))
            )
    end ResourceTemplateHandler

    final private[kyo] class PromptHandler[+E] private[kyo] (
        val promptMeta: PromptMeta,
        val promptHandler: Map[String, String] => PromptOutcome < (Async & Abort[McpException | JsonRpcResponse.Halt | E]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[Map[String, String], PromptOutcome, E]:
        def name: String = promptMeta.name
        def kind: Kind   = Kind.Prompt

        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpHandler[Map[String, String], PromptOutcome, E | E2] =
            new PromptHandler[E | E2](promptMeta, promptHandler, errorMappings.append(new ErrorMapping[E2](code, message)))
    end PromptHandler

    final private[kyo] class CompletionHandler[+E] private[kyo] (
        val ref: CompletionRef,
        val completionHandler: (
            CompletionArg,
            Maybe[CompletionArg.Context]
        ) => CompletionOutcome < (Async & Abort[McpException | JsonRpcResponse.Halt | E]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[(CompletionRef, CompletionArg), CompletionOutcome, E]:
        def name: String = ref match
            case CompletionRef.Prompt(n)   => s"completion/prompt/$n"
            case CompletionRef.Resource(u) => s"completion/resource/${u.asString}"
        def kind: Kind = Kind.Custom

        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpHandler[(CompletionRef, CompletionArg), CompletionOutcome, E | E2] =
            new CompletionHandler[E | E2](ref, completionHandler, errorMappings.append(new ErrorMapping[E2](code, message)))
    end CompletionHandler

    final private[kyo] class CustomHandler[In, Out, +E] private[kyo] (
        val method: String,
        val inSchema: Schema[In],
        val outSchema: Schema[Out],
        val customHandler: In => Out < (Async & Abort[McpException | JsonRpcResponse.Halt | E]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[In, Out, E]:
        def name: String = method
        def kind: Kind   = Kind.Custom

        def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): McpHandler[In, Out, E | E2] =
            new CustomHandler[In, Out, E | E2](
                method,
                inSchema,
                outSchema,
                customHandler,
                errorMappings.append(new ErrorMapping[E2](code, message))
            )
    end CustomHandler

end McpHandler
