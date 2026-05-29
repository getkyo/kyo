// flow-allow: PUBLIC config-policy type referenced by JsonRpcEndpoint.Config.cancellation field
package kyo

import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Structure
import kyo.Sync

final case class CancellationPolicy(
    cancelMethod: String,
    encodeParams: CancellationPolicy.ParamsEncoder,
    decodeParams: CancellationPolicy.ParamsDecoder,
    expectReplyForCancelledRequest: Boolean,
    cancelledError: Maybe[JsonRpcError],
    protectedMethods: Set[String]
) derives CanEqual

object CancellationPolicy:
    type ParamsEncoder = (JsonRpcId, Maybe[String]) => Frame ?=> Structure.Value < Sync
    type ParamsDecoder = Structure.Value => Frame ?=> Maybe[JsonRpcId] < Sync

    private case class LspCancelParams(id: JsonRpcId) derives Schema, CanEqual
    private case class McpCancelParams(requestId: JsonRpcId, reason: Maybe[String]) derives Schema, CanEqual

    // flow-allow: annotation pins the public ParamsEncoder type alias so the lambda matches the case-class constructor field type
    private val lspEncoder: ParamsEncoder =
        (id, _) =>
            f ?=>
                Sync.defer(Structure.encode(LspCancelParams(id)))(using f)

    // flow-allow: annotation pins the public ParamsEncoder type alias so the lambda matches the case-class constructor field type
    private val mcpEncoder: ParamsEncoder =
        (id, reason) =>
            f ?=>
                Sync.defer(Structure.encode(McpCancelParams(id, reason)))(using f)

    // flow-allow: annotation pins the public ParamsDecoder type alias so the lambda matches the case-class constructor field type
    private val lspDecoder: ParamsDecoder =
        sv =>
            f ?=>
                Sync.defer {
                    Structure.decode[LspCancelParams](sv)(using summon[Schema[LspCancelParams]], f) match
                        case Result.Success(p) => Present(p.id)
                        case _                 => Absent
                }(using f)

    // flow-allow: annotation pins the public ParamsDecoder type alias so the lambda matches the case-class constructor field type
    private val mcpDecoder: ParamsDecoder =
        sv =>
            f ?=>
                Sync.defer {
                    Structure.decode[McpCancelParams](sv)(using summon[Schema[McpCancelParams]], f) match
                        case Result.Success(p) => Present(p.requestId)
                        case _                 => Absent
                }(using f)

    val lsp: CancellationPolicy = CancellationPolicy(
        cancelMethod = "$/cancelRequest",
        encodeParams = lspEncoder,
        decodeParams = lspDecoder,
        expectReplyForCancelledRequest = true,
        cancelledError = Present(JsonRpcError.RequestCancelled),
        protectedMethods = Set.empty
    )

    val mcp: CancellationPolicy = CancellationPolicy(
        cancelMethod = "notifications/cancelled",
        encodeParams = mcpEncoder,
        decodeParams = mcpDecoder,
        expectReplyForCancelledRequest = false,
        cancelledError = Absent,
        protectedMethods = Set("initialize")
    )
end CancellationPolicy
