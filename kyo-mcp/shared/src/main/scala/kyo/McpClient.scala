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

    /** Named-record paginated list result.
      *
      * Replaces the tuple `(Chunk[A], Maybe[String])` pattern per Audit-A3 / INV-023.
      * `nextCursor` is `Absent` on the last page. `meta` carries the optional `_meta`
      * advisory field from the MCP spec (§3.7); it is omitted from the wire when `Absent`.
      *
      * @tparam A the item type
      */
    // flow-allow: Structure carve-out per §11a / INV-021
    final case class Page[+A](items: Chunk[A], nextCursor: Maybe[String], meta: Maybe[Structure.Value] = Absent) derives CanEqual:
        /** Returns `true` when there are no further pages. */
        def isLast: Boolean = nextCursor.isEmpty

    object Page:
        /** Returns an empty page with no cursor. */
        def empty[A]: Page[A] = Page(Chunk.empty, Absent)

        /** Constructs a page from items and an optional continuation cursor. */
        def of[A](items: Chunk[A], next: Maybe[String]): Page[A] = Page(items, next)

        given [A: Schema]: Schema[Page[A]] = Schema.derived

    end Page

    extension (self: McpClient)

        /** Lists the server's tools. Returns a named `Page` record (Audit-A3 / INV-023). */
        def listTools(cursor: Maybe[String] = Absent)(using Frame): Page[McpRoute.ToolMeta] < (Async & Abort[McpException | Closed]) =
            Sync.Unsafe.defer(self.listTools(cursor).safe.get)

        /** Calls a tool with a typed argument; returns the raw `ToolCallResult`.
          * Audit-C2: `using` clause order is `(Frame, Schema[In])` per CONTRIBUTING.md:349-351.
          */
        def callTool[In](name: String, arguments: In)(using
            Frame,
            Schema[In]
        ): McpRoute.ToolCallResult < (Async & Abort[McpException | Closed]) =
            Sync.Unsafe.defer(self.callTool[In](name, arguments).safe.get)

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
            Sync.Unsafe.defer(self.callToolTyped[In, Out](name, arguments).safe.get)

        /** Lists the server's resources. Returns a named `Page` record (Audit-A3). */
        def listResources(cursor: Maybe[String] = Absent)(using
            Frame
        ): Page[McpRoute.ResourceMeta] < (Async & Abort[McpException | Closed]) =
            Sync.Unsafe.defer(self.listResources(cursor).safe.get)

        /** Lists the server's resource templates. Returns a named `Page` record. */
        def listResourceTemplates(cursor: Maybe[String] = Absent)(using
            Frame
        ): Page[McpRoute.ResourceTemplateMeta] < (Async & Abort[McpException | Closed]) =
            Sync.Unsafe.defer(self.listResourceTemplates(cursor).safe.get)

        /** Reads a resource by URI.
          * INV-022: `uri` is typed `McpResourceUri`, not raw `String`.
          */
        def readResource(uri: McpResourceUri)(using Frame): Chunk[McpRoute.ResourceContents] < (Async & Abort[McpException | Closed]) =
            Sync.Unsafe.defer(self.readResource(uri).safe.get)

        /** Lists the server's prompts. Returns a named `Page` record (Audit-A3). */
        def listPrompts(cursor: Maybe[String] = Absent)(using
            Frame
        ): Page[McpRoute.PromptMeta] < (Async & Abort[McpException | Closed]) =
            Sync.Unsafe.defer(self.listPrompts(cursor).safe.get)

        /** Fetches a prompt by name with optional arguments. */
        def getPrompt(name: String, arguments: Map[String, String] = Map.empty)(using
            Frame
        ): McpRoute.PromptGetResult < (Async & Abort[McpException | Closed]) =
            Sync.Unsafe.defer(self.getPrompt(name, arguments).safe.get)

        /** Sets the minimum log level the client wishes to receive. */
        def setLogLevel(level: McpServer.LogLevel)(using Frame): Unit < (Async & Abort[McpException | Closed]) =
            Sync.Unsafe.defer(self.setLogLevel(level).safe.get)

        /** Requests a completion suggestion from the server. */
        def complete(ref: McpRoute.CompletionRef, arg: McpRoute.CompletionArg)(using
            Frame
        ): McpRoute.CompletionResult < (Async & Abort[McpException | Closed]) =
            Sync.Unsafe.defer(self.complete(ref, arg, Absent).safe.get)

        /** Sends `resources/subscribe` for one URI to the server. */
        def subscribeResource(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[McpException | Closed]) =
            Sync.Unsafe.defer(self.subscribeResource(uri).safe.get)

        /** Sends `resources/unsubscribe` for one URI to the server. */
        def unsubscribeResource(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[McpException | Closed]) =
            Sync.Unsafe.defer(self.unsubscribeResource(uri).safe.get)

        /** Sends `notifications/roots/list_changed` to the server. */
        def notifyRootsListChanged(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.notifyRootsListChanged.safe.get)

        /** Returns the server's advertised capabilities (Absent before handshake completes). */
        def serverCapabilities: Maybe[McpCapabilities.Server] = self.serverCapabilities

        /** Returns the server info (Absent before handshake completes). */
        def serverInfo: Maybe[McpInfo] = self.serverInfo

        /** Returns the negotiated protocol version (Absent before handshake completes). */
        def protocolVersion: Maybe[McpConfig.ProtocolVersion] = self.protocolVersion

        /** Underlying JsonRpcHandler (escape hatch for advanced consumers). */
        def underlying: JsonRpcHandler = self.underlying

        /** Closes the client with a default 30-second grace period. */
        def close(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(30.seconds).safe.get)

        /** Closes the client with an explicit grace period. */
        def close(gracePeriod: Duration)(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(gracePeriod).safe.get)

        /** Closes the client immediately without waiting for in-flight requests. */
        def closeNow(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(Duration.Zero).safe.get)

        /** Sends a `ping` request to the server and awaits the empty response. */
        def ping(using Frame): Unit < (Async & Abort[McpException | Closed]) =
            Sync.Unsafe.defer(self.ping.safe.get)

        /** Returns the underlying Unsafe handle. */
        def unsafe: Unsafe = self

    end extension

    abstract class Unsafe:
        def listTools(cursor: Maybe[String])(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Page[McpRoute.ToolMeta], Abort[McpException | Closed]]
        def callTool[In](name: String, arguments: In)(using
            AllowUnsafe,
            Frame,
            Schema[In]
        ): Fiber.Unsafe[McpRoute.ToolCallResult, Abort[McpException | Closed]]
        def callToolTyped[In, Out](name: String, arguments: In)(using
            AllowUnsafe,
            Frame,
            Schema[In],
            Schema[Out]
        ): Fiber.Unsafe[Out, Abort[McpException | Closed]]
        def listResources(cursor: Maybe[String])(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Page[McpRoute.ResourceMeta], Abort[McpException | Closed]]
        def listResourceTemplates(cursor: Maybe[String])(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Page[McpRoute.ResourceTemplateMeta], Abort[McpException | Closed]]
        def readResource(uri: McpResourceUri)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[McpRoute.ResourceContents], Abort[McpException | Closed]]
        def listPrompts(cursor: Maybe[String])(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Page[McpRoute.PromptMeta], Abort[McpException | Closed]]
        def getPrompt(name: String, arguments: Map[String, String])(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[McpRoute.PromptGetResult, Abort[McpException | Closed]]
        def setLogLevel(level: McpServer.LogLevel)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpException | Closed]]
        def complete(ref: McpRoute.CompletionRef, arg: McpRoute.CompletionArg, context: Maybe[McpRoute.CompletionArg.Context])(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[McpRoute.CompletionResult, Abort[McpException | Closed]]
        def notifyRootsListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        def ping(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpException | Closed]]
        def subscribeResource(uri: McpResourceUri)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpException | Closed]]
        def unsubscribeResource(uri: McpResourceUri)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpException | Closed]]
        def serverCapabilities: Maybe[McpCapabilities.Server]
        def serverInfo: Maybe[McpInfo]
        def protocolVersion: Maybe[McpConfig.ProtocolVersion]
        def underlying: JsonRpcHandler
        def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]
        final def safe: McpClient = this
    end Unsafe

    // --- Scoped init quartet (INV-014: parameter order = transport, clientInfo, capabilities, routes*) ---

    /** Initialises a client using `routes` and `McpConfig.default`, releasing it when the `Scope` exits. */
    def init(transport: JsonRpcTransport, clientInfo: McpInfo, capabilities: McpCapabilities.Client, routes: McpHandler[?, ?, ?]*)(using
        Frame
    ): McpClient < (Async & Scope & Abort[McpException | Closed]) =
        init(transport, clientInfo, capabilities, routes, McpConfig.default)

    /** Initialises a client from a `Seq` of routes and optional config, releasing it when the `Scope` exits. */
    def init(
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        routes: Seq[McpHandler[?, ?, ?]],
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
    )(routes: McpHandler[?, ?, ?]*)(using Frame): McpClient < (Async & Scope & Abort[McpException | Closed]) =
        init(transport, clientInfo, capabilities, routes, config)

    /** Initialises a client and immediately applies `f`, releasing the client when the `Scope` exits. */
    def initWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        routes: McpHandler[?, ?, ?]*
    )(f: McpClient => A < S)(using Frame): A < (S & Async & Scope & Abort[McpException | Closed]) =
        init(transport, clientInfo, capabilities, routes*).map(f)

    /** Initialises a client with `config` and immediately applies `f` (curried W2 overload). */
    def initWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        config: McpConfig
    )(routes: McpHandler[?, ?, ?]*)(f: McpClient => A < S)(using Frame): A < (S & Async & Scope & Abort[McpException | Closed]) =
        init(transport, clientInfo, capabilities, config)(routes*).map(f)

    // --- Unscoped init ---

    /** Initialises a client using `routes` and `McpConfig.default` without a managed `Scope`. */
    def initUnscoped(
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        routes: McpHandler[?, ?, ?]*
    )(using Frame): McpClient < (Async & Abort[McpException | Closed]) =
        initUnscoped(transport, clientInfo, capabilities, routes, McpConfig.default)

    /** Initialises a client from a `Seq` of routes without a managed `Scope`. */
    def initUnscoped(
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        routes: Seq[McpHandler[?, ?, ?]],
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
    )(routes: McpHandler[?, ?, ?]*)(using Frame): McpClient < (Async & Abort[McpException | Closed]) =
        initUnscoped(transport, clientInfo, capabilities, routes, config)

    /** Initialises an unscoped client and immediately applies `f`. */
    def initUnscopedWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        routes: McpHandler[?, ?, ?]*
    )(f: McpClient => A < S)(using Frame): A < (S & Async & Abort[McpException | Closed]) =
        initUnscoped(transport, clientInfo, capabilities, routes*).map(f)

    /** Initialises an unscoped client with `config` and immediately applies `f` (curried W2 overload). */
    def initUnscopedWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        config: McpConfig
    )(routes: McpHandler[?, ?, ?]*)(f: McpClient => A < S)(using Frame): A < (S & Async & Abort[McpException | Closed]) =
        initUnscoped(transport, clientInfo, capabilities, config)(routes*).map(f)

end McpClient
