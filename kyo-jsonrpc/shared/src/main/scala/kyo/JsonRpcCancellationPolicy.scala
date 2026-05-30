package kyo

/** Configures how the endpoint sends and receives request cancellation notifications.
  *
  * A `JsonRpcCancellationPolicy` specifies the method name, parameter encoding/decoding, whether the
  * cancelled request still expects a reply, and which methods are protected from cancellation.
  *
  * Two preset policies cover the major protocols:
  *  - [[JsonRpcCancellationPolicy.lsp]]: LSP `$/cancelRequest` with `{"id": ...}` params; cancelled
  *    requests still produce a `MethodNotFound` reply.
  *  - [[JsonRpcCancellationPolicy.mcp]]: MCP `notifications/cancelled` with `{"requestId": ...}`; no
  *    reply is expected for cancelled requests; `initialize` is protected.
  *
  * Set via [[JsonRpcHandler.Config.cancellation]].
  *
  * @see [[JsonRpcHandler.Config]]
  */
final case class JsonRpcCancellationPolicy(
    cancelMethod: String,
    encodeParams: JsonRpcCancellationPolicy.ParamsEncoder,
    decodeParams: JsonRpcCancellationPolicy.ParamsDecoder,
    expectReplyForCancelledRequest: Boolean,
    cancelledError: Maybe[JsonRpcError],
    protectedMethods: Set[String]
) derives CanEqual

object JsonRpcCancellationPolicy:
    private type ParamsEncoder = (JsonRpcId, Maybe[String]) => Frame ?=> Structure.Value < Sync
    private type ParamsDecoder = Structure.Value => Frame ?=> Maybe[JsonRpcId] < Sync

    private case class LspCancelParams(id: JsonRpcId) derives Schema, CanEqual
    private case class McpCancelParams(requestId: JsonRpcId, reason: Maybe[String]) derives Schema, CanEqual

    // annotation pins the private ParamsEncoder type so the lambda matches the case-class constructor field type
    private val lspEncoder: ParamsEncoder =
        (id, _) =>
            f ?=>
                Sync.defer(Structure.encode(LspCancelParams(id)))(using f)

    // annotation pins the private ParamsEncoder type so the lambda matches the case-class constructor field type
    private val mcpEncoder: ParamsEncoder =
        (id, reason) =>
            f ?=>
                Sync.defer(Structure.encode(McpCancelParams(id, reason)))(using f)

    // annotation pins the private ParamsDecoder type so the lambda matches the case-class constructor field type
    private val lspDecoder: ParamsDecoder =
        sv =>
            f ?=>
                Sync.defer {
                    Structure.decode[LspCancelParams](sv)(using summon[Schema[LspCancelParams]], f) match
                        case Result.Success(p) => Present(p.id)
                        case _                 => Absent
                }(using f)

    // annotation pins the private ParamsDecoder type so the lambda matches the case-class constructor field type
    private val mcpDecoder: ParamsDecoder =
        sv =>
            f ?=>
                Sync.defer {
                    Structure.decode[McpCancelParams](sv)(using summon[Schema[McpCancelParams]], f) match
                        case Result.Success(p) => Present(p.requestId)
                        case _                 => Absent
                }(using f)

    val lsp: JsonRpcCancellationPolicy = JsonRpcCancellationPolicy(
        cancelMethod = "$/cancelRequest",
        encodeParams = lspEncoder,
        decodeParams = lspDecoder,
        expectReplyForCancelledRequest = true,
        cancelledError = Present(JsonRpcCustomError(-32800, "Request cancelled")(using Frame.internal)),
        protectedMethods = Set.empty
    )

    val mcp: JsonRpcCancellationPolicy = JsonRpcCancellationPolicy(
        cancelMethod = "notifications/cancelled",
        encodeParams = mcpEncoder,
        decodeParams = mcpDecoder,
        expectReplyForCancelledRequest = false,
        cancelledError = Absent,
        protectedMethods = Set("initialize")
    )
end JsonRpcCancellationPolicy
