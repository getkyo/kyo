package kyo

/** Live MCP server handle managing one peer over a [[JsonRpcTransport]].
  *
  * Obtain via `McpServer.init` (Scope-managed) or `McpServer.initUnscoped` (manual close).
  * Mirrors `JsonRpcHandler` at kyo-jsonrpc/.../JsonRpcHandler.scala:30 and
  * `HttpServer` at kyo-http/.../HttpServer.scala:37.
  *
  * INV-012: `McpServer = McpServer.Unsafe` (opaque identity).
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
      * The `metadata` field is an INV-021 allowlist pass-through: the MCP spec defines
      * `_meta` as an open JSON object, so the user receives it as `Structure.Value` rather
      * than a typed shape.
      *
      * @param messages          the conversation turns to continue
      * @param modelPreferences  optional model selection hints and cost/speed/intelligence weights
      * @param systemPrompt      optional system prompt to include
      * @param includeContext    whether to include server/client context in the message
      * @param temperature       sampling temperature hint
      * @param maxTokens         maximum tokens for the sampled response
      * @param stopSequences     sequences that halt generation early
      * @param metadata          spec-defined open `_meta` field (INV-021 allowlist pass-through per §11a)
      */
    // flow-allow: Structure carve-out per §11a / INV-021
    final case class SamplingRequest(
        messages: Chunk[SamplingRequest.Message],
        modelPreferences: Maybe[SamplingRequest.ModelPreferences] = Absent,
        systemPrompt: Maybe[String] = Absent,
        includeContext: Maybe[SamplingRequest.IncludeContext] = Absent,
        temperature: Maybe[Double] = Absent,
        maxTokens: Int,
        stopSequences: Chunk[String] = Chunk.empty,
        // flow-allow: Structure carve-out per §11a / INV-021
        metadata: Maybe[Structure.Value] = Absent
    ) derives Schema, CanEqual

    object SamplingRequest:

        /** A single message in the sampling conversation. */
        final case class Message(role: McpContent.Role, content: SamplingContent) derives Schema, CanEqual

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
            // Wire strings use camelCase and do not match toString.toLowerCase (INV-010).
            // None is qualified as IncludeContext.None to avoid shadowing scala.None.
            given Schema[IncludeContext] = Schema.stringSchema.transform[IncludeContext] {
                case "none"       => IncludeContext.None
                case "thisServer" => IncludeContext.ThisServer
                case "allServers" => IncludeContext.AllServers
            } {
                case IncludeContext.None       => "none"
                case IncludeContext.ThisServer => "thisServer"
                case IncludeContext.AllServers => "allServers"
            }
        end IncludeContext

    end SamplingRequest

    /** Content type for `sampling/createMessage` request messages.
      *
      * A subset of [[McpContent]] restricted to Text, Image, and Audio only.
      * EmbeddedResource and ResourceLink are excluded per §3.10 (Option A typed subset, Q2).
      * Use `toMcpContent` to convert to the broader [[McpContent]] type.
      */
    sealed trait SamplingContent derives CanEqual:
        def toMcpContent: McpContent

    object SamplingContent:
        /** Text content for a sampling message. */
        final case class Text(
            text: String,
            annotations: McpContent.Annotations = McpContent.Annotations.noop
        ) extends SamplingContent:
            def toMcpContent: McpContent = McpContent.Text(text, annotations)
        end Text

        /** Image content for a sampling message. */
        final case class Image(
            data: String,
            mimeType: McpMimeType,
            annotations: McpContent.Annotations = McpContent.Annotations.noop
        ) extends SamplingContent:
            def toMcpContent: McpContent = McpContent.Image(data, mimeType, annotations)
        end Image

        /** Audio content for a sampling message. */
        final case class Audio(
            data: String,
            mimeType: McpMimeType,
            annotations: McpContent.Annotations = McpContent.Annotations.noop
        ) extends SamplingContent:
            def toMcpContent: McpContent = McpContent.Audio(data, mimeType, annotations)
        end Audio

        // Hand-rolled Schema using "type" discriminator for wire compatibility.
        // flow-allow: Structure carve-out per §11a / INV-021 (hand-rolled Schema body)
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
        model: String,
        stopReason: Maybe[SamplingResponse.StopReason] = Absent
    ) derives CanEqual

    object SamplingResponse:

        /** Typed stop reason for `sampling/createMessage` responses.
          *
          * Wire strings: `"endTurn"` | `"stopSequence"` | `"maxTokens"`.
          * Unknown wire strings decode tolerantly to `EndTurn` per Q8 decision.
          */
        enum StopReason derives CanEqual:
            case EndTurn, StopSequence, MaxTokens

        object StopReason:
            given Schema[StopReason] = Schema.stringSchema.transform[StopReason] {
                case "endTurn"      => StopReason.EndTurn
                case "stopSequence" => StopReason.StopSequence
                case "maxTokens"    => StopReason.MaxTokens
                case _              => StopReason.EndTurn
            } {
                case StopReason.EndTurn      => "endTurn"
                case StopReason.StopSequence => "stopSequence"
                case StopReason.MaxTokens    => "maxTokens"
            }
        end StopReason

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
      * The `content` field is an INV-021 allowlist pass-through: the MCP spec leaves the
      * elicitation response payload as an open JSON object, so it is surfaced as
      * `Maybe[Structure.Value]`.
      *
      * @param action  whether the user accepted, declined, or cancelled the elicitation
      * @param content the user-supplied content when action is Accept (INV-021 allowlist per §11a)
      */
    // flow-allow: Structure carve-out per §11a / INV-021
    final case class ElicitationResponse(
        action: ElicitationResponse.Action,
        // flow-allow: Structure carve-out per §11a / INV-021
        content: Maybe[Structure.Value] = Absent
    ) derives Schema, CanEqual

    object ElicitationResponse:

        /** The user's decision regarding the elicitation request. */
        enum Action derives CanEqual:
            case Accept, Decline, Cancel

        object Action:
            // Wire strings: "accept" | "decline" | "cancel" (INV-010).
            // capitalize maps lowercase wire string to Scala case name: "accept" -> "Accept".
            given Schema[Action] = Schema.stringSchema.transform(s => Action.valueOf(s.capitalize))(
                _.toString.toLowerCase
            )
        end Action

    end ElicitationResponse

    /** A root entry returned by the client in response to a `roots/list` request.
      *
      * INV-022: `uri` is typed `McpResourceUri`, not raw `String`, per Audit-A2.
      *
      * @param uri  the root URI
      * @param name optional human-readable name for the root
      */
    final case class Root(uri: McpResourceUri, name: Maybe[String] = Absent) derives Schema, CanEqual

    /** MCP log level enum with 8 severity levels.
      *
      * Wire strings are lowercase and match the Scala case name lowercase: `"debug"` | `"info"` |
      * `"notice"` | `"warning"` | `"error"` | `"critical"` | `"alert"` | `"emergency"`.
      * Phase 3 replaces the Schema stub with `Schema.stringSchema.transform` per Q-006 / INV-010.
      * Do NOT add `Schema` to the `derives` clause.
      */
    enum LogLevel derives CanEqual:
        case Debug, Info, Notice, Warning, Error, Critical, Alert, Emergency

    object LogLevel:

        // Wire strings: "debug"|"info"|"notice"|"warning"|"error"|"critical"|"alert"|"emergency" (INV-010).
        // capitalize maps lowercase wire string to Scala case name: "debug" -> "Debug".
        given Schema[LogLevel] = Schema.stringSchema.transform(s => LogLevel.valueOf(s.capitalize))(
            _.toString.toLowerCase
        )

    end LogLevel

    extension (self: McpServer)

        /** Sends `sampling/createMessage` to the connected client. */
        def requestSampling(req: SamplingRequest)(using Frame): SamplingResponse < (Async & Abort[McpException | Closed]) =
            Sync.Unsafe.defer(self.requestSampling(req).safe.get)

        /** Sends `roots/list` to the connected client. */
        def requestRoots(using Frame): Chunk[Root] < (Async & Abort[McpException | Closed]) =
            Sync.Unsafe.defer(self.requestRoots.safe.get)

        /** Sends `elicitation/create` to the connected client. */
        def requestElicitation(req: ElicitationRequest)(using Frame): ElicitationResponse < (Async & Abort[McpException | Closed]) =
            Sync.Unsafe.defer(self.requestElicitation(req).safe.get)

        /** Sends `notifications/tools/list_changed`. */
        def notifyToolsListChanged(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.notifyToolsListChanged.safe.get)

        /** Sends `notifications/resources/list_changed`. */
        def notifyResourcesListChanged(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.notifyResourcesListChanged.safe.get)

        /** Sends `notifications/resources/updated` for one URI.
          * Audit-A2: `uri` is typed `McpResourceUri`, not raw `String`.
          */
        def notifyResourceUpdated(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.notifyResourceUpdated(uri).safe.get)

        /** Sends `notifications/prompts/list_changed`. */
        def notifyPromptsListChanged(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.notifyPromptsListChanged.safe.get)

        /** Sends `notifications/message` (server-to-client structured log).
          * Audit-C1: `using` clause order is `(Frame, Schema[T])` per CONTRIBUTING.md:349-351.
          */
        def notifyLog[T](level: LogLevel, data: T, logger: Maybe[String] = Absent)(using
            Frame,
            Schema[T]
        ): Unit < (Async & Abort[Closed]) =
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

        /** Closes the server with a default 30-second grace period.
          * Audit-B1: matches `HttpServer.close(using Frame)` at kyo-http/.../HttpServer.scala:56.
          */
        def close(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(30.seconds).safe.get)

        /** Closes the server with an explicit grace period. */
        def close(gracePeriod: Duration)(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(gracePeriod).safe.get)

        /** Closes the server immediately without waiting for in-flight requests. */
        def closeNow(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(Duration.Zero).safe.get)

        /** Returns the underlying Unsafe handle. */
        def unsafe: Unsafe = self

    end extension

    abstract class Unsafe:
        def requestSampling(req: SamplingRequest)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[SamplingResponse, Abort[McpException | Closed]]
        def requestRoots(using AllowUnsafe, Frame): Fiber.Unsafe[Chunk[Root], Abort[McpException | Closed]]
        def requestElicitation(req: ElicitationRequest)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[ElicitationResponse, Abort[McpException | Closed]]
        def notifyToolsListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        def notifyResourcesListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        def notifyResourceUpdated(uri: McpResourceUri)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        def notifyPromptsListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        def notifyLog[T](level: LogLevel, data: T, logger: Maybe[String])(using
            AllowUnsafe,
            Frame,
            Schema[T]
        ): Fiber.Unsafe[Unit, Abort[Closed]]
        def protocolVersion: Maybe[McpConfig.ProtocolVersion]
        def clientCapabilities: Maybe[McpCapabilities.Client]
        def clientInfo: Maybe[McpInfo]
        def underlying: JsonRpcHandler
        def awaitDrain(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]
        def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]
        final def safe: McpServer = this
    end Unsafe

    // --- Scoped init quartet ---

    /** Initialises a server using `routes` and `McpConfig.default`, releasing it when the `Scope` exits. */
    def init(transport: JsonRpcTransport, routes: McpHandler[?, ?, ?]*)(using Frame): McpServer < (Async & Scope) =
        init(transport, routes, McpConfig.default)

    /** Initialises a server using `routes` and the supplied `config`, releasing it when the `Scope` exits. */
    def init(transport: JsonRpcTransport, config: McpConfig)(routes: McpHandler[?, ?, ?]*)(using Frame): McpServer < (Async & Scope) =
        init(transport, routes, config)

    /** Initialises a server from a `Seq` of routes and optional config, releasing it when the `Scope` exits. */
    def init(
        transport: JsonRpcTransport,
        routes: Seq[McpHandler[?, ?, ?]],
        config: McpConfig = McpConfig.default
    )(using Frame): McpServer < (Async & Scope) =
        McpConfig.require(config)
        Scope.acquireRelease(
            internal.mcp.McpEngine.initServer(transport, routes, config).map(_.safe)
        )(_.closeNow)
    end init

    /** Initialises a server and immediately applies `f`, releasing the server when the `Scope` exits. */
    def initWith[A, S](transport: JsonRpcTransport, routes: McpHandler[?, ?, ?]*)(f: McpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        init(transport, routes*).map(f)

    /** Initialises a server with `config` and immediately applies `f`, releasing the server when the `Scope` exits. */
    def initWith[A, S](transport: JsonRpcTransport, config: McpConfig)(routes: McpHandler[?, ?, ?]*)(f: McpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        init(transport, config)(routes*).map(f)

    // --- Unscoped init ---

    /** Initialises a server using `routes` and `McpConfig.default` without a managed `Scope`. */
    def initUnscoped(transport: JsonRpcTransport, routes: McpHandler[?, ?, ?]*)(using Frame): McpServer < Async =
        initUnscoped(transport, routes, McpConfig.default)

    /** Initialises a server using `routes` and the supplied `config` without a managed `Scope`. */
    def initUnscoped(transport: JsonRpcTransport, config: McpConfig)(routes: McpHandler[?, ?, ?]*)(using Frame): McpServer < Async =
        initUnscoped(transport, routes, config)

    /** Initialises a server from a `Seq` of routes without a managed `Scope`. */
    def initUnscoped(
        transport: JsonRpcTransport,
        routes: Seq[McpHandler[?, ?, ?]],
        config: McpConfig = McpConfig.default
    )(using Frame): McpServer < Async =
        McpConfig.require(config)
        internal.mcp.McpEngine.initServer(transport, routes, config).map(_.safe)
    end initUnscoped

    /** Initialises an unscoped server and immediately applies `f`. */
    def initUnscopedWith[A, S](transport: JsonRpcTransport, routes: McpHandler[?, ?, ?]*)(f: McpServer => A < S)(using
        Frame
    ): A < (S & Async) =
        initUnscoped(transport, routes*).map(f)

    /** Initialises an unscoped server with `config` and immediately applies `f`. */
    def initUnscopedWith[A, S](transport: JsonRpcTransport, config: McpConfig)(routes: McpHandler[?, ?, ?]*)(f: McpServer => A < S)(using
        Frame
    ): A < (S & Async) =
        initUnscoped(transport, config)(routes*).map(f)

end McpServer
