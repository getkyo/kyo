package kyo.internal.mcp

import kyo.*

/** MCP-specific cancellation policy adapter for kyo-jsonrpc.
  *
  * MCP uses the `notifications/cancelled` notification method with a `requestId` param field
  * carrying the target request identifier. The `initialize` method is protected from cancellation
  * per the MCP spec.
  *
  * The `cancelledError` slot is `Absent`, delegating to the substrate default error code -32800
  * "Request cancelled". `expectReplyForCancelledRequest` is `false`: the server interrupts the
  * handler and sends no reply.
  */
private[kyo] object McpCancellationPolicy:

    val default: JsonRpcCancellationPolicy =
        JsonRpcCancellationPolicy(
            cancelMethod = "notifications/cancelled",
            encodeParams = (id, reason) =>
                Sync.defer {
                    val idField = "requestId" -> id.fold(
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
                                case (k, v) if k == "requestId" =>
                                    v match
                                        case Structure.Value.Integer(n) => Present(JsonRpcId(n))
                                        case Structure.Value.Str(s)     => Present(JsonRpcId(s))
                                        case _                          => Absent
                            }.getOrElse(Absent)
                        case _ => Absent
                },
            expectReplyForCancelledRequest = false,
            cancelledError = Absent,
            protectedMethods = Set("initialize")
        )

end McpCancellationPolicy
