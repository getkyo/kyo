// flow-allow: PUBLIC gate trait implemented by users and consumed via JsonRpcEndpoint.Config.gate
package kyo

trait MessageGate:
    def beforeDispatch(env: JsonRpcEnvelope)(using Frame): MessageGate.Decision < Sync

object MessageGate:
    enum Decision derives CanEqual:
        case Allow
        case Reject(error: JsonRpcError)
        case Drop
    end Decision
end MessageGate
