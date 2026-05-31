package kyo

/** Live MCP client handle managing one peer over a [[JsonRpcTransport]].
  *
  * Obtain via `McpClient.init` (Scope-managed) or `McpClient.initUnscoped` (manual close).
  * The client issues the `initialize` handshake and then exposes typed methods for all
  * standard MCP operations.
  *
  * INV-012: `McpClient = McpClient.Unsafe` (opaque identity).
  * INV-014: parameter order on `init` is `(transport, clientInfo, capabilities, routes*)`.
  *
  * @see [[McpClient.init]]
  * @see [[McpClient.initUnscoped]]
  */
opaque type McpClient = McpClient.Unsafe

object McpClient:

    extension (self: McpClient)

        /** Lists the server's tools. Returns a named `McpPage` record (Audit-A3 / INV-023). */
        def listTools(cursor: Maybe[String] = Absent)(using Frame): McpPage[McpRoute.ToolMeta] < (Async & Abort[McpException | Closed]) =
            self.listToolsUnsafe(cursor)

        /** Calls a tool with a typed argument; returns the raw `ToolCallResult`.
          * Audit-C2: `using` clause order is `(Frame, Schema[In])` per CONTRIBUTING.md:349-351.
          */
        def callTool[In](name: String, arguments: In)(using
            Frame,
            Schema[In]
        ): McpRoute.ToolCallResult < (Async & Abort[McpException | Closed]) =
            self.callToolUnsafe[In](name, arguments)

        /** Calls a tool with typed argument and typed output; aborts with `McpToolStructuredMissingException`
          * when `structuredContent` is `Absent` (Audit-A9 / INV-027).
          * Audit-C2: `using` clause order is `(Frame, Schema[In], Schema[Out])`.
          * STEER-3: renamed from `callTool[In, Out]` to `callToolTyped[In, Out]` because Scala 3
          * cannot disambiguate two same-named extension methods that differ only in type-parameter count;
          * see phase-08/decisions.md § STEER-3.
          */
        def callToolTyped[In, Out](name: String, arguments: In)(using
            Frame,
            Schema[In],
            Schema[Out]
        ): Out < (Async & Abort[McpException | Closed]) =
            self.callToolTypedUnsafe[In, Out](name, arguments)

        /** Lists the server's resources. Returns a named `McpPage` record (Audit-A3). */
        def listResources(cursor: Maybe[String] = Absent)(using
            Frame
        ): McpPage[McpRoute.ResourceMeta] < (Async & Abort[McpException | Closed]) =
            self.listResourcesUnsafe(cursor)

        /** Lists the server's resource templates. Returns a named `McpPage` record. */
        def listResourceTemplates(cursor: Maybe[String] = Absent)(using
            Frame
        ): McpPage[McpRoute.ResourceTemplateMeta] < (Async & Abort[McpException | Closed]) =
            self.listResourceTemplatesUnsafe(cursor)

        /** Reads a resource by URI.
          * INV-022: `uri` is typed `McpResourceUri`, not raw `String`.
          */
        def readResource(uri: McpResourceUri)(using Frame): Chunk[McpResourceContents] < (Async & Abort[McpException | Closed]) =
            self.readResourceUnsafe(uri)

        /** Lists the server's prompts. Returns a named `McpPage` record (Audit-A3). */
        def listPrompts(cursor: Maybe[String] = Absent)(using
            Frame
        ): McpPage[McpRoute.PromptMeta] < (Async & Abort[McpException | Closed]) =
            self.listPromptsUnsafe(cursor)

        /** Fetches a prompt by name with optional arguments. */
        def getPrompt(name: String, arguments: Map[String, String] = Map.empty)(using
            Frame
        ): McpRoute.PromptGetResult < (Async & Abort[McpException | Closed]) =
            self.getPromptUnsafe(name, arguments)

        /** Sets the minimum log level the client wishes to receive. */
        def setLogLevel(level: McpLogLevel)(using Frame): Unit < (Async & Abort[McpException | Closed]) =
            self.setLogLevelUnsafe(level)

        /** Requests a completion suggestion from the server. */
        def complete(ref: McpRoute.CompletionRef, arg: McpRoute.CompletionArg)(using
            Frame
        ): McpRoute.CompletionResult < (Async & Abort[McpException | Closed]) =
            self.completeUnsafe(ref, arg, Absent)

        /** Sends `resources/subscribe` for one URI to the server. */
        def subscribeResource(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[McpException | Closed]) =
            self.subscribeResourceUnsafe(uri)

        /** Sends `resources/unsubscribe` for one URI to the server. */
        def unsubscribeResource(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[McpException | Closed]) =
            self.unsubscribeResourceUnsafe(uri)

        /** Sends `notifications/roots/list_changed` to the server. */
        def notifyRootsListChanged(using Frame): Unit < (Async & Abort[Closed]) =
            self.notifyRootsListChangedUnsafe

        /** Returns the server's advertised capabilities (Absent before handshake completes). */
        def serverCapabilities: Maybe[McpCapabilities.Server] = self.serverCapabilitiesUnsafe

        /** Returns the server info (Absent before handshake completes). */
        def serverInfo: Maybe[McpInfo] = self.serverInfoUnsafe

        /** Returns the negotiated protocol version (Absent before handshake completes). */
        def protocolVersion: Maybe[McpProtocolVersion] = self.protocolVersionUnsafe

        /** Underlying JsonRpcHandler (escape hatch for advanced consumers). */
        def underlying: JsonRpcHandler = self.underlyingUnsafe

        /** Closes the client with a default 30-second grace period. */
        def close(using Frame): Unit < Async = self.closeUnsafe(30.seconds)

        /** Closes the client with an explicit grace period. */
        def close(gracePeriod: Duration)(using Frame): Unit < Async = self.closeUnsafe(gracePeriod)

        /** Closes the client immediately without waiting for in-flight requests. */
        def closeNow(using Frame): Unit < Async = self.closeUnsafe(Duration.Zero)

        /** Sends a `ping` request to the server and awaits the empty response. */
        def ping(using Frame): Unit < (Async & Abort[McpException | Closed]) =
            self.pingUnsafe

        /** Returns the underlying Unsafe handle. */
        def unsafe: Unsafe = self

    end extension

    abstract class Unsafe:
        def listToolsUnsafe(cursor: Maybe[String])(using Frame): McpPage[McpRoute.ToolMeta] < (Async & Abort[McpException | Closed])
        def callToolUnsafe[In](name: String, arguments: In)(using
            Frame,
            Schema[In]
        ): McpRoute.ToolCallResult < (Async & Abort[McpException | Closed])
        def callToolTypedUnsafe[In, Out](name: String, arguments: In)(using
            Frame,
            Schema[In],
            Schema[Out]
        ): Out < (Async & Abort[McpException | Closed])
        def listResourcesUnsafe(cursor: Maybe[String])(using Frame): McpPage[McpRoute.ResourceMeta] < (Async & Abort[McpException | Closed])
        def listResourceTemplatesUnsafe(cursor: Maybe[String])(using
            Frame
        ): McpPage[McpRoute.ResourceTemplateMeta] < (Async & Abort[McpException | Closed])
        def readResourceUnsafe(uri: McpResourceUri)(using Frame): Chunk[McpResourceContents] < (Async & Abort[McpException | Closed])
        def listPromptsUnsafe(cursor: Maybe[String])(using Frame): McpPage[McpRoute.PromptMeta] < (Async & Abort[McpException | Closed])
        def getPromptUnsafe(name: String, arguments: Map[String, String])(using
            Frame
        ): McpRoute.PromptGetResult < (Async & Abort[McpException | Closed])
        def setLogLevelUnsafe(level: McpLogLevel)(using Frame): Unit < (Async & Abort[McpException | Closed])
        def completeUnsafe(ref: McpRoute.CompletionRef, arg: McpRoute.CompletionArg, context: Maybe[McpRoute.CompletionArg.Context])(using
            Frame
        ): McpRoute.CompletionResult < (Async & Abort[McpException | Closed])
        def notifyRootsListChangedUnsafe(using Frame): Unit < (Async & Abort[Closed])
        def pingUnsafe(using Frame): Unit < (Async & Abort[McpException | Closed])
        def subscribeResourceUnsafe(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[McpException | Closed])
        def unsubscribeResourceUnsafe(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[McpException | Closed])
        def serverCapabilitiesUnsafe: Maybe[McpCapabilities.Server]
        def serverInfoUnsafe: Maybe[McpInfo]
        def protocolVersionUnsafe: Maybe[McpProtocolVersion]
        def underlyingUnsafe: JsonRpcHandler
        def closeUnsafe(gracePeriod: Duration)(using Frame): Unit < Async
        final def safe: McpClient = this
    end Unsafe

    // --- Scoped init quartet (INV-014: parameter order = transport, clientInfo, capabilities, routes*) ---

    /** Initialises a client using `routes` and `McpConfig.default`, releasing it when the `Scope` exits. */
    def init(transport: JsonRpcTransport, clientInfo: McpInfo, capabilities: McpCapabilities.Client, routes: McpRoute[?, ?, ?]*)(using
        Frame
    ): McpClient < (Async & Scope & Abort[McpException | Closed]) =
        init(transport, clientInfo, capabilities, routes, McpConfig.default)

    /** Initialises a client from a `Seq` of routes and optional config, releasing it when the `Scope` exits. */
    def init(
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        routes: Seq[McpRoute[?, ?, ?]],
        config: McpConfig = McpConfig.default
    )(using Frame): McpClient < (Async & Scope & Abort[McpException | Closed]) =
        McpConfig.require(config)
        Scope.acquireRelease(
            internal.mcp.McpClientEngine.initClient(transport, clientInfo, capabilities, routes, config).map(_.safe)
        )(_.closeNow)
    end init

    /** Initialises a client using `routes` and `config` (curried), releasing it when the `Scope` exits.
      * Mirrors the McpServer.init(transport, config)(routes*) W2 curried overload.
      */
    def init(
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        config: McpConfig
    )(routes: McpRoute[?, ?, ?]*)(using Frame): McpClient < (Async & Scope & Abort[McpException | Closed]) =
        init(transport, clientInfo, capabilities, routes, config)

    /** Initialises a client and immediately applies `f`, releasing the client when the `Scope` exits. */
    def initWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        routes: McpRoute[?, ?, ?]*
    )(f: McpClient => A < S)(using Frame): A < (S & Async & Scope & Abort[McpException | Closed]) =
        init(transport, clientInfo, capabilities, routes*).map(f)

    /** Initialises a client with `config` and immediately applies `f` (curried W2 overload). */
    def initWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        config: McpConfig
    )(routes: McpRoute[?, ?, ?]*)(f: McpClient => A < S)(using Frame): A < (S & Async & Scope & Abort[McpException | Closed]) =
        init(transport, clientInfo, capabilities, config)(routes*).map(f)

    // --- Unscoped init ---

    /** Initialises a client using `routes` and `McpConfig.default` without a managed `Scope`. */
    def initUnscoped(
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        routes: McpRoute[?, ?, ?]*
    )(using Frame): McpClient < (Async & Abort[McpException | Closed]) =
        initUnscoped(transport, clientInfo, capabilities, routes, McpConfig.default)

    /** Initialises a client from a `Seq` of routes without a managed `Scope`. */
    def initUnscoped(
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        routes: Seq[McpRoute[?, ?, ?]],
        config: McpConfig = McpConfig.default
    )(using Frame): McpClient < (Async & Abort[McpException | Closed]) =
        McpConfig.require(config)
        internal.mcp.McpClientEngine.initClient(transport, clientInfo, capabilities, routes, config).map(_.safe)
    end initUnscoped

    /** Initialises an unscoped client using `routes` and `config` (curried W2 overload). */
    def initUnscoped(
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        config: McpConfig
    )(routes: McpRoute[?, ?, ?]*)(using Frame): McpClient < (Async & Abort[McpException | Closed]) =
        initUnscoped(transport, clientInfo, capabilities, routes, config)

    /** Initialises an unscoped client and immediately applies `f`. */
    def initUnscopedWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        routes: McpRoute[?, ?, ?]*
    )(f: McpClient => A < S)(using Frame): A < (S & Async & Abort[McpException | Closed]) =
        initUnscoped(transport, clientInfo, capabilities, routes*).map(f)

    /** Initialises an unscoped client with `config` and immediately applies `f` (curried W2 overload). */
    def initUnscopedWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        config: McpConfig
    )(routes: McpRoute[?, ?, ?]*)(f: McpClient => A < S)(using Frame): A < (S & Async & Abort[McpException | Closed]) =
        initUnscoped(transport, clientInfo, capabilities, config)(routes*).map(f)

end McpClient
