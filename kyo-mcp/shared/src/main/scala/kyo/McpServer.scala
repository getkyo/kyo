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
        def requestSamplingUnsafe(req: McpSamplingRequest)(using Frame): McpSamplingResponse < (Async & Abort[McpError | Closed]) =
            throw new NotImplementedError("McpServer.requestSamplingUnsafe stub: body filled in Phase 5")
        def requestRootsUnsafe(using Frame): Chunk[McpRoot] < (Async & Abort[McpError | Closed]) =
            throw new NotImplementedError("McpServer.requestRootsUnsafe stub: body filled in Phase 5")
        def requestElicitationUnsafe(req: McpElicitationRequest)(using Frame): McpElicitationResponse < (Async & Abort[McpError | Closed]) =
            throw new NotImplementedError("McpServer.requestElicitationUnsafe stub: body filled in Phase 5")
        def notifyToolsListChangedUnsafe(using Frame): Unit < (Async & Abort[Closed]) =
            throw new NotImplementedError("McpServer.notifyToolsListChangedUnsafe stub: body filled in Phase 5")
        def notifyResourcesListChangedUnsafe(using Frame): Unit < (Async & Abort[Closed]) =
            throw new NotImplementedError("McpServer.notifyResourcesListChangedUnsafe stub: body filled in Phase 5")
        def notifyResourceUpdatedUnsafe(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[Closed]) =
            throw new NotImplementedError("McpServer.notifyResourceUpdatedUnsafe stub: body filled in Phase 5")
        def notifyPromptsListChangedUnsafe(using Frame): Unit < (Async & Abort[Closed]) =
            throw new NotImplementedError("McpServer.notifyPromptsListChangedUnsafe stub: body filled in Phase 5")
        def notifyLogUnsafe[T](level: McpLogLevel, data: T, logger: Maybe[String])(using Frame, Schema[T]): Unit < (Async & Abort[Closed]) =
            throw new NotImplementedError("McpServer.notifyLogUnsafe stub: body filled in Phase 5")
        def protocolVersionUnsafe: Maybe[McpProtocolVersion] =
            throw new NotImplementedError("McpServer.protocolVersionUnsafe stub: body filled in Phase 5")
        def clientCapabilitiesUnsafe: Maybe[McpCapabilities.Client] =
            throw new NotImplementedError("McpServer.clientCapabilitiesUnsafe stub: body filled in Phase 5")
        def clientInfoUnsafe: Maybe[McpInfo] =
            throw new NotImplementedError("McpServer.clientInfoUnsafe stub: body filled in Phase 5")
        def underlyingUnsafe: JsonRpcHandler =
            throw new NotImplementedError("McpServer.underlyingUnsafe stub: body filled in Phase 5")
        def awaitDrainUnsafe(using Frame): Unit < Async =
            throw new NotImplementedError("McpServer.awaitDrainUnsafe stub: body filled in Phase 5")
        def closeUnsafe(gracePeriod: Duration)(using Frame): Unit < Async =
            throw new NotImplementedError("McpServer.closeUnsafe stub: body filled in Phase 5")
        final def safe: McpServer = this
    end Unsafe

    // --- Scoped init quartet ---

    def init(transport: JsonRpcTransport, routes: McpRoute[?, ?, ?]*)(using Frame): McpServer < (Async & Scope) =
        throw new NotImplementedError("McpServer.init stub: body filled in Phase 5")

    def init(transport: JsonRpcTransport, config: McpConfig)(routes: McpRoute[?, ?, ?]*)(using Frame): McpServer < (Async & Scope) =
        throw new NotImplementedError("McpServer.init(config) stub: body filled in Phase 5")

    def init(
        transport: JsonRpcTransport,
        routes: Seq[McpRoute[?, ?, ?]],
        config: McpConfig = McpConfig.default
    )(using Frame): McpServer < (Async & Scope) =
        throw new NotImplementedError("McpServer.init(seq, config) stub: body filled in Phase 5")

    def initWith[A, S](transport: JsonRpcTransport, routes: McpRoute[?, ?, ?]*)(f: McpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        throw new NotImplementedError("McpServer.initWith stub: body filled in Phase 5")

    def initWith[A, S](transport: JsonRpcTransport, config: McpConfig)(routes: McpRoute[?, ?, ?]*)(f: McpServer => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        throw new NotImplementedError("McpServer.initWith(config) stub: body filled in Phase 5")

    // --- Unscoped init ---

    def initUnscoped(transport: JsonRpcTransport, routes: McpRoute[?, ?, ?]*)(using Frame): McpServer < Async =
        throw new NotImplementedError("McpServer.initUnscoped stub: body filled in Phase 5")

    def initUnscoped(transport: JsonRpcTransport, config: McpConfig)(routes: McpRoute[?, ?, ?]*)(using Frame): McpServer < Async =
        throw new NotImplementedError("McpServer.initUnscoped(config) stub: body filled in Phase 5")

    def initUnscoped(
        transport: JsonRpcTransport,
        routes: Seq[McpRoute[?, ?, ?]],
        config: McpConfig = McpConfig.default
    )(using Frame): McpServer < Async =
        throw new NotImplementedError("McpServer.initUnscoped(seq, config) stub: body filled in Phase 5")

    def initUnscopedWith[A, S](transport: JsonRpcTransport, routes: McpRoute[?, ?, ?]*)(f: McpServer => A < S)(using
        Frame
    ): A < (S & Async) =
        throw new NotImplementedError("McpServer.initUnscopedWith stub: body filled in Phase 5")

    def initUnscopedWith[A, S](transport: JsonRpcTransport, config: McpConfig)(routes: McpRoute[?, ?, ?]*)(f: McpServer => A < S)(using
        Frame
    ): A < (S & Async) =
        throw new NotImplementedError("McpServer.initUnscopedWith(config) stub: body filled in Phase 5")

end McpServer
