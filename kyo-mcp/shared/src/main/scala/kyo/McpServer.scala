package kyo

/** Live MCP server handle managing one peer over a [[JsonRpcTransport]].
  *
  * Obtain via `McpServer.init` (Scope-managed) or `McpServer.initUnscoped` (manual close).
  * Mirrors `JsonRpcHandler` at kyo-jsonrpc/.../JsonRpcHandler.scala:30 and
  * `HttpServer` at kyo-http/.../HttpServer.scala:37.
  *
  * INV-012: `McpServer = McpServer.Unsafe` (opaque identity).
  *
  * @see [[McpServer.init]]
  * @see [[McpServer.initUnscoped]]
  */
opaque type McpServer = McpServer.Unsafe

object McpServer:

    extension (self: McpServer)

        /** Sends `sampling/createMessage` to the connected client. */
        def requestSampling(req: McpSamplingRequest)(using Frame): McpSamplingResponse < (Async & Abort[McpError | Closed]) =
            self.requestSamplingUnsafe(req)

        /** Sends `roots/list` to the connected client. */
        def requestRoots(using Frame): Chunk[McpRoot] < (Async & Abort[McpError | Closed]) =
            self.requestRootsUnsafe

        /** Sends `elicitation/create` to the connected client. */
        def requestElicitation(req: McpElicitationRequest)(using Frame): McpElicitationResponse < (Async & Abort[McpError | Closed]) =
            self.requestElicitationUnsafe(req)

        /** Sends `notifications/tools/list_changed`. */
        def notifyToolsListChanged(using Frame): Unit < (Async & Abort[Closed]) =
            self.notifyToolsListChangedUnsafe

        /** Sends `notifications/resources/list_changed`. */
        def notifyResourcesListChanged(using Frame): Unit < (Async & Abort[Closed]) =
            self.notifyResourcesListChangedUnsafe

        /** Sends `notifications/resources/updated` for one URI.
          * Audit-A2: `uri` is typed `McpResourceUri`, not raw `String`.
          */
        def notifyResourceUpdated(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[Closed]) =
            self.notifyResourceUpdatedUnsafe(uri)

        /** Sends `notifications/prompts/list_changed`. */
        def notifyPromptsListChanged(using Frame): Unit < (Async & Abort[Closed]) =
            self.notifyPromptsListChangedUnsafe

        /** Sends `notifications/message` (server-to-client structured log).
          * Audit-C1: `using` clause order is `(Frame, Schema[T])` per CONTRIBUTING.md:349-351.
          */
        def notifyLog[T](level: McpLogLevel, data: T, logger: Maybe[String] = Absent)(using
            Frame,
            Schema[T]
        ): Unit < (Async & Abort[Closed]) =
            self.notifyLogUnsafe(level, data, logger)

        /** Returns the negotiated protocol version (Absent before handshake completes). */
        def protocolVersion: Maybe[McpProtocolVersion] = self.protocolVersionUnsafe

        /** Returns the client's advertised capabilities (Absent before handshake completes). */
        def clientCapabilities: Maybe[McpCapabilities.Client] = self.clientCapabilitiesUnsafe

        /** Returns the client info (Absent before handshake completes). */
        def clientInfo: Maybe[McpInfo] = self.clientInfoUnsafe

        /** Underlying JsonRpcHandler (escape hatch for advanced consumers). */
        def underlying: JsonRpcHandler = self.underlyingUnsafe

        /** Awaits until all in-flight requests have drained. */
        def awaitDrain(using Frame): Unit < Async = self.awaitDrainUnsafe

        /** Closes the server with a default 30-second grace period.
          * Audit-B1: matches `HttpServer.close(using Frame)` at kyo-http/.../HttpServer.scala:56.
          */
        def close(using Frame): Unit < Async = self.closeUnsafe(30.seconds)

        /** Closes the server with an explicit grace period. */
        def close(gracePeriod: Duration)(using Frame): Unit < Async = self.closeUnsafe(gracePeriod)

        /** Closes the server immediately without waiting for in-flight requests. */
        def closeNow(using Frame): Unit < Async = self.closeUnsafe(Duration.Zero)

        /** Returns the underlying Unsafe handle. */
        def unsafe: Unsafe = self

    end extension

    abstract class Unsafe:
        def requestSamplingUnsafe(req: McpSamplingRequest)(using Frame): McpSamplingResponse < (Async & Abort[McpError | Closed])
        def requestRootsUnsafe(using Frame): Chunk[McpRoot] < (Async & Abort[McpError | Closed])
        def requestElicitationUnsafe(req: McpElicitationRequest)(using Frame): McpElicitationResponse < (Async & Abort[McpError | Closed])
        def notifyToolsListChangedUnsafe(using Frame): Unit < (Async & Abort[Closed])
        def notifyResourcesListChangedUnsafe(using Frame): Unit < (Async & Abort[Closed])
        def notifyResourceUpdatedUnsafe(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[Closed])
        def notifyPromptsListChangedUnsafe(using Frame): Unit < (Async & Abort[Closed])
        def notifyLogUnsafe[T](level: McpLogLevel, data: T, logger: Maybe[String])(using Frame, Schema[T]): Unit < (Async & Abort[Closed])
        def protocolVersionUnsafe: Maybe[McpProtocolVersion]
        def clientCapabilitiesUnsafe: Maybe[McpCapabilities.Client]
        def clientInfoUnsafe: Maybe[McpInfo]
        def underlyingUnsafe: JsonRpcHandler
        def awaitDrainUnsafe(using Frame): Unit < Async
        def closeUnsafe(gracePeriod: Duration)(using Frame): Unit < Async
        final def safe: McpServer = this
    end Unsafe

    // --- Scoped init quartet ---

    /** Initialises a server using `routes` and `McpConfig.default`, releasing it when the `Scope` exits. */
    def init(transport: JsonRpcTransport, routes: McpRoute[?, ?, ?]*)(using Frame): McpServer < (Async & Scope) =
        init(transport, routes, McpConfig.default)

    /** Initialises a server using `routes` and the supplied `config`, releasing it when the `Scope` exits. */
    def init(transport: JsonRpcTransport, config: McpConfig)(routes: McpRoute[?, ?, ?]*)(using Frame): McpServer < (Async & Scope) =
        init(transport, routes, config)

    /** Initialises a server from a `Seq` of routes and optional config, releasing it when the `Scope` exits. */
    def init(
        transport: JsonRpcTransport,
        routes: Seq[McpRoute[?, ?, ?]],
        config: McpConfig = McpConfig.default
    )(using Frame): McpServer < (Async & Scope) =
        McpConfig.require(config)
        Scope.acquireRelease(
            internal.mcp.McpEngine.initServer(transport, routes, config).map(_.safe)
        )(_.closeNow)
    end init

    /** Initialises a server and immediately applies `f`, releasing the server when the `Scope` exits. */
    def initWith[A, S](transport: JsonRpcTransport, routes: McpRoute[?, ?, ?]*)(f: McpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        init(transport, routes*).map(f)

    /** Initialises a server with `config` and immediately applies `f`, releasing the server when the `Scope` exits. */
    def initWith[A, S](transport: JsonRpcTransport, config: McpConfig)(routes: McpRoute[?, ?, ?]*)(f: McpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        init(transport, config)(routes*).map(f)

    // --- Unscoped init ---

    /** Initialises a server using `routes` and `McpConfig.default` without a managed `Scope`. */
    def initUnscoped(transport: JsonRpcTransport, routes: McpRoute[?, ?, ?]*)(using Frame): McpServer < Async =
        initUnscoped(transport, routes, McpConfig.default)

    /** Initialises a server using `routes` and the supplied `config` without a managed `Scope`. */
    def initUnscoped(transport: JsonRpcTransport, config: McpConfig)(routes: McpRoute[?, ?, ?]*)(using Frame): McpServer < Async =
        initUnscoped(transport, routes, config)

    /** Initialises a server from a `Seq` of routes without a managed `Scope`. */
    def initUnscoped(
        transport: JsonRpcTransport,
        routes: Seq[McpRoute[?, ?, ?]],
        config: McpConfig = McpConfig.default
    )(using Frame): McpServer < Async =
        McpConfig.require(config)
        internal.mcp.McpEngine.initServer(transport, routes, config).map(_.safe)
    end initUnscoped

    /** Initialises an unscoped server and immediately applies `f`. */
    def initUnscopedWith[A, S](transport: JsonRpcTransport, routes: McpRoute[?, ?, ?]*)(f: McpServer => A < S)(using
        Frame
    ): A < (S & Async) =
        initUnscoped(transport, routes*).map(f)

    /** Initialises an unscoped server with `config` and immediately applies `f`. */
    def initUnscopedWith[A, S](transport: JsonRpcTransport, config: McpConfig)(routes: McpRoute[?, ?, ?]*)(f: McpServer => A < S)(using
        Frame
    ): A < (S & Async) =
        initUnscoped(transport, config)(routes*).map(f)

end McpServer
