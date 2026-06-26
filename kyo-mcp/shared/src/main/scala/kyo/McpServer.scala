package kyo

/** Live MCP server handle managing one peer over a [[JsonRpcTransport]].
  *
  * Obtain via `McpServer.init` (Scope-managed) or `McpServer.initUnscoped` (manual close).
  * Mirrors `JsonRpcHandler` at kyo-jsonrpc/.../JsonRpcHandler.scala:30 and
  * `HttpServer` at kyo-http/.../HttpServer.scala:37.
  *
  * Reverse-direction request/response records (`SamplingRequest`, `SamplingResponse`,
  * `ElicitationRequest`, `ElicitationResponse`) and the `Root` record live inside this
  * companion because every consumer (`requestSampling`, `requestElicitation`, `requestRoots`)
  * is a method on `McpServer`. Nesting them here keeps the top-level `kyo.*` namespace small.
  *
  * @see [[McpServer.init]]
  * @see [[McpServer.initUnscoped]]
  */
opaque type McpServer = McpServer.Unsafe

object McpServer:

    /** Parameters for the `sampling/createMessage` reverse-direction request.
      *
      * The `metadata` field carries the spec-defined open `_meta` JSON object, surfaced as
      * `Maybe[Structure.Value]` rather than a typed shape.
      *
      * @param messages          the conversation turns to continue
      * @param modelPreferences  optional model selection hints and cost/speed/intelligence weights
      * @param systemPrompt      optional system prompt to include
      * @param includeContext    whether to include server/client context in the message
      * @param temperature       sampling temperature hint
      * @param maxTokens         maximum tokens for the sampled response
      * @param stopSequences     sequences that halt generation early
      * @param metadata          spec-defined open `_meta` field
      */
    final case class SamplingRequest(
        messages: Chunk[SamplingRequest.Message],
        maxTokens: Int,
        modelPreferences: Maybe[SamplingRequest.ModelPreferences] = Absent,
        systemPrompt: Maybe[String] = Absent,
        includeContext: Maybe[SamplingRequest.IncludeContext] = Absent,
        temperature: Maybe[Double] = Absent,
        stopSequences: Chunk[String] = Chunk.empty,
        metadata: Maybe[Structure.Value] = Absent
    ) derives Schema, CanEqual

    object SamplingRequest:

        /** A single message in the sampling conversation. */
        final case class Message(role: McpContent.Role, content: SamplingContent) derives Schema, CanEqual

        object Message:
            /** A user-role message carrying the given content. */
            def user(content: SamplingContent): Message = Message(McpContent.Role.User, content)

            /** An assistant-role message carrying the given content. */
            def assistant(content: SamplingContent): Message = Message(McpContent.Role.Assistant, content)

            /** A user-role text message. */
            def user(text: String): Message = Message(McpContent.Role.User, SamplingContent.text(text))

            /** An assistant-role text message. */
            def assistant(text: String): Message = Message(McpContent.Role.Assistant, SamplingContent.text(text))
        end Message

        /** Hints for model selection. */
        final case class ModelPreferences(
            hints: Chunk[ModelHint] = Chunk.empty,
            costPriority: Maybe[Double] = Absent,
            speedPriority: Maybe[Double] = Absent,
            intelligencePriority: Maybe[Double] = Absent
        ) derives Schema, CanEqual

        /** A model name hint for selection. */
        final case class ModelHint(name: Maybe[String] = Absent) derives Schema, CanEqual

        /** Controls how much context from the server or all servers is included. */
        enum IncludeContext derives CanEqual:
            case None, ThisServer, AllServers

        object IncludeContext:
            // Wire strings use camelCase. Total: an unknown context string decodes to Result.Failure.
            given Schema[IncludeContext] = internal.mcp.McpEnumSchema.closed[IncludeContext](
                "none"       -> IncludeContext.None,
                "thisServer" -> IncludeContext.ThisServer,
                "allServers" -> IncludeContext.AllServers
            )
        end IncludeContext

        /** A one-user-text-turn request: the dominant reverse-sampling shape. */
        def user(
            text: String,
            maxTokens: Int,
            modelPreferences: Maybe[ModelPreferences] = Absent,
            systemPrompt: Maybe[String] = Absent,
            temperature: Maybe[Double] = Absent
        ): SamplingRequest =
            SamplingRequest(Chunk(Message.user(text)), maxTokens, modelPreferences, systemPrompt, Absent, temperature)

        /** A multi-turn request built from explicit messages. */
        def of(maxTokens: Int)(messages: Message*): SamplingRequest =
            SamplingRequest(Chunk.from(messages), maxTokens)

    end SamplingRequest

    /** Content type for `sampling/createMessage` request messages.
      *
      * A subset of [[McpContent]] restricted to Text, Image, and Audio only.
      * EmbeddedResource and ResourceLink are excluded per MCP spec §3.10.
      * Use `toMcpContent` to convert to the broader [[McpContent]] type.
      */
    sealed trait SamplingContent derives CanEqual:
        def toMcpContent: McpContent

    object SamplingContent:
        /** Text content for a sampling message. */
        final case class Text(
            text: String,
            annotations: McpContent.Annotations = McpContent.Annotations.empty
        ) extends SamplingContent:
            def toMcpContent: McpContent = McpContent.Text(text, annotations)
        end Text

        /** Image content for a sampling message. */
        final case class Image(
            data: String,
            mimeType: McpMimeType,
            annotations: McpContent.Annotations = McpContent.Annotations.empty
        ) extends SamplingContent:
            def toMcpContent: McpContent = McpContent.Image(data, mimeType, annotations)
        end Image

        /** Audio content for a sampling message. */
        final case class Audio(
            data: String,
            mimeType: McpMimeType,
            annotations: McpContent.Annotations = McpContent.Annotations.empty
        ) extends SamplingContent:
            def toMcpContent: McpContent = McpContent.Audio(data, mimeType, annotations)
        end Audio

        /** Constructs a text sampling-content value. */
        def text(text: String, annotations: McpContent.Annotations = McpContent.Annotations.empty): SamplingContent =
            Text(text, annotations)

        /** Constructs an image sampling-content value. */
        def image(
            data: String,
            mimeType: McpMimeType,
            annotations: McpContent.Annotations = McpContent.Annotations.empty
        ): SamplingContent =
            Image(data, mimeType, annotations)

        /** Constructs an audio sampling-content value. */
        def audio(
            data: String,
            mimeType: McpMimeType,
            annotations: McpContent.Annotations = McpContent.Annotations.empty
        ): SamplingContent =
            Audio(data, mimeType, annotations)

        // Hand-rolled Schema using "type" discriminator for wire compatibility.
        given Schema[SamplingContent] = internal.McpSamplingContentSchema.schema

    end SamplingContent

    /** Result of the `sampling/createMessage` reverse-direction request.
      *
      * @param role       the role of the sampled content
      * @param content    the generated content
      * @param model      the model identifier that produced the response
      * @param stopReason the reason generation stopped, if known; typed per §3.5
      */
    final case class SamplingResponse(
        role: McpContent.Role,
        content: McpContent,
        model: Maybe[String] = Absent,
        stopReason: Maybe[SamplingResponse.StopReason] = Absent
    ) derives CanEqual

    object SamplingResponse:

        /** An assistant response (the only role a sampling response carries per MCP). `model` and
          * `stopReason` default to `Absent`.
          */
        def assistant(
            content: McpContent,
            model: Maybe[String] = Absent,
            stopReason: Maybe[StopReason] = Absent
        ): SamplingResponse =
            SamplingResponse(McpContent.Role.Assistant, content, model, stopReason)

        /** An assistant text-response shorthand. */
        def assistant(text: String): SamplingResponse =
            SamplingResponse(McpContent.Role.Assistant, McpContent.text(text), Absent, Absent)

        /** Stop reason for `sampling/createMessage` responses.
          *
          * A genuinely open spec field: known values have named constants, and an unknown wire
          * string round-trips losslessly through `asString` rather than collapsing to a known
          * value. Modeled as an opaque string with constants, mirroring `McpConfig.ProtocolVersion`.
          */
        opaque type StopReason = String

        object StopReason:
            val EndTurn: StopReason      = "endTurn"
            val StopSequence: StopReason = "stopSequence"
            val MaxTokens: StopReason    = "maxTokens"

            /** Wire decoder (engine-only): wraps any stop-reason string, known or not. */
            private[kyo] def fromWire(s: String): StopReason = s

            extension (r: StopReason)
                /** Returns the underlying wire string. */
                def asString: String = r

            given Schema[StopReason] = Schema.stringSchema.transform[StopReason](fromWire)(_.asString)

            given CanEqual[StopReason, StopReason] = CanEqual.derived
        end StopReason

        extension (r: SamplingResponse)
            /** The text content, or `Absent` when the response is not a text leaf. */
            def contentText: Maybe[String] = r.content match
                case McpContent.Text(t, _) => Present(t)
                case _                     => Absent

            /** The (data, mimeType) of an image response, or `Absent`. */
            def contentImage: Maybe[(String, McpMimeType)] = r.content match
                case McpContent.Image(d, m, _) => Present((d, m))
                case _                         => Absent

            /** The (data, mimeType) of an audio response, or `Absent`. */
            def contentAudio: Maybe[(String, McpMimeType)] = r.content match
                case McpContent.Audio(d, m, _) => Present((d, m))
                case _                         => Absent
        end extension

    end SamplingResponse

    // Schema for SamplingResponse is placed here (after SamplingResponse.StopReason.given is defined)
    // so the macro can resolve Schema[StopReason]. Placing it on the case class with `derives Schema`
    // would run before the companion object is in scope, causing the macro to fail to find Schema[StopReason].
    given Schema[SamplingResponse] = Schema.derived

    /** Parameters for the `elicitation/create` reverse-direction request.
      *
      * The server sends this to the client when it needs to collect additional information
      * from the end user. `requestedSchema` is a JSON Schema document describing the
      * expected response structure.
      *
      * @param message         human-readable message shown to the user
      * @param requestedSchema JSON Schema describing the expected response shape
      */
    final case class ElicitationRequest(
        message: String,
        requestedSchema: Json.JsonSchema
    ) derives Schema, CanEqual

    /** Result of the `elicitation/create` reverse-direction request.
      *
      * The MCP spec leaves the elicitation response payload as an open JSON object, so
      * `content` is surfaced as `Maybe[Structure.Value]`.
      *
      * @param action  whether the user accepted, declined, or cancelled the elicitation
      * @param content the user-supplied content when action is Accept
      */
    final case class ElicitationResponse(
        action: ElicitationResponse.Action,
        content: Maybe[Structure.Value] = Absent
    ) derives Schema, CanEqual

    object ElicitationResponse:

        /** Accept carrying a typed payload (engine-encoded via `Structure.encode`). */
        def accept[A](value: A)(using Schema[A], Frame): ElicitationResponse =
            ElicitationResponse(Action.Accept, Present(Structure.encode(value)))

        /** Accept carrying an already-built structure value. */
        def accept(content: Structure.Value): ElicitationResponse =
            ElicitationResponse(Action.Accept, Present(content))

        /** A decline response (no content). */
        val decline: ElicitationResponse = ElicitationResponse(Action.Decline, Absent)

        /** A cancel response (no content). */
        val cancel: ElicitationResponse = ElicitationResponse(Action.Cancel, Absent)

        /** The user's decision regarding the elicitation request. */
        enum Action derives CanEqual:
            case Accept, Decline, Cancel

        object Action:
            // Wire strings: "accept" | "decline" | "cancel". Total: an unknown action string
            // decodes to Result.Failure, never a thrown valueOf panic.
            given Schema[Action] = internal.mcp.McpEnumSchema.closed[Action](
                "accept"  -> Action.Accept,
                "decline" -> Action.Decline,
                "cancel"  -> Action.Cancel
            )
        end Action

    end ElicitationResponse

    /** A root entry returned by the client in response to a `roots/list` request.
      *
      * @param uri  the root URI
      * @param name optional human-readable name for the root
      */
    final case class Root(uri: McpResourceUri, name: Maybe[String] = Absent) derives Schema, CanEqual

    /** The typed result of a `requestElicitationAs[A]`: the user accepted (and the payload decoded
      * to `A`), declined, or cancelled.
      */
    enum ElicitationOutcome[+A] derives CanEqual:
        case Accept(value: A)
        case Decline
        case Cancel
    end ElicitationOutcome

    /** MCP log level enum with 8 severity levels.
      *
      * Wire strings are lowercase and match the Scala case name lowercase: `"debug"` | `"info"` |
      * `"notice"` | `"warning"` | `"error"` | `"critical"` | `"alert"` | `"emergency"`.
      * Do NOT add `Schema` to the `derives` clause.
      */
    enum LogLevel derives CanEqual:
        case Debug, Info, Notice, Warning, Error, Critical, Alert, Emergency

    object LogLevel:

        // Wire strings: "debug"|"info"|"notice"|"warning"|"error"|"critical"|"alert"|"emergency".
        // Total: an unknown level string decodes to Result.Failure, never a valueOf panic.
        given Schema[LogLevel] = internal.mcp.McpEnumSchema.closed[LogLevel](
            "debug"     -> LogLevel.Debug,
            "info"      -> LogLevel.Info,
            "notice"    -> LogLevel.Notice,
            "warning"   -> LogLevel.Warning,
            "error"     -> LogLevel.Error,
            "critical"  -> LogLevel.Critical,
            "alert"     -> LogLevel.Alert,
            "emergency" -> LogLevel.Emergency
        )

    end LogLevel

    extension (self: McpServer)

        /** Sends `sampling/createMessage` to the connected client. */
        def requestSampling(request: SamplingRequest)(using Frame): SamplingResponse < (Async & Abort[McpRequestSamplingFailure]) =
            Sync.Unsafe.defer(self.requestSampling(request).safe.get)

        /** Sends `roots/list` to the connected client. */
        def requestRoots(using Frame): Chunk[Root] < (Async & Abort[McpRequestRootsFailure]) =
            Sync.Unsafe.defer(self.requestRoots.safe.get)

        /** Sends `elicitation/create` to the connected client. */
        def requestElicitation(request: ElicitationRequest)(using
            Frame
        ): ElicitationResponse < (Async & Abort[McpRequestElicitationFailure]) =
            Sync.Unsafe.defer(self.requestElicitation(request).safe.get)

        /** Sends a typed `elicitation/create`: derives the requested schema from `A`, and decodes the
          * `Accept` payload to `A` with a typed decode failure for a non-conforming client.
          */
        def requestElicitationAs[A](message: String)(using
            schema: Schema[A],
            frame: Frame
        )
            : ElicitationOutcome[A] < (Async & Abort[McpRequestElicitationAsFailure]) =
            def decodeContent(sv: Structure.Value): ElicitationOutcome[A] < Abort[McpRequestElicitationAsFailure] =
                Structure.decode[A](sv)(using schema, frame) match
                    case Result.Success(a) => ElicitationOutcome.Accept(a)
                    case Result.Failure(e) =>
                        Abort.fail(McpToolStructuredDecodeException("elicitation/create", e.getMessage, Present(e)))
                    case Result.Panic(t) => Abort.panic(t)
            end decodeContent
            val request = ElicitationRequest(message, Json.jsonSchema[A])
            // The elicitation call's failure trait is widened to the elicitation-as trait at its leaves:
            // every McpRequestElicitationFailure leaf also mixes into McpRequestElicitationAsFailure.
            val elicited: ElicitationResponse < (Async & Abort[McpRequestElicitationAsFailure]) =
                Abort.recover[McpRequestElicitationFailure] {
                    case e: McpElicitationDeclinedException => Abort.fail(e)
                    case e: McpConnectionClosedException    => Abort.fail(e)
                }(Sync.Unsafe.defer(self.requestElicitation(request).safe.get))
            elicited.flatMap { resp =>
                resp.action match
                    case ElicitationResponse.Action.Decline => ElicitationOutcome.Decline
                    case ElicitationResponse.Action.Cancel  => ElicitationOutcome.Cancel
                    case ElicitationResponse.Action.Accept =>
                        resp.content match
                            case Absent =>
                                Abort.fail(McpToolStructuredDecodeException("elicitation/create", "Accept carried no content", Absent))
                            case Present(sv) => decodeContent(sv)
            }
        end requestElicitationAs

        /** Sends `notifications/tools/list_changed`. */
        def notifyToolsListChanged(using Frame): Unit < (Async & Abort[McpConnectionClosedException]) =
            Sync.Unsafe.defer(self.notifyToolsListChanged.safe.get)

        /** Sends `notifications/resources/list_changed`. */
        def notifyResourcesListChanged(using Frame): Unit < (Async & Abort[McpConnectionClosedException]) =
            Sync.Unsafe.defer(self.notifyResourcesListChanged.safe.get)

        /** Sends `notifications/resources/updated` for one URI. */
        def notifyResourceUpdated(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[McpConnectionClosedException]) =
            Sync.Unsafe.defer(self.notifyResourceUpdated(uri).safe.get)

        /** Sends `notifications/prompts/list_changed`. */
        def notifyPromptsListChanged(using Frame): Unit < (Async & Abort[McpConnectionClosedException]) =
            Sync.Unsafe.defer(self.notifyPromptsListChanged.safe.get)

        /** Sends `notifications/message` (server-to-client structured log). */
        def notifyLog[T](level: LogLevel, data: T, logger: Maybe[String] = Absent)(using
            Frame,
            Schema[T]
        ): Unit < (Async & Abort[McpConnectionClosedException]) =
            Sync.Unsafe.defer(self.notifyLog(level, data, logger).safe.get)

        /** Returns the negotiated protocol version (Absent before handshake completes). */
        def protocolVersion: Maybe[McpConfig.ProtocolVersion] = self.protocolVersion

        /** Returns the client's advertised capabilities (Absent before handshake completes). */
        def clientCapabilities: Maybe[McpCapabilities.Client] = self.clientCapabilities

        /** Returns the client info (Absent before handshake completes). */
        def clientInfo: Maybe[McpInfo] = self.clientInfo

        /** Underlying JsonRpcHandler (escape hatch for advanced consumers). */
        def underlying: JsonRpcHandler = self.underlying

        /** Awaits until all in-flight requests have drained. */
        def awaitDrain(using Frame): Unit < Async = Sync.Unsafe.defer(self.awaitDrain.safe.get)

        /** Closes the server with a default 30-second grace period. */
        def close(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(30.seconds).safe.get)

        /** Closes the server with an explicit grace period. */
        def close(gracePeriod: Duration)(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(gracePeriod).safe.get)

        /** Closes the server immediately without waiting for in-flight requests. */
        def closeNow(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(Duration.Zero).safe.get)

        /** Runs the handler close effect directly, without spawning a detached fiber.
          *
          * Used by the Scope.acquireRelease release slot so the close runs in-place on the
          * scope's finalizer fiber rather than spawning a new unsupervised fiber.
          */
        private[kyo] def closeDirect(using Frame): Unit < Async = self.closeDirect

        /** Returns the underlying Unsafe handle. */
        def unsafe: Unsafe = self

    end extension

    abstract class Unsafe:
        def requestSampling(request: SamplingRequest)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[SamplingResponse, Abort[McpRequestSamplingFailure]]
        def requestRoots(using AllowUnsafe, Frame): Fiber.Unsafe[Chunk[Root], Abort[McpRequestRootsFailure]]
        def requestElicitation(request: ElicitationRequest)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[ElicitationResponse, Abort[McpRequestElicitationFailure]]
        def notifyToolsListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]]
        def notifyResourcesListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]]
        def notifyResourceUpdated(uri: McpResourceUri)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]]
        def notifyPromptsListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]]
        def notifyLog[T](level: LogLevel, data: T, logger: Maybe[String])(using
            AllowUnsafe,
            Frame,
            Schema[T]
        ): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]]
        def protocolVersion: Maybe[McpConfig.ProtocolVersion]
        def clientCapabilities: Maybe[McpCapabilities.Client]
        def clientInfo: Maybe[McpInfo]
        def underlying: JsonRpcHandler
        def awaitDrain(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]
        def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]
        private[kyo] def closeDirect(using Frame): Unit < Async
        final def safe: McpServer = this
    end Unsafe

    // --- Scoped init quartet ---

    /** Initialises a server using `handlers` and `McpConfig.default`, releasing it when the `Scope` exits. */
    def init(transport: JsonRpcTransport, handlers: McpHandler[?, ?, ?]*)(using
        Frame
    ): McpServer < (Async & Scope) =
        init(transport, handlers, McpConfig.default)

    /** Initialises a server using `handlers` and the supplied `config`, releasing it when the `Scope` exits. */
    def init(transport: JsonRpcTransport, config: McpConfig)(handlers: McpHandler[?, ?, ?]*)(using
        Frame
    ): McpServer < (Async & Scope) =
        init(transport, handlers, config)

    /** Initialises a server from a `Seq` of handlers and optional config, releasing it when the `Scope` exits. */
    def init(
        transport: JsonRpcTransport,
        handlers: Seq[McpHandler[?, ?, ?]],
        config: McpConfig = McpConfig.default
    )(using Frame): McpServer < (Async & Scope) =
        McpConfig.require(config)
        Scope.acquireRelease(
            internal.mcp.McpEngine.initServer(transport, handlers, config).map(_.safe)
        )(srv => srv.closeDirect)
    end init

    /** Initialises a server and immediately applies `f`, releasing the server when the `Scope` exits. */
    def initWith[A, S](transport: JsonRpcTransport, handlers: McpHandler[?, ?, ?]*)(f: McpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        init(transport, handlers*).map(f)

    /** Initialises a server with `config` and immediately applies `f`, releasing the server when the `Scope` exits. */
    def initWith[A, S](transport: JsonRpcTransport, config: McpConfig)(handlers: McpHandler[?, ?, ?]*)(f: McpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        init(transport, config)(handlers*).map(f)

    // --- Unscoped init ---

    /** Initialises a server using `handlers` and `McpConfig.default` without a managed `Scope`. */
    def initUnscoped(transport: JsonRpcTransport, handlers: McpHandler[?, ?, ?]*)(using
        Frame
    ): McpServer < Async =
        initUnscoped(transport, handlers, McpConfig.default)

    /** Initialises a server using `handlers` and the supplied `config` without a managed `Scope`. */
    def initUnscoped(transport: JsonRpcTransport, config: McpConfig)(handlers: McpHandler[?, ?, ?]*)(using
        Frame
    ): McpServer < Async =
        initUnscoped(transport, handlers, config)

    /** Initialises a server from a `Seq` of handlers without a managed `Scope`.
      *
      * The returned `McpServer` is UNSCOPED: the caller owns its lifecycle and MUST close it,
      * ideally under `Scope.ensure`, or the reader/writer fibers and the underlying transport
      * leak on interrupt. Prefer `init` (Scope-managed, closed exactly once on scope exit)
      * unless a manual lifecycle is genuinely required.
      */
    def initUnscoped(
        transport: JsonRpcTransport,
        handlers: Seq[McpHandler[?, ?, ?]],
        config: McpConfig = McpConfig.default
    )(using Frame): McpServer < Async =
        McpConfig.require(config)
        internal.mcp.McpEngine.initServer(transport, handlers, config).map(_.safe)
    end initUnscoped

    /** Initialises an unscoped server and immediately applies `f`. */
    def initUnscopedWith[A, S](transport: JsonRpcTransport, handlers: McpHandler[?, ?, ?]*)(f: McpServer => A < S)(using
        Frame
    ): A < (S & Async) =
        initUnscoped(transport, handlers*).map(f)

    /** Initialises an unscoped server with `config` and immediately applies `f`. */
    def initUnscopedWith[A, S](transport: JsonRpcTransport, config: McpConfig)(handlers: McpHandler[?, ?, ?]*)(f: McpServer => A < S)(using
        Frame
    ): A < (S & Async) =
        initUnscoped(transport, config)(handlers*).map(f)

end McpServer
