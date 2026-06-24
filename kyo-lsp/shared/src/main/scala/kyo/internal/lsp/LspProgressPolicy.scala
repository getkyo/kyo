package kyo.internal.lsp

import kyo.*

/** LSP-specific `JsonRpcProgressPolicy` installation for `$/progress`.
  *
  * LSP progress tokens live at:
  *   - `params.token` on inbound `$/progress` notifications.
  *   - `params.workDoneToken` on request params (for work-done progress).
  *   - `params.partialResultToken` on request params (for partial-result streaming).
  *
  * The policy extracts `workDoneToken` first; if absent, falls back to `partialResultToken`.
  * This mirrors the LSP 3.17 spec §3.15 where both token types use the same `$/progress`
  * notification channel but represent different progress flavors.
  *
  * `enforceMonotonic = false` because the LSP spec does not mandate monotonically increasing
  * percentage values.
  */
private[kyo] object LspProgressPolicy:

    // Inbound $/progress: token is at top-level params.token
    private val extractInbound: Structure.Value => (Maybe[Structure.Value] < Sync) =
        paramsValue => JsonRpcProgressPolicy.field(paramsValue, "token")

    // Request params: prefer workDoneToken, fall back to partialResultToken
    private val extractRequest: Structure.Value => (Maybe[Structure.Value] < Sync) =
        paramsValue =>
            JsonRpcProgressPolicy.field(paramsValue, "workDoneToken") match
                case Present(v) => Present(v)
                case Absent     => JsonRpcProgressPolicy.field(paramsValue, "partialResultToken")

    // Stamp outbound token: adds workDoneToken to outbound request params
    private val stampOutbound: (Structure.Value, Structure.Value) => (Structure.Value < Sync) =
        (params, token) =>
            params match
                case Structure.Value.Record(fs) =>
                    Structure.Value.Record(fs.filterNot(_._1 == "workDoneToken") :+ ("workDoneToken" -> token))
                case _ =>
                    Structure.Value.Record(Chunk("workDoneToken" -> token))

    // Encode $/progress notification params: { token, value }
    private val encodeProgress: (Structure.Value, Structure.Value) => (Structure.Value < Sync) =
        (token, value) =>
            value match
                case Structure.Value.Record(fs) =>
                    Structure.Value.Record(Chunk("token" -> token) ++ fs)
                case other =>
                    Structure.Value.Record(Chunk("token" -> token, "value" -> other))

    // Extract progress value: strip token, return rest
    private val extractProgress: Structure.Value => (Maybe[Structure.Value] < Sync) =
        params =>
            params match
                case Structure.Value.Record(fs) =>
                    Present(Structure.Value.Record(fs.filterNot(_._1 == "token")))
                case _ => Absent

    val default: JsonRpcProgressPolicy =
        JsonRpcProgressPolicy(
            progressMethod = "$/progress",
            extractInboundToken = extractInbound,
            extractRequestToken = extractRequest,
            stampOutboundToken = stampOutbound,
            encodeProgressParams = encodeProgress,
            extractProgressValue = extractProgress,
            enforceMonotonic = false
        )

end LspProgressPolicy
