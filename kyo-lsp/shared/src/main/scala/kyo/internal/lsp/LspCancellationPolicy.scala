package kyo.internal.lsp

import kyo.*

/** LSP-specific `JsonRpcCancellationPolicy` for `$/cancelRequest`.
  *
  * LSP uses `$/cancelRequest` with a `params.id` field (integer or string). The key
  * divergence from MCP is that `expectReplyForCancelledRequest = true` (INV-031): when a
  * request is cancelled via `$/cancelRequest`, the server MUST reply with a JSON-RPC error
  * response with code -32800 ("Request cancelled"). MCP sets this to `false` (fire-and-forget).
  *
  * The `initialize` method is protected from cancellation per the LSP spec.
  *
  * Per INV-031 and INV-008.
  */
private[kyo] object LspCancellationPolicy:

    val default: JsonRpcCancellationPolicy =
        JsonRpcCancellationPolicy(
            cancelMethod = "$/cancelRequest",
            encodeParams = (id, reason) =>
                Sync.defer {
                    val idField = "id" -> id.fold(
                        n => Structure.Value.Integer(n),
                        s => Structure.Value.Str(s)
                    )
                    val fields = reason match
                        case Present(r) => Chunk(idField, "reason" -> Structure.Value.Str(r))
                        case Absent     => Chunk(idField)
                    Structure.Value.Record(fields)
                },
            decodeParams = sv =>
                Sync.defer {
                    sv match
                        case Structure.Value.Record(fields) =>
                            fields.iterator.collectFirst {
                                case (k, v) if k == "id" =>
                                    v match
                                        case Structure.Value.Integer(n) => Present(JsonRpcId(n))
                                        case Structure.Value.Str(s)     => Present(JsonRpcId(s))
                                        case _                          => Absent
                            }.getOrElse(Absent)
                        case _ => Absent
                },
            // LSP requires the server to reply to a cancelled request with -32800 (INV-031).
            expectReplyForCancelledRequest = true,
            cancelledError = Absent,
            protectedMethods = Set("initialize")
        )

end LspCancellationPolicy
