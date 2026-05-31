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
        def listTools(cursor: Maybe[String] = Absent)(using Frame): McpPage[McpRoute.ToolMeta] < (Async & Abort[McpError | Closed]) =
            self.listToolsUnsafe(cursor)

        /** Calls a tool with a typed argument; returns the raw `ToolCallResult`.
          * Audit-C2: `using` clause order is `(Frame, Schema[In])` per CONTRIBUTING.md:349-351.
          */
        def callTool[In](name: String, arguments: In)(using
            Frame,
            Schema[In]
        ): McpRoute.ToolCallResult < (Async & Abort[McpError | Closed]) =
            self.callToolUnsafe[In](name, arguments)

        /** Calls a tool with typed argument and typed output; aborts with `McpToolStructuredMissingError`
          * when `structuredContent` is `Absent` (Audit-A9 / INV-027).
          * Audit-C2: `using` clause order is `(Frame, Schema[In], Schema[Out])`.
          */
        def callTool[In, Out](name: String, arguments: In)(using Frame, Schema[In], Schema[Out]): Out < (Async & Abort[McpError | Closed]) =
            self.callToolTypedUnsafe[In, Out](name, arguments)

        /** Lists the server's resources. Returns a named `McpPage` record (Audit-A3). */
        def listResources(cursor: Maybe[String] = Absent)(using
            Frame
        ): McpPage[McpRoute.ResourceMeta] < (Async & Abort[McpError | Closed]) =
            self.listResourcesUnsafe(cursor)

        /** Lists the server's resource templates. Returns a named `McpPage` record. */
        def listResourceTemplates(cursor: Maybe[String] = Absent)(using
            Frame
        ): McpPage[McpRoute.ResourceTemplateMeta] < (Async & Abort[McpError | Closed]) =
            self.listResourceTemplatesUnsafe(cursor)

        /** Reads a resource by URI.
          * INV-022: `uri` is typed `McpResourceUri`, not raw `String`.
          */
        def readResource(uri: McpResourceUri)(using Frame): Chunk[McpResourceContents] < (Async & Abort[McpError | Closed]) =
            self.readResourceUnsafe(uri)

        /** Lists the server's prompts. Returns a named `McpPage` record (Audit-A3). */
        def listPrompts(cursor: Maybe[String] = Absent)(using Frame): McpPage[McpRoute.PromptMeta] < (Async & Abort[McpError | Closed]) =
            self.listPromptsUnsafe(cursor)

        /** Fetches a prompt by name with optional arguments. */
        def getPrompt(name: String, arguments: Map[String, String] = Map.empty)(using
            Frame
        ): McpRoute.PromptGetResult < (Async & Abort[McpError | Closed]) =
            self.getPromptUnsafe(name, arguments)

        /** Sets the minimum log level the client wishes to receive. */
        def setLogLevel(level: McpLogLevel)(using Frame): Unit < (Async & Abort[McpError | Closed]) =
            self.setLogLevelUnsafe(level)

        /** Requests a completion suggestion from the server. */
        def complete(ref: McpRoute.CompletionRef, arg: McpRoute.CompletionArg)(using
            Frame
        ): McpRoute.CompletionResult < (Async & Abort[McpError | Closed]) =
            self.completeUnsafe(ref, arg)

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

        /** Returns the underlying Unsafe handle. */
        def unsafe: Unsafe = self

    end extension

    abstract class Unsafe:
        def listToolsUnsafe(cursor: Maybe[String])(using Frame): McpPage[McpRoute.ToolMeta] < (Async & Abort[McpError | Closed]) =
            throw new NotImplementedError("McpClient.listToolsUnsafe stub: body filled in Phase 6")
        def callToolUnsafe[In](name: String, arguments: In)(using
            Frame,
            Schema[In]
        ): McpRoute.ToolCallResult < (Async & Abort[McpError | Closed]) =
            throw new NotImplementedError("McpClient.callToolUnsafe stub: body filled in Phase 6")
        def callToolTypedUnsafe[In, Out](name: String, arguments: In)(using
            Frame,
            Schema[In],
            Schema[Out]
        ): Out < (Async & Abort[McpError | Closed]) =
            throw new NotImplementedError("McpClient.callToolTypedUnsafe stub: body filled in Phase 6")
        def listResourcesUnsafe(cursor: Maybe[String])(using Frame): McpPage[McpRoute.ResourceMeta] < (Async & Abort[McpError | Closed]) =
            throw new NotImplementedError("McpClient.listResourcesUnsafe stub: body filled in Phase 6")
        def listResourceTemplatesUnsafe(cursor: Maybe[String])(using
            Frame
        ): McpPage[McpRoute.ResourceTemplateMeta] < (Async & Abort[McpError | Closed]) =
            throw new NotImplementedError("McpClient.listResourceTemplatesUnsafe stub: body filled in Phase 6")
        def readResourceUnsafe(uri: McpResourceUri)(using Frame): Chunk[McpResourceContents] < (Async & Abort[McpError | Closed]) =
            throw new NotImplementedError("McpClient.readResourceUnsafe stub: body filled in Phase 6")
        def listPromptsUnsafe(cursor: Maybe[String])(using Frame): McpPage[McpRoute.PromptMeta] < (Async & Abort[McpError | Closed]) =
            throw new NotImplementedError("McpClient.listPromptsUnsafe stub: body filled in Phase 6")
        def getPromptUnsafe(name: String, arguments: Map[String, String])(using
            Frame
        ): McpRoute.PromptGetResult < (Async & Abort[McpError | Closed]) =
            throw new NotImplementedError("McpClient.getPromptUnsafe stub: body filled in Phase 6")
        def setLogLevelUnsafe(level: McpLogLevel)(using Frame): Unit < (Async & Abort[McpError | Closed]) =
            throw new NotImplementedError("McpClient.setLogLevelUnsafe stub: body filled in Phase 6")
        def completeUnsafe(ref: McpRoute.CompletionRef, arg: McpRoute.CompletionArg)(using
            Frame
        ): McpRoute.CompletionResult < (Async & Abort[McpError | Closed]) =
            throw new NotImplementedError("McpClient.completeUnsafe stub: body filled in Phase 6")
        def notifyRootsListChangedUnsafe(using Frame): Unit < (Async & Abort[Closed]) =
            throw new NotImplementedError("McpClient.notifyRootsListChangedUnsafe stub: body filled in Phase 6")
        def serverCapabilitiesUnsafe: Maybe[McpCapabilities.Server] =
            throw new NotImplementedError("McpClient.serverCapabilitiesUnsafe stub: body filled in Phase 6")
        def serverInfoUnsafe: Maybe[McpInfo] =
            throw new NotImplementedError("McpClient.serverInfoUnsafe stub: body filled in Phase 6")
        def protocolVersionUnsafe: Maybe[McpProtocolVersion] =
            throw new NotImplementedError("McpClient.protocolVersionUnsafe stub: body filled in Phase 6")
        def underlyingUnsafe: JsonRpcHandler =
            throw new NotImplementedError("McpClient.underlyingUnsafe stub: body filled in Phase 6")
        def closeUnsafe(gracePeriod: Duration)(using Frame): Unit < Async =
            throw new NotImplementedError("McpClient.closeUnsafe stub: body filled in Phase 6")
        final def safe: McpClient = this
    end Unsafe

    // --- Scoped init quartet (INV-014: parameter order = transport, clientInfo, capabilities, routes*) ---

    def init(transport: JsonRpcTransport, clientInfo: McpInfo, capabilities: McpCapabilities.Client, routes: McpRoute[?, ?, ?]*)(using
        Frame
    ): McpClient < (Async & Scope) =
        throw new NotImplementedError("McpClient.init stub: body filled in Phase 6")

    def init(
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        routes: Seq[McpRoute[?, ?, ?]],
        config: McpConfig = McpConfig.default
    )(using Frame): McpClient < (Async & Scope) =
        throw new NotImplementedError("McpClient.init(seq, config) stub: body filled in Phase 6")

    def initWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        routes: McpRoute[?, ?, ?]*
    )(f: McpClient => A < S)(using Frame): A < (S & Async & Scope) =
        throw new NotImplementedError("McpClient.initWith stub: body filled in Phase 6")

    // --- Unscoped init ---

    def initUnscoped(
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        routes: McpRoute[?, ?, ?]*
    )(using Frame): McpClient < Async =
        throw new NotImplementedError("McpClient.initUnscoped stub: body filled in Phase 6")

    def initUnscopedWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        routes: McpRoute[?, ?, ?]*
    )(f: McpClient => A < S)(using Frame): A < (S & Async) =
        throw new NotImplementedError("McpClient.initUnscopedWith stub: body filled in Phase 6")

end McpClient
