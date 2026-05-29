// flow-allow: PUBLIC wire-shape ADT exposed through JsonRpcTransport and MessageGate user implementations
package kyo

import kyo.Maybe
import kyo.Structure

enum JsonRpcEnvelope derives CanEqual:
    case Request(
        id: JsonRpcId,
        method: String,
        params: Maybe[Structure.Value],
        extras: Maybe[Structure.Value]
    )
    case Notification(
        method: String,
        params: Maybe[Structure.Value],
        extras: Maybe[Structure.Value]
    )
    case Response(
        id: JsonRpcId,
        result: Maybe[Structure.Value],
        error: Maybe[JsonRpcError],
        extras: Maybe[Structure.Value]
    )
    case Malformed(id: Maybe[JsonRpcId], reason: String, raw: Structure.Value)
end JsonRpcEnvelope
