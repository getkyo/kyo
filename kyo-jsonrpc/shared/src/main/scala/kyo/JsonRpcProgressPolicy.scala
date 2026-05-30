package kyo

/** Configures how the endpoint reports and receives progress notifications during long-running
  * requests.
  *
  * A `JsonRpcProgressPolicy` captures the method name and a set of token-extraction and parameter
  * encoding/decoding functions specific to each protocol dialect.
  *
  * Two preset policies are provided:
  *  - [[JsonRpcProgressPolicy.lsp]]: LSP `$/progress` with `workDoneToken` in request params.
  *  - [[JsonRpcProgressPolicy.mcp]]: MCP `notifications/progress` with `_meta.progressToken`; enforces
  *    monotonic progress values.
  *
  * Set via [[JsonRpcHandler.Config.progress]].
  *
  * @see [[JsonRpcHandler.Config]]
  * @see [[JsonRpcRoute.Context]]
  */
final case class JsonRpcProgressPolicy(
    progressMethod: String,
    extractInboundToken: Structure.Value => (Maybe[Structure.Value] < Sync),
    extractRequestToken: Structure.Value => (Maybe[Structure.Value] < Sync),
    stampOutboundToken: (Structure.Value, Structure.Value) => (Structure.Value < Sync),
    encodeProgressParams: (Structure.Value, Structure.Value) => (Structure.Value < Sync),
    extractProgressValue: Structure.Value => (Maybe[Structure.Value] < Sync),
    enforceMonotonic: Boolean
) derives CanEqual

object JsonRpcProgressPolicy:
    import Structure.Value.Record

    // Field lookup in a Record; Absent for non-records or missing keys.
    private inline def field(v: Structure.Value, name: String): Maybe[Structure.Value] =
        v match
            case Record(fields) =>
                Maybe.fromOption(fields.iterator.collectFirst { case (k, x) if k == name => x })
            case _ => Absent

    // Merge two Records: b's keys win on collision (last-write-wins via Chunk concatenation).
    private inline def merge(a: Structure.Value, b: Structure.Value): Structure.Value =
        (a, b) match
            case (Record(af), Record(bf)) => Record(af ++ bf)
            case (Record(_), other)       => other
            case (_, Record(bf))          => Record(bf)
            case (_, other)               => other

    val lsp: JsonRpcProgressPolicy = JsonRpcProgressPolicy(
        progressMethod = "$/progress",
        extractInboundToken = p => field(p, "token"),
        extractRequestToken = p => field(p, "workDoneToken"),
        stampOutboundToken = (p, t) => merge(p, Record(Chunk("workDoneToken" -> t))),
        encodeProgressParams = (t, v) => Record(Chunk("token" -> t, "value" -> v)),
        extractProgressValue = p => field(p, "value"),
        enforceMonotonic = false
    )

    val mcp: JsonRpcProgressPolicy = JsonRpcProgressPolicy(
        progressMethod = "notifications/progress",
        extractInboundToken = p => field(p, "progressToken"),
        extractRequestToken = p =>
            field(p, "_meta").map(meta => field(meta, "progressToken")).getOrElse(Absent),
        stampOutboundToken = (p, t) =>
            val existingMeta = field(p, "_meta").getOrElse(Record(Chunk.empty))
            val newMeta      = merge(existingMeta, Record(Chunk("progressToken" -> t)))
            merge(p, Record(Chunk("_meta" -> newMeta)))
        ,
        encodeProgressParams = (t, v) =>
            merge(Record(Chunk("progressToken" -> t)), v),
        extractProgressValue = p => Present(p),
        enforceMonotonic = true
    )
end JsonRpcProgressPolicy
