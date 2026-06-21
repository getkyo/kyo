package kyo

/** Configures how the endpoint reports and receives progress notifications during long-running
  * requests.
  *
  * A `JsonRpcProgressPolicy` captures the notification method name and a set of token-extraction and
  * parameter encoding/decoding functions for a given protocol.
  *
  * Set via [[JsonRpcHandler.Config.progress]].
  *
  * @param progressMethod
  *   the notification method name used for progress reporting.
  * @param extractInboundToken
  *   extracts the progress token from an inbound progress notification's params.
  * @param extractRequestToken
  *   extracts the progress token from the original request's params on the server side.
  * @param stampOutboundToken
  *   merges a freshly-allocated progress token into the outbound request params on the client side.
  * @param encodeProgressParams
  *   builds the wire params for an outbound progress notification given `(token, value)`.
  * @param extractProgressValue
  *   extracts the user-facing progress value from an inbound progress notification's params.
  * @param enforceMonotonic
  *   when `true`, progress values with a `progress` field that is not strictly non-decreasing are
  *   silently dropped.
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

    /** Field lookup in a Record; `Absent` for non-records or missing keys. */
    inline def field(v: Structure.Value, name: String): Maybe[Structure.Value] =
        v match
            case Record(fields) =>
                Maybe.fromOption(fields.iterator.collectFirst { case (k, x) if k == name => x })
            case _ => Absent

    /** Merge two Records: `b`'s keys win on collision (last-write-wins via `Chunk` concatenation). */
    inline def merge(a: Structure.Value, b: Structure.Value): Structure.Value =
        (a, b) match
            case (Record(af), Record(bf)) => Record(af ++ bf)
            case (Record(_), other)       => other
            case (_, Record(bf))          => Record(bf)
            case (_, other)               => other
end JsonRpcProgressPolicy
