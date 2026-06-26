package kyo

/** Client-side reverse-direction handler: the client's answer to a request or notification
  * the server sends it.
  *
  * Distinct from [[McpHandler]] (the server-side carrier), so a server-side `tool` / `resource`
  * / `prompt` handler does not type-check on `McpClient.init`. Construct values via the
  * companion factories, each of which bakes in the wire method string and the `(In, Out)`
  * types so a wrong method or response type cannot be passed:
  *
  *   - [[McpClientHandler.onSampling]]        ; answers `sampling/createMessage`
  *   - [[McpClientHandler.onElicitation]]     ; answers `elicitation/create`
  *   - [[McpClientHandler.onRoots]]           ; answers `roots/list` (returns `Chunk[Root]`; the engine builds the envelope)
  *   - [[McpClientHandler.onNotification]]    ; a fire-and-forget notification sink (no reply)
  *   - [[McpClientHandler.onLog]] / [[McpClientHandler.onResourceUpdated]] ; typed notification sinks over [[McpNotification]]
  *   - [[McpClientHandler.customClient]]      ; the magic-string escape hatch for vendor reverse-direction methods
  *
  * @tparam In  the request (or notification) parameter type
  * @tparam Out the response payload type (`Unit` for a notification sink)
  * @tparam E   the union of user-registered domain error types
  */
sealed trait McpClientHandler[In, Out, +E]:
    def method: String
    def direction: McpHandler.Direction
    private[kyo] def toRoute(serverRef: AtomicRef[Maybe[McpServer.Unsafe]])(using Frame): JsonRpcRoute[?, ?, ?]
    private[kyo] def requiredCapability: Maybe[McpCapabilities.Name]
end McpClientHandler

object McpClientHandler:

    final private[kyo] class RequestCarrier[In, Out, +E](
        val method: String,
        val requiredCapability: Maybe[McpCapabilities.Name],
        val inSchema: Schema[In],
        val outSchema: Schema[Out],
        val handler: In => Out < (Async & Abort[JsonRpcResponse.Halt | E])
    ) extends McpClientHandler[In, Out, E]:
        def direction: McpHandler.Direction = McpHandler.Direction.ClientHandled
        private[kyo] def toRoute(serverRef: AtomicRef[Maybe[McpServer.Unsafe]])(using Frame): JsonRpcRoute[?, ?, ?] =
            internal.mcp.McpClientHandlerLift.liftRequest(this, serverRef)
    end RequestCarrier

    final private[kyo] class NotificationCarrier[In, +E](
        val method: String,
        val inSchema: Schema[In],
        val handler: In => Unit < (Async & Abort[JsonRpcResponse.Halt | E])
    ) extends McpClientHandler[In, Unit, E]:
        def direction: McpHandler.Direction                 = McpHandler.Direction.ClientHandled
        def requiredCapability: Maybe[McpCapabilities.Name] = Absent
        private[kyo] def toRoute(serverRef: AtomicRef[Maybe[McpServer.Unsafe]])(using Frame): JsonRpcRoute[?, ?, ?] =
            internal.mcp.McpClientHandlerLift.liftNotification(this, serverRef)
    end NotificationCarrier

    /** Answers `sampling/createMessage`. */
    def onSampling[E](
        handler: McpServer.SamplingRequest => McpServer.SamplingResponse < (Async & Abort[JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpClientHandler[McpServer.SamplingRequest, McpServer.SamplingResponse, E] =
        new RequestCarrier(
            "sampling/createMessage",
            Present(McpCapabilities.Name.Sampling),
            summon[Schema[McpServer.SamplingRequest]],
            summon[Schema[McpServer.SamplingResponse]],
            handler
        )

    /** Answers `elicitation/create`. */
    def onElicitation[E](
        handler: McpServer.ElicitationRequest => McpServer.ElicitationResponse < (Async & Abort[JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpClientHandler[McpServer.ElicitationRequest, McpServer.ElicitationResponse, E] =
        new RequestCarrier(
            "elicitation/create",
            Present(McpCapabilities.Name.Elicitation),
            summon[Schema[McpServer.ElicitationRequest]],
            summon[Schema[McpServer.ElicitationResponse]],
            handler
        )

    /** Answers `roots/list`; returns `Chunk[Root]` and the engine builds the wire envelope. */
    def onRoots[E](
        handler: => Chunk[McpServer.Root] < (Async & Abort[JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpClientHandler[Unit, Chunk[McpServer.Root], E] =
        new RequestCarrier[Unit, Chunk[McpServer.Root], E](
            "roots/list",
            Present(McpCapabilities.Name.Roots),
            summon[Schema[Unit]],
            summon[Schema[Chunk[McpServer.Root]]],
            _ => handler
        )

    /** Answers `roots/list` with a fixed set of root URIs, one `Root` per URI. */
    def roots[E](uris: McpResourceUri*)(using Frame): McpClientHandler[Unit, Chunk[McpServer.Root], E] =
        onRoots(Chunk.from(uris.map(McpServer.Root(_))))

    /** A fire-and-forget notification sink. `Out` is structurally `Unit`: there is no
      * `Schema[Out]`, so a caller cannot register a reply-bearing route for a notification.
      */
    def onNotification[In](method: String)(using
        inSchema: Schema[In]
    )[E](
        handler: In => Unit < (Async & Abort[JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpClientHandler[In, Unit, E] =
        new NotificationCarrier(method, inSchema, handler)

    /** A typed `notifications/message` sink. */
    def onLog[E](
        handler: McpNotification.Log => Unit < (Async & Abort[JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpClientHandler[McpNotification.Log, Unit, E] =
        new NotificationCarrier("notifications/message", summon[Schema[McpNotification.Log]], handler)

    /** A typed `notifications/resources/updated` sink. */
    def onResourceUpdated[E](
        handler: McpNotification.ResourceUpdated => Unit < (Async & Abort[JsonRpcResponse.Halt | E])
    )(using frame: Frame): McpClientHandler[McpNotification.ResourceUpdated, Unit, E] =
        new NotificationCarrier("notifications/resources/updated", summon[Schema[McpNotification.ResourceUpdated]], handler)

    /** The client-side magic-string escape hatch (the reverse analogue of `McpHandler.custom`). */
    def customClient[In](method: String)(using
        inSchema: Schema[In]
    )[Out, E](
        handler: In => Out < (Async & Abort[JsonRpcResponse.Halt | E])
    )(using outSchema: Schema[Out], frame: Frame): McpClientHandler[In, Out, E] =
        new RequestCarrier(method, Absent, inSchema, outSchema, handler)

end McpClientHandler
