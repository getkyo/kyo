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
  *   - [[tool]] / [[toolRaw]] ; `tools/call` endpoints (structured-typed vs raw `ToolOutcome`)
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

    /** Adds a typed-error mapping reading the wire `code`/`message` from an [[McpHandler.McpErrorCode]]
      * instance. An author code in the framework-reserved range `-32000..-32003` is rejected when
      * the handler is registered.
      */
    def error[E2](using McpHandler.McpErrorCode[E2], Schema[E2], ConcreteTag[E2]): McpHandler[In, Out, E | E2]
end McpHandler

object McpHandler:

    // --- Internal types -------------------------------------------------------

    /** Engine dispatch flavor for a handler. */
    private[kyo] enum Kind derives CanEqual:
        case Tool, Resource, ResourceTemplate, Prompt, Custom

    /** The direction a handler is dispatched in.
      *
      * A server-side handler is `ServerHandled`; a client-side reverse-direction handler
      * (`McpClientHandler`) is `ClientHandled`. The carrier types are distinct, so a
      * wrong-direction registration does not type-check; `Direction` is the explicit tag the
      * engine reads to route.
      */
    enum Direction derives CanEqual:
        case ServerHandled, ClientHandled

    /** Type-class supplying the wire code and message for a user error type `E`.
      *
      * Used by the `.error[E2]` type-class overload so author codes are read from a single
      * instance rather than restated per call. Codes in the framework-reserved range
      * `-32000..-32003` are rejected when the handler is registered.
      */
    trait McpErrorCode[E]:
        def code: Int
        def message: String

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

    /** A resource body returned by a `resource` / `resourceTemplate` handler.
      *
      * Carries NO `uri`: the engine stamps the registered (fixed) or matched (template) URI.
      * `mimeType` is optional and defaults to the registered one when `Absent`. This makes a
      * body whose URI disagrees with the registration unrepresentable. Distinct from
      * [[ResourceContents]] (the client read type, which carries a URI).
      */
    sealed trait ResourceBody derives CanEqual:
        def mimeType: Maybe[McpMimeType]

    object ResourceBody:
        final case class Text(mimeType: Maybe[McpMimeType], text: String) extends ResourceBody
        final case class Blob(mimeType: Maybe[McpMimeType], blob: String) extends ResourceBody

        /** A text resource body; the engine stamps the URI. */
        def text(content: String, mime: Maybe[McpMimeType] = Absent): ResourceBody = Text(mime, content)

        /** A base-64 blob resource body; the engine stamps the URI. */
        def blob(content: String, mime: Maybe[McpMimeType] = Absent): ResourceBody = Blob(mime, content)
    end ResourceBody

    /** Pre-extracted template bindings handed to a `resourceTemplate` handler.
      *
      * The engine matched the inbound URI to route it, so it hands the author the matched
      * `uri` and the extracted `bindings`. `requireVariable` aborts a typed error on a missing
      * required binding (no `getOrElse("")`).
      */
    final class ResourceMatch private[kyo] (val uri: McpResourceUri, val bindings: Map[String, String]):
        def variable(name: String): Maybe[String] = Maybe.fromOption(bindings.get(name))
        def requireVariable(name: String)(using Frame): String < Abort[McpInvalidArgumentException] =
            bindings.get(name) match
                case Some(v) => v
                case None    => Abort.fail(McpInvalidArgumentException("resources/read", name, "required template variable absent"))
    end ResourceMatch

    /** Sealed union of MCP resource content types returned from `resources/read`.
      *
      * Both leaves carry a typed `uri: McpResourceUri` field. `mimeType` is the opaque
      * `McpMimeType` so all media-type-carrying surface stays typed. Use the companion factory
      * methods `text` and `blob` to construct values.
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
        // Wire discriminator key: "type"; tags: "text" | "blob".
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
      * `ToolAnnotations.empty` is the empty record used as the default parameter for every tool
      * factory; the factory translates `.empty` into `Absent` on the wire so the field is omitted.
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
        val empty: ToolAnnotations = ToolAnnotations()
    end ToolAnnotations

    /** The outcome of a `tools/call` request.
      *
      * `structuredContent` carries typed tool output: the MCP spec defines it as an open JSON
      * object, so the field is surfaced as `Maybe[Structure.Value]`. `meta` carries the optional
      * `_meta` advisory field from the MCP spec (§3.7); same open-object treatment applies.
      */
    final case class ToolOutcome(
        content: Chunk[McpContent],
        isError: Boolean,
        structuredContent: Maybe[Structure.Value],
        meta: Maybe[Structure.Value] = Absent
    ) derives Schema, CanEqual

    object ToolOutcome:
        /** A successful tool result: `isError = false`, no structured content. Blank text leaves
          * are dropped so an empty or whitespace-only content block never reaches the wire.
          */
        def ok(content: McpContent, more: McpContent*): ToolOutcome =
            ToolOutcome(this.content(content +: more*), isError = false, structuredContent = Absent)

        /** A model-visible failure: `isError = true`, the message as the first text leaf.
          *
          * Use this for failures the model should see and may retry. For protocol faults the
          * model must not see, abort an `McpException` (or `.error[E2]`) instead.
          */
        def error(message: String, more: McpContent*): ToolOutcome =
            ToolOutcome(this.content(McpContent.text(message) +: more*), isError = true, structuredContent = Absent)

        /** A success carrying a typed structured payload (encoded via `Structure.encode`) plus
          * optional content leaves. Blank text leaves are dropped through `content`.
          */
        def ok[A](structured: A, content: McpContent*)(using Schema[A], Frame): ToolOutcome =
            ToolOutcome(this.content(content*), isError = false, structuredContent = Present(Structure.encode(structured)), meta = Absent)

        /** A success carrying explicit structured content and/or `_meta`. Blank text leaves are
          * dropped through `content`.
          */
        def okWith(
            content: Chunk[McpContent] = Chunk.empty,
            structuredContent: Maybe[Structure.Value] = Absent,
            meta: Maybe[Structure.Value] = Absent
        ): ToolOutcome =
            ToolOutcome(this.content(content*), isError = false, structuredContent, meta)

        /** Empty-dropping smart constructor for a content chunk: a blank (empty or
          * whitespace-only) `McpContent.Text` leaf is dropped so handlers cannot emit blank
          * text on the wire; non-text leaves pass through unchanged.
          */
        def content(items: McpContent*): Chunk[McpContent] =
            Chunk.from(items.iterator.filterNot {
                case t: McpContent.Text => t.text.trim.isEmpty
                case _                  => false
            })

        extension (self: ToolOutcome)
            /** Decodes `structuredContent` to `M`, or `Absent` when no structured content is present.
              * A present-but-non-conforming payload aborts `McpDecodeException`.
              */
            def structuredContentAs[M](using Schema[M], Frame): Maybe[M] < Abort[McpDecodeException] =
                self.structuredContent match
                    case Absent => Maybe.empty
                    case Present(sv) =>
                        Structure.decode[M](sv) match
                            case Result.Success(m) => Present(m)
                            case Result.Failure(e) => Abort.fail(McpDecodeException("structuredContent", e.getMessage, Present(e)))
                            case Result.Panic(t)   => Abort.panic(t)

            /** Decodes `meta` to `M`, or `Absent` when no `_meta` is present. */
            def metaAs[M](using Schema[M], Frame): Maybe[M] < Abort[McpDecodeException] =
                self.meta match
                    case Absent => Maybe.empty
                    case Present(sv) =>
                        Structure.decode[M](sv) match
                            case Result.Success(m) => Present(m)
                            case Result.Failure(e) => Abort.fail(McpDecodeException("meta", e.getMessage, Present(e)))
                            case Result.Panic(t)   => Abort.panic(t)
        end extension
    end ToolOutcome

    // --- Resource -------------------------------------------------------------

    /** Metadata returned by `McpClient.listResources`. */
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

    /** Metadata returned by `McpClient.listResourceTemplates`. */
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
      * `ResourceAnnotations.empty` is the empty record used as the default parameter for every
      * resource and resource-template factory; the factory translates `.empty` into `Absent` on
      * the wire so the field is omitted.
      */
    final case class ResourceAnnotations(
        audience: Maybe[Chunk[McpContent.Role]] = Absent,
        priority: Maybe[Double] = Absent,
        lastModified: Maybe[String] = Absent
    ) derives Schema, CanEqual

    object ResourceAnnotations:
        /** The empty resource annotations record used as the factory default. */
        val empty: ResourceAnnotations = ResourceAnnotations()
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
      * `meta` carries the optional `_meta` advisory field from the MCP spec (§3.7); the MCP spec
      * leaves it as an open JSON object, so the field is surfaced as `Maybe[Structure.Value]`.
      */
    final case class PromptOutcome(
        description: Maybe[String],
        messages: Chunk[PromptMessage],
        meta: Maybe[Structure.Value] = Absent
    ) derives Schema, CanEqual

    object PromptOutcome:
        /** Builds an outcome from messages; blank-text messages are dropped through
          * `PromptMessage.messages`.
          */
        def of(description: Maybe[String], messages: PromptMessage*): PromptOutcome =
            PromptOutcome(description, PromptMessage.messages(messages*))

        /** A one-user-text-message outcome shorthand. */
        def user(text: String, description: Maybe[String] = Absent): PromptOutcome =
            of(description, PromptMessage.user(text))
    end PromptOutcome

    /** A single message in a prompt outcome. */
    final case class PromptMessage(role: McpContent.Role, content: McpContent) derives Schema, CanEqual

    object PromptMessage:
        /** A user-role prompt message carrying the given content. */
        def user(content: McpContent): PromptMessage = PromptMessage(McpContent.Role.User, content)

        /** An assistant-role prompt message carrying the given content. */
        def assistant(content: McpContent): PromptMessage = PromptMessage(McpContent.Role.Assistant, content)

        /** A system-role prompt message carrying the given content. */
        def system(content: McpContent): PromptMessage = PromptMessage(McpContent.Role.System, content)

        /** A user-role text prompt message. */
        def user(text: String): PromptMessage = PromptMessage(McpContent.Role.User, McpContent.text(text))

        /** An assistant-role text prompt message. */
        def assistant(text: String): PromptMessage = PromptMessage(McpContent.Role.Assistant, McpContent.text(text))

        /** A system-role text prompt message. */
        def system(text: String): PromptMessage = PromptMessage(McpContent.Role.System, McpContent.text(text))

        /** Empty-dropping smart constructor for a prompt-message chunk: a message whose content
          * is a blank (empty or whitespace-only) `McpContent.Text` leaf is dropped so a handler
          * cannot emit a blank prompt message on the wire; non-text content passes through.
          */
        def messages(items: PromptMessage*): Chunk[PromptMessage] =
            Chunk.from(items.iterator.filterNot { m =>
                m.content match
                    case t: McpContent.Text => t.text.trim.isEmpty
                    case _                  => false
            })
    end PromptMessage

    // --- Completion -----------------------------------------------------------

    /** Identifies the target of a completion request.
      *
      * The on-wire discriminator key is `"type"` with values `"ref/prompt"` and `"ref/resource"`.
      * The hand-rolled Schema lives in `kyo/internal/McpCompletionRefSchema.scala`.
      */
    enum CompletionRef derives CanEqual:
        case Prompt(name: String)
        case Resource(uri: McpResourceUri)

    object CompletionRef:
        given Schema[CompletionRef] = internal.McpCompletionRefSchema.schema

    /** Named argument descriptor for a completion request. */
    final case class CompletionArg(name: String, value: String) derives Schema, CanEqual

    object CompletionArg:
        /** Additional context for a completion request, carrying previously filled argument values (§3.17). */
        final case class Context(arguments: Map[String, String]) derives Schema, CanEqual

        object Context:
            /** Builds a completion context from name/value argument pairs. */
            def of(pairs: (String, String)*): Context = Context(pairs.toMap)
    end CompletionArg

    /** The outcome of a `completion/complete` request. */
    final case class CompletionOutcome(
        values: Chunk[String],
        total: Maybe[Int] = Absent,
        hasMore: Maybe[Boolean] = Absent
    ) derives Schema, CanEqual

    object CompletionOutcome:
        /** Builds an outcome from a flat value list, with no `total`/`hasMore`. */
        def of(values: String*): CompletionOutcome = CompletionOutcome(Chunk.from(values))
    end CompletionOutcome

    // --- Helpers --------------------------------------------------------------

    private inline def toolAnnotationsMaybe(a: ToolAnnotations): Maybe[ToolAnnotations] =
        if a == ToolAnnotations.empty then Absent else Present(a)
    private inline def resourceAnnotationsMaybe(a: ResourceAnnotations): Maybe[ResourceAnnotations] =
        if a == ResourceAnnotations.empty then Absent else Present(a)

    // --- Factories ------------------------------------------------------------

    inline def tool[In](
        name: String,
        description: String = "",
        annotations: ToolAnnotations = ToolAnnotations.empty
    )(using
        inSchema: Schema[In]
    )[Out, E](
        handler: In => Out < (Async & Abort[JsonRpcResponse.Halt | E])
    )(using outSchema: Schema[Out], frame: Frame): McpHandler[In, Out, E] =
        // `inline` so `Json.jsonSchema[In]`/`Json.jsonSchema[Out]` expand at the call site where
        // `In`/`Out` are concrete; against an abstract type parameter they yield an empty Product.
        // The advertised `outputSchema` is derived from `Out`; the lift encodes the one returned
        // value into `structuredContent` and a text mirror, so the three coupled fields agree by
        // construction.
        val meta = ToolMeta(
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            inputSchema = Json.jsonSchema[In],
            outputSchema = Present(Json.jsonSchema[Out]),
            annotations = toolAnnotationsMaybe(annotations)
        )
        new ToolHandler[In, Out, E](meta, inSchema, outSchema, handler, Chunk.empty)
    end tool

    /** Constructs a tool handler returning a full [[ToolOutcome]] for total control
      * (multiple content leaves, a pure-content tool, in-band `isError`). The lower-level
      * escape; reach for [[tool]] when one returned value should drive the structured output.
      */
    inline def toolRaw[In](
        name: String,
        description: String = "",
        annotations: ToolAnnotations = ToolAnnotations.empty
    )[E](
        handler: In => ToolOutcome < (Async & Abort[JsonRpcResponse.Halt | E])
    )(using inSchema: Schema[In], frame: Frame): McpHandler[In, ToolOutcome, E] =
        // `inline` so `Json.jsonSchema[In]` resolves against the concrete `In` at the call site.
        val meta = ToolMeta(
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            inputSchema = Json.jsonSchema[In],
            outputSchema = Absent,
            annotations = toolAnnotationsMaybe(annotations)
        )
        new ToolMultiHandler[In, E](meta, inSchema, handler, Chunk.empty)
    end toolRaw

    /** Constructs a fixed-URI resource handler.
      *
      * The handler is a by-name effectful value because the URI is fully known at registration
      * time: the engine only dispatches `resources/read` to this handler when the inbound URI
      * equals the registered `uri`. The engine stamps the registered URI onto each [[ResourceBody]]
      * returned by the handler. For URI-template resources where the URI is dynamic per request,
      * use [[resourceTemplate]].
      *
      * @param subscribe when `true`, this resource opts into the subscription protocol (SS3.4).
      *                  The server advertises `resources.subscribe = true` when any resource handler
      *                  sets this flag. Clients may then call `subscribeResource` / `unsubscribeResource`.
      */
    def resource(
        uri: McpResourceUri,
        name: String,
        description: String = "",
        mimeType: Maybe[McpMimeType] = Absent,
        annotations: ResourceAnnotations = ResourceAnnotations.empty,
        subscribe: Boolean = false
    )[E](
        handler: => Chunk[ResourceBody] < (Async & Abort[JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpHandler[Unit, Chunk[ResourceBody], E] =
        val meta = ResourceMeta(
            uri = uri,
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            mimeType = mimeType,
            annotations = resourceAnnotationsMaybe(annotations)
        )
        new ResourceHandler[E](meta, subscribe, () => handler, Chunk.empty)
    end resource

    /** Constructs a URI-template resource handler. The closure receives a [[ResourceMatch]]
      * carrying the matched URI and pre-extracted template bindings.
      */
    def resourceTemplate(
        uriTemplate: McpResourceUri.Template,
        name: String,
        description: String = "",
        mimeType: Maybe[McpMimeType] = Absent,
        annotations: ResourceAnnotations = ResourceAnnotations.empty
    )[E](
        handler: ResourceMatch => Chunk[ResourceBody] < (Async & Abort[JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpHandler[ResourceMatch, Chunk[ResourceBody], E] =
        val meta = ResourceTemplateMeta(
            uriTemplate = uriTemplate,
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            mimeType = mimeType,
            annotations = resourceAnnotationsMaybe(annotations)
        )
        new ResourceTemplateHandler[E](meta, handler, Chunk.empty)
    end resourceTemplate

    /** Constructs a typed prompt handler.
      *
      * Derives the `PromptArgument` advertisement from `In`'s fields (a `Maybe` field is
      * `required = false`) and decodes the inbound `Map[String, String]` into `In`. A required
      * `In` field absent from the inbound map surfaces a typed decode failure, never a silent
      * `""`. The raw `Map[String, String]` form below is retained for per-argument metadata.
      */
    def prompt[In](
        name: String,
        description: String = ""
    )(using
        inSchema: Schema[In]
    )[E](
        handler: In => PromptOutcome < (Async & Abort[JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpHandler[In, PromptOutcome, E] =
        val meta = PromptMeta(
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            arguments = internal.mcp.McpPromptArguments.fromSchema[In]
        )
        new TypedPromptHandler[In, E](meta, inSchema, handler, Chunk.empty)
    end prompt

    /** Constructs a prompt handler over the raw inbound `Map[String, String]` (the escape
      * hatch for per-argument description/title that have no home on a bare `In` field).
      */
    def prompt(
        name: String,
        description: String,
        arguments: Chunk[PromptArgument]
    )[E](
        handler: Map[String, String] => PromptOutcome < (Async & Abort[JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpHandler[Map[String, String], PromptOutcome, E] =
        val meta = PromptMeta(
            name = name,
            description = if description.isEmpty then Absent else Present(description),
            arguments = arguments
        )
        new PromptHandler[E](meta, handler, Chunk.empty)
    end prompt

    /** Constructs a 1-arg completion handler (just the [[CompletionArg]]). The previously-filled
      * context is discarded. Use [[completionWith]] when the handler also needs the optional
      * `Context` carrying values the user has already filled for other arguments of the same
      * prompt (per SS3.17).
      */
    def completion(ref: CompletionRef)[E](
        handler: CompletionArg => CompletionOutcome < (Async & Abort[JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpHandler[CompletionArg, CompletionOutcome, E] =
        new CompletionHandler[E](ref, (arg, _) => handler(arg), Chunk.empty)

    /** Constructs a completion handler whose ref is read off a registered prompt handler value,
      * removing the third restatement of a prompt name.
      */
    def completion[In](promptHandler: McpHandler[In, PromptOutcome, ?])[E](
        handler: CompletionArg => CompletionOutcome < (Async & Abort[JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpHandler[CompletionArg, CompletionOutcome, E] =
        completion(CompletionRef.Prompt(promptHandler.name))[E](handler)

    /** Constructs a 2-arg completion handler receiving `(arg, contextOpt)`. The `ref` argument
      * from the wire is omitted from the closure because it is always equal to the registered
      * `ref`: the engine dispatches `completion/complete` to this handler only when the inbound
      * ref matches.
      */
    def completionWith(ref: CompletionRef)[E](
        handler: (
            CompletionArg,
            Maybe[CompletionArg.Context]
        ) => CompletionOutcome < (Async & Abort[JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpHandler[CompletionArg, CompletionOutcome, E] =
        new CompletionHandler[E](ref, handler, Chunk.empty)

    /** Constructs an arbitrary JSON-RPC method handler.
      *
      * Call sites annotate `[In]` and the compiler infers `[Out]` from the handler's return type
      * via clause interleaving.
      */
    def custom[In](method: String)(using
        inSchema: Schema[In]
    )[Out, E](
        handler: In => Out < (Async & Abort[JsonRpcResponse.Halt | E])
    )(using outSchema: Schema[Out], frame: Frame): McpHandler[In, Out, E] =
        new CustomHandler[In, Out, E](method, inSchema, outSchema, handler, Chunk.empty)

    // --- Concrete handler carriers (internal) ---------------------------------

    final private[kyo] class ToolHandler[In, Out, +E] @scala.annotation.publicInBinary private[kyo] (
        val toolMeta: ToolMeta,
        val inSchema: Schema[In],
        val outSchema: Schema[Out],
        val toolHandler: In => Out < (Async & Abort[JsonRpcResponse.Halt | E]),
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

        def error[E2](using ec: McpErrorCode[E2], schema: Schema[E2], tag: ConcreteTag[E2]): McpHandler[In, Out, E | E2] =
            error[E2](using schema, tag)(ec.code, ec.message)
    end ToolHandler

    final private[kyo] class ToolMultiHandler[In, +E] @scala.annotation.publicInBinary private[kyo] (
        val toolMeta: ToolMeta,
        val inSchema: Schema[In],
        val toolHandler: In => ToolOutcome < (Async & Abort[JsonRpcResponse.Halt | E]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[In, ToolOutcome, E]:
        def name: String = toolMeta.name
        def kind: Kind   = Kind.Tool

        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpHandler[In, ToolOutcome, E | E2] =
            new ToolMultiHandler[In, E | E2](toolMeta, inSchema, toolHandler, errorMappings.append(new ErrorMapping[E2](code, message)))

        def error[E2](using ec: McpErrorCode[E2], schema: Schema[E2], tag: ConcreteTag[E2]): McpHandler[In, ToolOutcome, E | E2] =
            error[E2](using schema, tag)(ec.code, ec.message)
    end ToolMultiHandler

    final private[kyo] class ResourceHandler[+E] private[kyo] (
        val resourceMeta: ResourceMeta,
        val subscribable: Boolean,
        val resourceHandler: () => Chunk[ResourceBody] < (Async & Abort[JsonRpcResponse.Halt | E]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[Unit, Chunk[ResourceBody], E]:
        def name: String = resourceMeta.name
        def kind: Kind   = Kind.Resource

        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpHandler[Unit, Chunk[ResourceBody], E | E2] =
            new ResourceHandler[E | E2](
                resourceMeta,
                subscribable,
                resourceHandler,
                errorMappings.append(new ErrorMapping[E2](code, message))
            )

        def error[E2](using ec: McpErrorCode[E2], schema: Schema[E2], tag: ConcreteTag[E2]): McpHandler[Unit, Chunk[ResourceBody], E | E2] =
            error[E2](using schema, tag)(ec.code, ec.message)
    end ResourceHandler

    final private[kyo] class ResourceTemplateHandler[+E] private[kyo] (
        val resourceTemplateMeta: ResourceTemplateMeta,
        val resourceTemplateHandler: ResourceMatch => Chunk[
            ResourceBody
        ] < (Async & Abort[JsonRpcResponse.Halt | E]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[ResourceMatch, Chunk[ResourceBody], E]:
        def name: String = resourceTemplateMeta.name
        def kind: Kind   = Kind.ResourceTemplate

        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpHandler[ResourceMatch, Chunk[ResourceBody], E | E2] =
            new ResourceTemplateHandler[E | E2](
                resourceTemplateMeta,
                resourceTemplateHandler,
                errorMappings.append(new ErrorMapping[E2](code, message))
            )

        def error[E2](using
            ec: McpErrorCode[E2],
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        ): McpHandler[ResourceMatch, Chunk[ResourceBody], E | E2] =
            error[E2](using schema, tag)(ec.code, ec.message)
    end ResourceTemplateHandler

    final private[kyo] class PromptHandler[+E] private[kyo] (
        val promptMeta: PromptMeta,
        val promptHandler: Map[String, String] => PromptOutcome < (Async & Abort[JsonRpcResponse.Halt | E]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[Map[String, String], PromptOutcome, E]:
        def name: String = promptMeta.name
        def kind: Kind   = Kind.Prompt

        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpHandler[Map[String, String], PromptOutcome, E | E2] =
            new PromptHandler[E | E2](promptMeta, promptHandler, errorMappings.append(new ErrorMapping[E2](code, message)))

        def error[E2](using
            ec: McpErrorCode[E2],
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        ): McpHandler[Map[String, String], PromptOutcome, E | E2] =
            error[E2](using schema, tag)(ec.code, ec.message)
    end PromptHandler

    final private[kyo] class TypedPromptHandler[In, +E] @scala.annotation.publicInBinary private[kyo] (
        val promptMeta: PromptMeta,
        val inSchema: Schema[In],
        val typedPromptHandler: In => PromptOutcome < (Async & Abort[JsonRpcResponse.Halt | E]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[In, PromptOutcome, E]:
        def name: String = promptMeta.name
        def kind: Kind   = Kind.Prompt

        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpHandler[In, PromptOutcome, E | E2] =
            new TypedPromptHandler[In, E | E2](
                promptMeta,
                inSchema,
                typedPromptHandler,
                errorMappings.append(new ErrorMapping[E2](code, message))
            )

        def error[E2](using ec: McpErrorCode[E2], schema: Schema[E2], tag: ConcreteTag[E2]): McpHandler[In, PromptOutcome, E | E2] =
            error[E2](using schema, tag)(ec.code, ec.message)
    end TypedPromptHandler

    final private[kyo] class CompletionHandler[+E] private[kyo] (
        val ref: CompletionRef,
        val completionHandler: (
            CompletionArg,
            Maybe[CompletionArg.Context]
        ) => CompletionOutcome < (Async & Abort[JsonRpcResponse.Halt | E]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[CompletionArg, CompletionOutcome, E]:
        def name: String = ref match
            case CompletionRef.Prompt(n)   => s"completion/prompt/$n"
            case CompletionRef.Resource(u) => s"completion/resource/${u.asString}"
        def kind: Kind = Kind.Custom

        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpHandler[CompletionArg, CompletionOutcome, E | E2] =
            new CompletionHandler[E | E2](ref, completionHandler, errorMappings.append(new ErrorMapping[E2](code, message)))

        def error[E2](using
            ec: McpErrorCode[E2],
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        ): McpHandler[CompletionArg, CompletionOutcome, E | E2] =
            error[E2](using schema, tag)(ec.code, ec.message)
    end CompletionHandler

    final private[kyo] class CustomHandler[In, Out, +E] private[kyo] (
        val method: String,
        val inSchema: Schema[In],
        val outSchema: Schema[Out],
        val customHandler: In => Out < (Async & Abort[JsonRpcResponse.Halt | E]),
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

        def error[E2](using ec: McpErrorCode[E2], schema: Schema[E2], tag: ConcreteTag[E2]): McpHandler[In, Out, E | E2] =
            error[E2](using schema, tag)(ec.code, ec.message)
    end CustomHandler

end McpHandler
