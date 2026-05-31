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
      * Mcp.server.flatMap(_.requestSampling(req))
      * ```
      */
    def server(using Frame): McpServer < Sync = ctx("server").map(_.server)

    /** Reports an MCP-shaped progress notification keyed on the `_meta.progressToken` the client
      * supplied. Silently dropped when the client did not supply a token.
      */
    def progress(
        progress: Double,
        total: Maybe[Double] = Absent,
        message: Maybe[String] = Absent
    )(using Frame): Unit < (Async & Abort[Closed]) =
        ctx("progress").flatMap(c => internal.mcp.McpProgressPolicy.report(c.jsonRpc, progress, total, message))

    /** The JSON-RPC id of the inbound request (`Absent` for notifications). */
    def requestId(using Frame): Maybe[JsonRpcId] < Sync = ctx("requestId").map(_.jsonRpc.requestId)

    /** Promise that completes when the peer sends a cancellation for the current request. */
    def cancelled(using Frame): Fiber.Promise[Unit, Sync] < Sync = ctx("cancelled").map(_.jsonRpc.cancelled)

    /** Protocol-specific extra fields from the inbound envelope, if any. */
    def extras(using Frame): Maybe[Structure.Value] < Sync = ctx("extras").map(_.jsonRpc.extras)

end Mcp
