package kyo.internal.mcp

import kyo.*

/** MCP-specific progress policy adapter for kyo-jsonrpc.
  *
  * MCP uses `notifications/progress` as the progress notification method. The progress token lives
  * at `params._meta.progressToken` on the request side, and at top-level
  * `params.progressToken` on the inbound progress notification side. Monotonic enforcement is
  * enabled so regressing progress values are silently dropped.
  */
private[kyo] object McpProgressPolicy:

    private def stampToken(params: Structure.Value, token: Structure.Value): Structure.Value =
        val metaWithToken = Structure.Value.Record(Chunk("progressToken" -> token))
        params match
            case Structure.Value.Record(fs) =>
                val newMeta = Maybe.fromOption(fs.iterator.collectFirst { case (k, v) if k == "_meta" => v }) match
                    case Present(prev) => JsonRpcProgressPolicy.merge(prev, metaWithToken)
                    case Absent        => metaWithToken
                Structure.Value.Record(fs.filterNot(_._1 == "_meta") :+ ("_meta" -> newMeta))
            case _ =>
                Structure.Value.Record(Chunk("_meta" -> metaWithToken))
        end match
    end stampToken

    private def encodeProgress(token: Structure.Value, value: Structure.Value): Structure.Value =
        value match
            case Structure.Value.Record(fs) =>
                Structure.Value.Record(Chunk("progressToken" -> token) ++ fs)
            case other =>
                Structure.Value.Record(Chunk("progressToken" -> token, "progress" -> other))
        end match
    end encodeProgress

    private def extractProgress(params: Structure.Value): Maybe[Structure.Value] =
        params match
            case Structure.Value.Record(fs) =>
                Present(Structure.Value.Record(fs.filterNot(_._1 == "progressToken")))
            case _ => Absent
        end match
    end extractProgress

    val default: JsonRpcProgressPolicy =
        JsonRpcProgressPolicy(
            progressMethod = "notifications/progress",
            // Progress notification: token is at top-level params.progressToken
            extractInboundToken = paramsValue =>
                JsonRpcProgressPolicy.field(paramsValue, "progressToken"),
            // Request: token is at params._meta.progressToken
            extractRequestToken = paramsValue =>
                JsonRpcProgressPolicy.field(paramsValue, "_meta")
                    .flatMap(meta => JsonRpcProgressPolicy.field(meta, "progressToken")),
            stampOutboundToken = (params, token) => stampToken(params, token),
            encodeProgressParams = (token, value) => encodeProgress(token, value),
            extractProgressValue = params => extractProgress(params),
            enforceMonotonic = true
        )

    /** Reports a progress notification via the `JsonRpcRoute.Context` progress sink.
      *
      * Builds the MCP progress payload `{ progress, total?, message? }` and invokes
      * `ctx.progress(value)` which sends it through the configured `JsonRpcProgressPolicy`.
      * If the context has no progress sink, this is a no-op.
      */
    def report(
        ctx: JsonRpcRoute.Context,
        progress: Double,
        total: Maybe[Double],
        message: Maybe[String]
    )(using Frame): Unit < (Async & Abort[Closed]) =
        val progressField = Chunk(("progress", Structure.Value.Decimal(progress)))
        val totalFields   = total.fold(Chunk.empty[(String, Structure.Value)])(t => Chunk(("total", Structure.Value.Decimal(t))))
        val msgFields     = message.fold(Chunk.empty[(String, Structure.Value)])(m => Chunk(("message", Structure.Value.Str(m))))
        val fields        = progressField ++ totalFields ++ msgFields
        ctx.progress(Structure.Value.Record(fields))
    end report

end McpProgressPolicy
