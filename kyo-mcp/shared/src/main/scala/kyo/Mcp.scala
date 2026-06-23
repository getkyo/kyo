package kyo

/** Handler-side accessors for the per-request MCP context.
  *
  * The engine binds the per-request `(JsonRpcRoute.Context, McpServer)` pair into a `Local` for
  * the lifetime of a single handler invocation. Handler code reaches into that local through the
  * accessors here:
  *
  *   - `Mcp.server`        ; the live `McpServer` for reverse-direction calls
  *   - `Mcp.progress(...)` ; emits an MCP-shaped progress notification
  *   - `Mcp.requestId`     ; the JSON-RPC id of the inbound request
  *   - `Mcp.cancelled`     ; the promise that completes when the peer cancels this request
  *   - `Mcp.extras`        ; protocol-specific extra fields from the inbound envelope
  *
  * Calling any of these outside an active route-handler invocation raises an
  * `IllegalStateException` (surfaced as a kernel panic). This is a programmer-error path, not a
  * domain failure, so no typed `Abort` is added to the signature.
  */
object Mcp:

    /** The pair of per-request context fields the engine binds for each dispatch. */
    final private[kyo] case class RequestContext(jsonRpc: JsonRpcRoute.Context, server: McpServer)

    /** Local holding the current request context. The engine sets `Present(ctx)` for the duration
      * of a single handler invocation; outside that scope it is `Absent`.
      */
    private[kyo] val local: Local[Maybe[RequestContext]] = Local.init(Absent)

    private def ctx(method: String)(using Frame): RequestContext < Sync =
        local.use {
            case Present(c) => Sync.defer(c)
            case Absent =>
                Sync.defer(throw new IllegalStateException(s"Mcp.$method called outside an MCP route handler"))
        }

    /** Returns the live [[McpServer]] handle for reverse-direction calls.
      *
      * Inside a route handler:
      * ```
      * Mcp.server.map(_.requestSampling(request))
      * ```
      */
    def server(using Frame): McpServer < Sync = ctx("server").map(_.server)

    /** The live [[McpServer]] if called within a route-handler dynamic extent, else `Absent`.
      * The total safe path; prefer it over [[server]], which panics outside a handler.
      */
    def serverMaybe(using Frame): Maybe[McpServer] < Sync =
        local.use {
            case Present(c) => Sync.defer(Present(c.server))
            case Absent     => Sync.defer(Absent)
        }

    /** Sends `sampling/createMessage` to the connected client without exposing the handle
      * (so it cannot leak into a detached fiber). Valid only within a handler; panics otherwise.
      */
    def requestSampling(request: McpServer.SamplingRequest)(using
        Frame
    ): McpServer.SamplingResponse < (Async & Abort[McpRequestSamplingFailure]) =
        ctx("requestSampling").flatMap(_.server.requestSampling(request))

    /** Sends `elicitation/create` to the connected client without exposing the handle. */
    def requestElicitation(request: McpServer.ElicitationRequest)(using
        Frame
    ): McpServer.ElicitationResponse < (Async & Abort[McpRequestElicitationFailure]) =
        ctx("requestElicitation").flatMap(_.server.requestElicitation(request))

    /** Sends `roots/list` to the connected client without exposing the handle. */
    def requestRoots(using Frame): Chunk[McpServer.Root] < (Async & Abort[McpRequestRootsFailure]) =
        ctx("requestRoots").flatMap(_.server.requestRoots)

    /** The negotiated protocol version (non-`Maybe`: a handler body runs only post-handshake). */
    def protocolVersion(using Frame): McpConfig.ProtocolVersion < Sync =
        ctx("protocolVersion").map(_.server.protocolVersion.getOrElse(McpConfig.ProtocolVersion.current))

    /** The client's advertised capabilities (non-`Maybe`: post-handshake inside a handler). */
    def clientCapabilities(using Frame): McpCapabilities.Client < Sync =
        ctx("clientCapabilities").map(_.server.clientCapabilities.getOrElse(McpCapabilities.Client()))

    /** The client info (non-`Maybe`: post-handshake inside a handler). */
    def clientInfo(using Frame): McpInfo < Sync =
        ctx("clientInfo").map(_.server.clientInfo.getOrElse(McpInfo("unknown")))

    /** Reports an MCP-shaped progress notification keyed on the `_meta.progressToken` the client
      * supplied. Silently dropped when the client did not supply a token.
      */
    def progress(
        progress: Double,
        total: Maybe[Double] = Absent,
        message: Maybe[String] = Absent
    )(using Frame): Unit < (Async & Abort[McpConnectionClosedException]) =
        // Map the transport `Closed` from the progress sink to the typed connection leaf so no bare
        // `Closed` reaches the public progress row.
        Abort.recover[Closed](_ => Abort.fail(McpConnectionClosedException()))(
            ctx("progress").flatMap(c => internal.mcp.McpProgressPolicy.report(c.jsonRpc, progress, total, message))
        )

    /** The JSON-RPC id of the inbound request (`Absent` for notifications). */
    def requestId(using Frame): Maybe[JsonRpcId] < Sync = ctx("requestId").map(_.jsonRpc.requestId)

    /** Promise that completes when the peer sends a cancellation for the current request. */
    def cancelled(using Frame): Fiber.Promise[Unit, Sync] < Sync = ctx("cancelled").map(_.jsonRpc.cancelled)

    /** Protocol-specific extra fields from the inbound envelope, if any (raw storage form). */
    def extras(using Frame): Maybe[Structure.Value] < Sync = ctx("extras").map(_.jsonRpc.extras)

    /** Decodes the open `_meta` slot to `T`. `Absent` when no extras are present; a non-conforming
      * payload aborts `McpDecodeException` on an honestly-widened row (the decode can fail).
      */
    def extras[T](using Schema[T], Frame): Maybe[T] < (Sync & Abort[McpDecodeException]) =
        extras.map {
            case Absent => Maybe.empty
            case Present(sv) =>
                Structure.decode[T](sv) match
                    case Result.Success(t) => Present(t)
                    case Result.Failure(e) => Abort.fail(McpDecodeException("_meta", e.getMessage, Present(e)))
                    case Result.Panic(t)   => Abort.panic(t)
        }

end Mcp
