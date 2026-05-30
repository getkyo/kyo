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
      *   Gates that previously returned `Reject(error: JsonRpcError)` must now construct a full
      *   response via [[JsonRpcResponse.failure]]:
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

        /** Returns a gate that requires the `initialize` request to arrive before any other
          * request is dispatched. Non-initialize requests arriving before initialization is complete
          * are rejected with the supplied error response.
          *
          * Typical use: LSP servers that must complete the `initialize` / `initialized` handshake
          * before accepting any further requests.
          *
          * @param onUninitializedRequest
          *   the response to send when a non-initialize request arrives before initialization.
          */
        def requireInitialize(
            onUninitializedRequest: JsonRpcResponse
        ): JsonRpcMessageGate =
            // Unsafe: AtomicBoolean for thread-safe initialized flag shared across handler fibers
            val initialized = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger).safe
            new JsonRpcMessageGate:
                def beforeDispatch(env: JsonRpcEnvelope)(using Frame): Decision < Sync =
                    env match
                        case JsonRpcRequest(_, "initialize", _, _) =>
                            initialized.set(true).andThen(Decision.Allow)
                        case _: JsonRpcRequest =>
                            initialized.get.map { done =>
                                if done then Decision.Allow
                                else Decision.Reject(onUninitializedRequest)
                            }
                        case _ =>
                            Decision.Allow
                end beforeDispatch
            end new
        end requireInitialize

    end server

    /** Client-side gate patterns for outbound request pre-processing.
      *
      * Mirrors [[kyo.HttpFilter.client]] at kyo-http/shared/src/main/scala/kyo/HttpFilter.scala:324.
      */
    object client:
        // Intentionally empty for now. Client-side gate use-cases are expected to be added in
        // Phase G when outbound gating is introduced.
    end client

end JsonRpcMessageGate
