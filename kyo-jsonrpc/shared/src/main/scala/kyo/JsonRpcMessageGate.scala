package kyo

/** A pre-dispatch hook that can allow, reject, or drop incoming envelopes before routing.
  *
  * Implement `beforeDispatch` to apply cross-cutting concerns such as authentication, rate
  * limiting, or protocol-specific pre-validation. The three possible outcomes are modelled by
  * [[JsonRpcMessageGate.Decision]]:
  *  - [[JsonRpcMessageGate.Decision.Allow]]: pass the message to the handler.
  *  - [[JsonRpcMessageGate.Decision.Reject]]: reply with a full [[JsonRpcResponse]] and discard
  *    the message. The response is sent directly over the wire; the caller is not left hanging.
  *  - [[JsonRpcMessageGate.Decision.Drop]]: silently discard the message.
  *
  * Set via [[JsonRpcHandler.Config.gate]].
  *
  * Use [[JsonRpcMessageGate.noop]] for a no-op gate that always admits every envelope.
  * Server-side preset patterns live in [[JsonRpcMessageGate.server]].
  * Client-side preset patterns live in [[JsonRpcMessageGate.client]].
  *
  * Mirrors [[kyo.HttpFilter]] at kyo-http/shared/src/main/scala/kyo/HttpFilter.scala:43.
  *
  * @see [[JsonRpcHandler.Config]]
  * @see [[JsonRpcResponse.Halt]]
  */
trait JsonRpcMessageGate:
    def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcMessageGate.Decision < Sync
end JsonRpcMessageGate

object JsonRpcMessageGate:

    /** The three outcomes a gate may return from [[JsonRpcMessageGate.beforeDispatch]].
      *
      * [[Allow]]: forward the message to the registered handler.
      * [[Reject]]: send the supplied [[JsonRpcResponse]] back to the caller and skip the handler.
      *   Construct the response via [[JsonRpcResponse.failure]]:
      *   {{{
      *   Decision.Reject(JsonRpcResponse.failure(id, JsonRpcImplementationError(-32002, "Not ready")))
      *   }}}
      * [[Drop]]: silently discard the message with no reply.
      */
    enum Decision derives CanEqual:
        case Allow
        case Reject(response: JsonRpcResponse)
        case Drop
    end Decision

    /** A gate that always admits every envelope. */
    val noop: JsonRpcMessageGate = new JsonRpcMessageGate:
        def beforeDispatch(env: JsonRpcEnvelope)(using Frame): Decision < Sync =
            Sync.defer(Decision.Allow)

    /** Server-side gate patterns for common protocol pre-validation use cases.
      *
      * Mirrors [[kyo.HttpFilter.server]] at kyo-http/shared/src/main/scala/kyo/HttpFilter.scala:108.
      */
    object server:

        /** Returns a gate that requires a request with `handshakeMethod` to succeed before any
          * other request is dispatched. Requests for other methods that arrive before the handshake
          * completes are rejected with `onUninitializedRequest`.
          *
          * Common for servers that require a handshake message before accepting work. For example,
          * a server that requires an `"initialize"` request before processing any other request
          * can be configured with `requireHandshake("initialize", rejectionResponse)`.
          *
          * @param handshakeMethod
          *   the method name that triggers the handshake; once a request with this method is seen,
          *   all subsequent requests are allowed through.
          * @param onUninitializedRequest
          *   the response to send when a non-handshake request arrives before the handshake.
          */
        def requireHandshake(
            handshakeMethod: String,
            onUninitializedRequest: JsonRpcResponse
        )(using Frame): JsonRpcMessageGate < Sync =
            // Thread-safe initialized flag shared across handler fibers, initialized under the safe tier.
            AtomicBoolean.init(false).map { initialized =>
                new JsonRpcMessageGate:
                    def beforeDispatch(env: JsonRpcEnvelope)(using Frame): Decision < Sync =
                        env match
                            case JsonRpcRequest(_, `handshakeMethod`, _, _) =>
                                initialized.set(true).andThen(Decision.Allow)
                            case _: JsonRpcRequest =>
                                initialized.get.map { done =>
                                    if done then Decision.Allow
                                    else Decision.Reject(onUninitializedRequest)
                                }
                            case _ =>
                                Decision.Allow
                    end beforeDispatch
            }
        end requireHandshake

    end server

    /** Client-side gate patterns for outbound request pre-processing.
      *
      * Mirrors [[kyo.HttpFilter.client]] at kyo-http/shared/src/main/scala/kyo/HttpFilter.scala:324.
      */
    object client:
        // Intentionally empty for now. Reserved for future client-side gate patterns.
    end client

end JsonRpcMessageGate
