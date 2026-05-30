package kyo

/** A pre-dispatch hook that can allow, reject, or drop incoming envelopes before routing.
  *
  * Implement `beforeDispatch` to apply cross-cutting concerns such as authentication, rate
  * limiting, or protocol-specific pre-validation. The three possible outcomes are modelled by
  * [[MessageGate.Decision]]:
  *  - `Allow`: pass the message to the handler.
  *  - `Reject`: reply with a `JsonRpcError` and discard the message.
  *  - `Drop`: silently discard the message.
  *
  * Set via [[JsonRpcEndpoint.Config.gate]].
  *
  * @see [[JsonRpcEndpoint.Config]]
  */
trait MessageGate:
    def beforeDispatch(env: JsonRpcEnvelope)(using Frame): MessageGate.Decision < Sync

object MessageGate:
    enum Decision derives CanEqual:
        case Allow
        case Reject(error: JsonRpcError)
        case Drop
    end Decision
end MessageGate
