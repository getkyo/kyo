package kyo

/** Live MCP client handle managing one peer over a [[JsonRpcTransport]].
  *
  * Obtain via `McpClient.init` (Scope-managed) or `McpClient.initUnscoped` (manual close).
  * The client issues the `initialize` handshake and then exposes typed methods for all
  * standard MCP operations.
  *
  * @see [[McpClient.init]]
  * @see [[McpClient.initUnscoped]]
  */
opaque type McpClient = McpClient.Unsafe

object McpClient:

    /** Named-record paginated list result.
      *
      * `nextCursor` is `Absent` on the last page. `meta` carries the optional `_meta`
      * advisory field from the MCP spec (§3.7); it is omitted from the wire when `Absent`.
      *
      * @tparam A the item type
      */
    final case class Page[+A](items: Chunk[A], nextCursor: Maybe[McpCursor], meta: Maybe[Structure.Value] = Absent) derives CanEqual:
        /** Returns `true` when there are no further pages. */
        def isLast: Boolean = nextCursor.isEmpty

    object Page:
        /** Returns an empty page with no cursor. */
        def empty[A]: Page[A] = Page(Chunk.empty, Absent)

        /** Constructs a page from items and an optional continuation cursor. */
        def of[A](items: Chunk[A], next: Maybe[McpCursor]): Page[A] = Page(items, next)

        given [A: Schema]: Schema[Page[A]] = Schema.derived

        extension [A](self: Page[A])
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

    end Page

    extension (self: McpClient)

        /** Lists the server's tools. Returns a named `Page` record. */
        def listTools(cursor: Maybe[McpCursor] = Absent)(using Frame): Page[McpHandler.ToolMeta] < (Async & Abort[McpListFailure]) =
            guarded(McpCapabilities.Name.Tools)(Sync.Unsafe.defer(self.listTools(cursor).safe.get))

        /** Calls a tool with typed argument and typed output (the default lane). Validates the
          * received `structuredContent` and aborts a distinct `McpToolStructuredMissingException`
          * (no structured content) vs `McpToolStructuredDecodeException` (present but undecodable).
          *
          * `[In]` is inferred from `input`; only `[Out]` is annotated, matching the server-side
          * `McpHandler.tool[In](...)[Out]` clause-interleaving convention.
          */
        def callTool[Out](name: String)[In](input: In)(using
            Frame,
            Schema[In],
            Schema[Out]
        ): Out < (Async & Abort[McpCallToolFailure]) =
            guarded(McpCapabilities.Name.Tools)(Sync.Unsafe.defer(self.callToolTyped[In, Out](name, input).safe.get))

        /** Calls a tool and returns the raw `ToolOutcome` (the escape hatch). */
        def callToolRaw[In](name: String, arguments: In)(using
            Frame,
            Schema[In]
        ): McpHandler.ToolOutcome < (Async & Abort[McpCallToolRawFailure]) =
            guarded(McpCapabilities.Name.Tools)(Sync.Unsafe.defer(self.callTool[In](name, arguments).safe.get))

        /** Lists the server's resources. Returns a named `Page` record. */
        def listResources(cursor: Maybe[McpCursor] = Absent)(using
            Frame
        ): Page[McpHandler.ResourceMeta] < (Async & Abort[McpListFailure]) =
            guarded(McpCapabilities.Name.Resources)(Sync.Unsafe.defer(self.listResources(cursor).safe.get))

        /** Lists the server's resource templates. Returns a named `Page` record. */
        def listResourceTemplates(cursor: Maybe[McpCursor] = Absent)(using
            Frame
        ): Page[McpHandler.ResourceTemplateMeta] < (Async & Abort[McpListFailure]) =
            guarded(McpCapabilities.Name.Resources)(Sync.Unsafe.defer(self.listResourceTemplates(cursor).safe.get))

        /** Reads a resource by URI and decodes the resource's text payload to `Out` (the default lane).
          *
          * The resource must serve a single text leaf whose content is a serialized `Out` (for
          * example, JSON). A non-text leaf (Blob) or a text leaf whose content does not decode to
          * `Out` aborts with [[McpToolStructuredDecodeException]]; an empty response aborts with
          * [[McpToolStructuredMissingException]]. Use `readResourceRaw` when the resource serves
          * non-decodable or multi-leaf content.
          */
        def readResource[Out](uri: McpResourceUri)(using Frame, Schema[Out]): Out < (Async & Abort[McpReadResourceFailure]) =
            guarded(McpCapabilities.Name.Resources)(widenReadResourceRaw(readResourceRaw(uri)).map(decodeSingle[Out]("resources/read", _)))

        /** Reads a resource by URI; returns the raw `ResourceContents` (the escape hatch). */
        def readResourceRaw(uri: McpResourceUri)(using
            Frame
        ): Chunk[McpHandler.ResourceContents] < (Async & Abort[McpReadResourceRawFailure]) =
            guarded(McpCapabilities.Name.Resources)(Sync.Unsafe.defer(self.readResource(uri).safe.get))

        /** Lists the server's prompts. Returns a named `Page` record. */
        def listPrompts(cursor: Maybe[McpCursor] = Absent)(using
            Frame
        ): Page[McpHandler.PromptMeta] < (Async & Abort[McpListFailure]) =
            guarded(McpCapabilities.Name.Prompts)(Sync.Unsafe.defer(self.listPrompts(cursor).safe.get))

        /** Fetches a prompt and decodes its `_meta`/structured payload to `Out` (the default lane). */
        def getPrompt[Out](name: String, arguments: Map[String, String] = Map.empty)(using
            Frame,
            Schema[Out]
        ): Out < (Async & Abort[McpGetPromptFailure]) =
            guarded(McpCapabilities.Name.Prompts)(widenGetPromptRaw(getPromptRaw(name, arguments)).map(decodePrompt[Out](name, _)))

        /** Fetches a prompt; returns the raw `PromptOutcome` (the escape hatch). */
        def getPromptRaw(name: String, arguments: Map[String, String] = Map.empty)(using
            Frame
        ): McpHandler.PromptOutcome < (Async & Abort[McpGetPromptRawFailure]) =
            guarded(McpCapabilities.Name.Prompts)(Sync.Unsafe.defer(self.getPrompt(name, arguments).safe.get))

        /** Sets the minimum log level the client wishes to receive. */
        def setLogLevel(level: McpServer.LogLevel)(using Frame): Unit < (Async & Abort[McpClientRequestFailure]) =
            Sync.Unsafe.defer(self.setLogLevel(level).safe.get)

        /** Requests a completion suggestion from the server, with optional previously-filled context. */
        def complete(
            ref: McpHandler.CompletionRef,
            arg: McpHandler.CompletionArg,
            context: Maybe[McpHandler.CompletionArg.Context] = Absent
        )(using
            Frame
        ): McpHandler.CompletionOutcome < (Async & Abort[McpCompleteFailure]) =
            guarded(McpCapabilities.Name.Completions)(Sync.Unsafe.defer(self.complete(ref, arg, context).safe.get))

        /** Fetches a prompt with its arguments validated against the server's advertised `PromptMeta`;
          * aborts `McpInvalidArgumentException` for an unknown key or a missing required argument.
          */
        def getPromptChecked[Out](name: String, arguments: Map[String, String] = Map.empty)(using
            Frame,
            Schema[Out]
        ): Out < (Async & Abort[McpGetPromptCheckedFailure]) =
            guarded(McpCapabilities.Name.Prompts) {
                widenListToGetPromptChecked(streamPrompts.run).map { metas =>
                    Maybe.fromOption(metas.find(_.name == name)) match
                        case Present(meta) =>
                            val declared = meta.arguments.map(_.name).toSet
                            val required = meta.arguments.filter(_.required).map(_.name).toSet
                            val unknown  = arguments.keySet.diff(declared)
                            val missing  = required.diff(arguments.keySet)
                            if unknown.nonEmpty then
                                Abort.fail(McpInvalidArgumentException("prompts/get", unknown.toList.sorted.head, "unknown argument"))
                            else if missing.nonEmpty then
                                Abort.fail(McpInvalidArgumentException(
                                    "prompts/get",
                                    missing.toList.sorted.head,
                                    "required argument missing"
                                ))
                            else widenGetPromptToChecked(getPrompt[Out](name, arguments))
                            end if
                        case Absent => widenGetPromptToChecked(getPrompt[Out](name, arguments))
                }
            }

        /** Auto-draining stream of every tool (paged `listTools` + `Page` retained for manual paging). */
        def streamTools(using Frame): Stream[McpHandler.ToolMeta, Async & Abort[McpListFailure]] =
            drain(c => guarded(McpCapabilities.Name.Tools)(Sync.Unsafe.defer(self.listTools(c).safe.get)))

        /** Auto-draining stream of every resource. */
        def streamResources(using Frame): Stream[McpHandler.ResourceMeta, Async & Abort[McpListFailure]] =
            drain(c => guarded(McpCapabilities.Name.Resources)(Sync.Unsafe.defer(self.listResources(c).safe.get)))

        /** Auto-draining stream of every resource template. */
        def streamResourceTemplates(using Frame): Stream[McpHandler.ResourceTemplateMeta, Async & Abort[McpListFailure]] =
            drain(c => guarded(McpCapabilities.Name.Resources)(Sync.Unsafe.defer(self.listResourceTemplates(c).safe.get)))

        /** Auto-draining stream of every prompt. */
        def streamPrompts(using Frame): Stream[McpHandler.PromptMeta, Async & Abort[McpListFailure]] =
            drain(c => guarded(McpCapabilities.Name.Prompts)(Sync.Unsafe.defer(self.listPrompts(c).safe.get)))

        /** Reports whether the server advertised `cap`, so a caller can branch without catching. */
        def supports(cap: McpCapabilities.Name)(using Frame): Boolean < Sync =
            Sync.defer(McpClient.capabilityAdvertised(cap, self.serverCapabilities))

        /** Sends `resources/subscribe` for one URI to the server. */
        def subscribeResource(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[McpClientRequestFailure]) =
            Sync.Unsafe.defer(self.subscribeResource(uri).safe.get)

        /** Sends `resources/unsubscribe` for one URI to the server. */
        def unsubscribeResource(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[McpClientRequestFailure]) =
            Sync.Unsafe.defer(self.unsubscribeResource(uri).safe.get)

        /** Sends `notifications/roots/list_changed` to the server. */
        def notifyRootsListChanged(using Frame): Unit < (Async & Abort[McpConnectionClosedException]) =
            Sync.Unsafe.defer(self.notifyRootsListChanged.safe.get)

        /** Returns the server's advertised capabilities. Non-`Maybe`: the scoped handle is always
          * post-handshake. The `Maybe` form lives on the `Unsafe` tier.
          */
        def serverCapabilities(using Frame): McpCapabilities.Server < Sync =
            Sync.defer(self.serverCapabilities.getOrElse(McpCapabilities.Server()))

        /** Returns the server info (non-`Maybe`: the scoped handle is post-handshake). */
        def serverInfo(using Frame): McpInfo < Sync =
            Sync.defer(self.serverInfo.getOrElse(McpInfo("unknown")))

        /** Returns the negotiated protocol version (non-`Maybe`: the scoped handle is post-handshake). */
        def protocolVersion(using Frame): McpConfig.ProtocolVersion < Sync =
            Sync.defer(self.protocolVersion.getOrElse(McpConfig.ProtocolVersion.current))

        /** Underlying JsonRpcHandler (escape hatch for advanced consumers). */
        def underlying: JsonRpcHandler = self.underlying

        /** Closes the client with a default 30-second grace period. */
        def close(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(30.seconds).safe.get)

        /** Closes the client with an explicit grace period. */
        def close(gracePeriod: Duration)(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(gracePeriod).safe.get)

        /** Closes the client immediately without waiting for in-flight requests. */
        def closeNow(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(Duration.Zero).safe.get)

        /** Runs the handler close effect directly, without spawning a detached fiber.
          *
          * Used by the Scope.acquireRelease release slot so the close runs in-place on the
          * scope's finalizer fiber rather than spawning a new unsupervised fiber.
          */
        private[kyo] def closeDirect(using Frame): Unit < Async = self.closeDirect

        /** Sends a `ping` request to the server and awaits the empty response. */
        def ping(using Frame): Unit < (Async & Abort[McpClientRequestFailure]) =
            Sync.Unsafe.defer(self.ping.safe.get)

        /** Returns the underlying Unsafe handle. */
        def unsafe: Unsafe = self

        // Early local capability guard: aborts the same typed error the wire would, minus the round
        // trip. Generic over the operation's failure trait `E`; the guard leaf mixes into every
        // guarded operation's trait, so it is admitted by the `E >: McpCapabilityNotAdvertisedException` bound.
        private def guarded[A, S, E >: McpCapabilityNotAdvertisedException <: McpException](cap: McpCapabilities.Name)(
            body: => A < (S & Abort[E])
        )(using
            Frame
        ): A < (S & Async & Abort[E]) =
            if McpClient.capabilityAdvertised(cap, self.serverCapabilities) then body
            else
                Abort.fail(McpCapabilityNotAdvertisedException(
                    McpClient.methodFor(cap),
                    cap,
                    McpCapabilityNotAdvertisedException.Peer.Server
                ))

        // --- Failure-trait widening bridges ------------------------------------
        // A delegating typed operation composes a sibling operation's row (a different sealed trait)
        // whose every leaf also mixes into the target trait. The compiler cannot see the sibling
        // trait as a subtype, so each bridge re-fails the value at its concrete leaf type, which the
        // target trait admits. The matches are exhaustive over the source trait's leaves; adding a
        // leaf to a source trait fails the match to compile, surfacing the new case here.

        private def widenReadResourceRaw[A, S](v: A < (S & Abort[McpReadResourceRawFailure]))(using
            Frame
        ): A < (S & Abort[McpReadResourceFailure]) =
            Abort.recover[McpReadResourceRawFailure] {
                case e: McpCapabilityNotAdvertisedException => Abort.fail(e)
                case e: McpRemoteApplicationException       => Abort.fail(e)
                case e: McpConnectionClosedException        => Abort.fail(e)
            }(v)

        private def widenGetPromptRaw[A, S](v: A < (S & Abort[McpGetPromptRawFailure]))(using
            Frame
        ): A < (S & Abort[McpGetPromptFailure]) =
            Abort.recover[McpGetPromptRawFailure] {
                case e: McpCapabilityNotAdvertisedException => Abort.fail(e)
                case e: McpRemoteApplicationException       => Abort.fail(e)
                case e: McpConnectionClosedException        => Abort.fail(e)
            }(v)

        private def widenListToGetPromptChecked[A, S](v: A < (S & Abort[McpListFailure]))(using
            Frame
        ): A < (S & Abort[McpGetPromptCheckedFailure]) =
            Abort.recover[McpListFailure] {
                case e: McpCapabilityNotAdvertisedException => Abort.fail(e)
                case e: McpRemoteApplicationException       => Abort.fail(e)
                case e: McpConnectionClosedException        => Abort.fail(e)
            }(v)

        private def widenGetPromptToChecked[A, S](v: A < (S & Abort[McpGetPromptFailure]))(using
            Frame
        ): A < (S & Abort[McpGetPromptCheckedFailure]) =
            Abort.recover[McpGetPromptFailure] {
                case e: McpCapabilityNotAdvertisedException => Abort.fail(e)
                case e: McpRemoteApplicationException       => Abort.fail(e)
                case e: McpToolStructuredMissingException   => Abort.fail(e)
                case e: McpToolStructuredDecodeException    => Abort.fail(e)
                case e: McpConnectionClosedException        => Abort.fail(e)
            }(v)

        private def decodeSingle[Out](method: String, contents: Chunk[McpHandler.ResourceContents])(using
            Frame,
            Schema[Out]
        ): Out < Abort[McpReadResourceFailure] =
            contents.headMaybe match
                case Absent => Abort.fail(McpToolStructuredMissingException(method))
                case Present(rc) =>
                    rc match
                        case McpHandler.ResourceContents.Text(_, _, text) =>
                            Json.decode[Out](text) match
                                case Result.Success(o) => o
                                case Result.Failure(e) => Abort.fail(McpToolStructuredDecodeException(method, e.getMessage, Present(e)))
                                case Result.Panic(t)   => Abort.panic(t)
                        case _ =>
                            Abort.fail(McpToolStructuredDecodeException(
                                method,
                                "resource content is not text-decodable for typed read",
                                Absent
                            ))

        private def decodePrompt[Out](name: String, outcome: McpHandler.PromptOutcome)(using
            Frame,
            Schema[Out]
        ): Out < Abort[McpGetPromptFailure] =
            outcome.meta match
                case Absent => Abort.fail(McpToolStructuredMissingException(name))
                case Present(sv) =>
                    Structure.decode[Out](sv) match
                        case Result.Success(o) => o
                        case Result.Failure(e) => Abort.fail(McpToolStructuredDecodeException(name, e.getMessage, Present(e)))
                        case Result.Panic(t)   => Abort.panic(t)

        private def drain[A](page: Maybe[McpCursor] => Page[A] < (Async & Abort[McpListFailure]))(using
            Frame,
            Tag[Emit[Chunk[A]]]
        ): Stream[A, Async & Abort[McpListFailure]] =
            Stream:
                def loop(cursor: Maybe[McpCursor]): Unit < (Emit[Chunk[A]] & Async & Abort[McpListFailure]) =
                    page(cursor).map { p =>
                        Emit.value(p.items).andThen {
                            p.nextCursor match
                                case Present(next) => loop(Present(next))
                                case Absent        => ()
                        }
                    }
                loop(Absent)

    end extension

    /** Returns true when `cap` is advertised in the (post-handshake) server capabilities. */
    private[kyo] def capabilityAdvertised(cap: McpCapabilities.Name, caps: Maybe[McpCapabilities.Server]): Boolean =
        caps match
            case Absent => false
            case Present(c) =>
                cap match
                    case McpCapabilities.Name.Tools       => c.tools.isDefined
                    case McpCapabilities.Name.Resources   => c.resources.isDefined
                    case McpCapabilities.Name.Prompts     => c.prompts.isDefined
                    case McpCapabilities.Name.Logging     => c.logging.isDefined
                    case McpCapabilities.Name.Completions => c.completions.isDefined
                    case _                                => true

    private[kyo] def methodFor(cap: McpCapabilities.Name): String =
        cap match
            case McpCapabilities.Name.Tools       => "tools/call"
            case McpCapabilities.Name.Resources   => "resources/read"
            case McpCapabilities.Name.Prompts     => "prompts/get"
            case McpCapabilities.Name.Logging     => "logging/setLevel"
            case McpCapabilities.Name.Completions => "completion/complete"
            case other                            => other.toString.toLowerCase

    abstract class Unsafe:
        def listTools(cursor: Maybe[McpCursor])(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Page[McpHandler.ToolMeta], Abort[McpListFailure]]
        def callTool[In](name: String, arguments: In)(using
            AllowUnsafe,
            Frame,
            Schema[In]
        ): Fiber.Unsafe[McpHandler.ToolOutcome, Abort[McpCallToolRawFailure]]
        def callToolTyped[In, Out](name: String, arguments: In)(using
            AllowUnsafe,
            Frame,
            Schema[In],
            Schema[Out]
        ): Fiber.Unsafe[Out, Abort[McpCallToolFailure]]
        def listResources(cursor: Maybe[McpCursor])(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Page[McpHandler.ResourceMeta], Abort[McpListFailure]]
        def listResourceTemplates(cursor: Maybe[McpCursor])(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Page[McpHandler.ResourceTemplateMeta], Abort[McpListFailure]]
        def readResource(uri: McpResourceUri)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[McpHandler.ResourceContents], Abort[McpReadResourceRawFailure]]
        def listPrompts(cursor: Maybe[McpCursor])(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Page[McpHandler.PromptMeta], Abort[McpListFailure]]
        def getPrompt(name: String, arguments: Map[String, String])(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[McpHandler.PromptOutcome, Abort[McpGetPromptRawFailure]]
        def setLogLevel(level: McpServer.LogLevel)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpClientRequestFailure]]
        def complete(ref: McpHandler.CompletionRef, arg: McpHandler.CompletionArg, context: Maybe[McpHandler.CompletionArg.Context])(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[McpHandler.CompletionOutcome, Abort[McpCompleteFailure]]
        def notifyRootsListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]]
        def ping(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpClientRequestFailure]]
        def subscribeResource(uri: McpResourceUri)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpClientRequestFailure]]
        def unsubscribeResource(uri: McpResourceUri)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpClientRequestFailure]]
        def serverCapabilities: Maybe[McpCapabilities.Server]
        def serverInfo: Maybe[McpInfo]
        def protocolVersion: Maybe[McpConfig.ProtocolVersion]
        def underlying: JsonRpcHandler
        def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]
        private[kyo] def closeDirect(using Frame): Unit < Async
        final def safe: McpClient = this
    end Unsafe

    // --- Scoped init quartet (parameter order = transport, clientInfo, capabilities, handlers*) ---

    /** Initialises a client using `handlers` and `McpConfig.default`, releasing it when the `Scope` exits. */
    def init(
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        handlers: McpClientHandler[?, ?, ?]*
    )(using
        Frame
    ): McpClient < (Async & Scope & Abort[McpInitFailure]) =
        init(transport, clientInfo, capabilities, handlers, McpConfig.default)

    /** Initialises a client from a `Seq` of handlers and optional config, releasing it when the `Scope` exits. */
    def init(
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        handlers: Seq[McpClientHandler[?, ?, ?]],
        config: McpConfig = McpConfig.default
    )(using Frame): McpClient < (Async & Scope & Abort[McpInitFailure]) =
        McpConfig.require(config)
        internal.mcp.McpReverseHandlerValidation.require(handlers, capabilities).andThen(
            Scope.acquireRelease(
                internal.mcp.McpClientEngine.initClient(transport, clientInfo, capabilities, handlers, config).map(_.safe)
            )(client => client.closeDirect)
        )
    end init

    /** Initialises a client using `handlers` and `config` (curried), releasing it when the `Scope` exits.
      * Mirrors the McpServer.init(transport, config)(handlers*) W2 curried overload.
      */
    def init(
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        config: McpConfig
    )(handlers: McpClientHandler[?, ?, ?]*)(using Frame): McpClient < (Async & Scope & Abort[McpInitFailure]) =
        init(transport, clientInfo, capabilities, handlers, config)

    /** Initialises a client and immediately applies `f`, releasing the client when the `Scope` exits. */
    def initWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        handlers: McpClientHandler[?, ?, ?]*
    )(f: McpClient => A < S)(using Frame): A < (S & Async & Scope & Abort[McpInitFailure]) =
        init(transport, clientInfo, capabilities, handlers*).map(f)

    /** Initialises a client with `config` and immediately applies `f` (curried W2 overload). */
    def initWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        config: McpConfig
    )(handlers: McpClientHandler[?, ?, ?]*)(f: McpClient => A < S)(using Frame): A < (S & Async & Scope & Abort[McpInitFailure]) =
        init(transport, clientInfo, capabilities, config)(handlers*).map(f)

    // --- Unscoped init ---

    /** Initialises a client using `handlers` and `McpConfig.default` without a managed `Scope`. */
    def initUnscoped(
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        handlers: McpClientHandler[?, ?, ?]*
    )(using Frame): McpClient < (Async & Abort[McpInitFailure]) =
        initUnscoped(transport, clientInfo, capabilities, handlers, McpConfig.default)

    /** Initialises a client from a `Seq` of handlers without a managed `Scope`.
      *
      * The returned `McpClient` is UNSCOPED: the caller owns its lifecycle and MUST close it,
      * ideally under `Scope.ensure`, or the reader/writer fibers and the underlying transport
      * leak on interrupt. Prefer `init` (Scope-managed, closed exactly once on scope exit)
      * unless a manual lifecycle is genuinely required.
      */
    def initUnscoped(
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        handlers: Seq[McpClientHandler[?, ?, ?]],
        config: McpConfig = McpConfig.default
    )(using Frame): McpClient < (Async & Abort[McpInitFailure]) =
        McpConfig.require(config)
        internal.mcp.McpReverseHandlerValidation.require(handlers, capabilities).andThen(
            internal.mcp.McpClientEngine.initClient(transport, clientInfo, capabilities, handlers, config).map(_.safe)
        )
    end initUnscoped

    /** Initialises an unscoped client using `handlers` and `config` (curried W2 overload). */
    def initUnscoped(
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        config: McpConfig
    )(handlers: McpClientHandler[?, ?, ?]*)(using Frame): McpClient < (Async & Abort[McpInitFailure]) =
        initUnscoped(transport, clientInfo, capabilities, handlers, config)

    /** Initialises an unscoped client and immediately applies `f`. */
    def initUnscopedWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        handlers: McpClientHandler[?, ?, ?]*
    )(f: McpClient => A < S)(using Frame): A < (S & Async & Abort[McpInitFailure]) =
        initUnscoped(transport, clientInfo, capabilities, handlers*).map(f)

    /** Initialises an unscoped client with `config` and immediately applies `f` (curried W2 overload). */
    def initUnscopedWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        config: McpConfig
    )(handlers: McpClientHandler[?, ?, ?]*)(f: McpClient => A < S)(using Frame): A < (S & Async & Abort[McpInitFailure]) =
        initUnscoped(transport, clientInfo, capabilities, config)(handlers*).map(f)

end McpClient
