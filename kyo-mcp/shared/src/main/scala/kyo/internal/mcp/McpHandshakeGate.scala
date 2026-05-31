package kyo.internal.mcp

import kyo.*

/** Two-stage MCP handshake gate.
  *
  * Stage 1: the `initialize` request must arrive before any non-ping request is admitted.
  * Stage 2 (when `order == RequireInitializedNotification`): the `notifications/initialized`
  * notification must also arrive before general traffic is admitted.
  *
  * Extends the single-stage pattern in `JsonRpcMessageGate.server.requireHandshake` with a
  * second `AtomicBoolean` flag for the initialized notification. INV-005.
  *
  * Rejection responses are built per-request from the inbound envelope's id so the client
  * receives a correctly correlated error response. (prep.md concern 1).
  */
private[kyo] object McpHandshakeGate:

    /** Builds the two-stage MCP handshake gate.
      *
      * @param order controls whether the `notifications/initialized` notification is required
      *              before general traffic is admitted.
      */
    def server(order: McpConfig.HandshakeOrder): JsonRpcMessageGate =
        // AllowUnsafe: AtomicBoolean for thread-safe flags shared across handler fibers.
        // Pattern mirrors JsonRpcMessageGate.server.requireHandshake at
        // kyo-jsonrpc/.../JsonRpcMessageGate.scala:77.
        val initRequestSeen = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger).safe
        val initNotifSeen   = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger).safe
        new JsonRpcMessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcMessageGate.Decision < Sync =
                env match
                    case JsonRpcRequest(_, "initialize", _, _) =>
                        initRequestSeen.set(true).andThen(JsonRpcMessageGate.Decision.Allow)

                    case JsonRpcNotification("notifications/initialized", _, _) =>
                        initNotifSeen.set(true).andThen(JsonRpcMessageGate.Decision.Allow)

                    case JsonRpcRequest(_, "ping", _, _) =>
                        Sync.defer(JsonRpcMessageGate.Decision.Allow)

                    case req: JsonRpcRequest =>
                        for
                            initReq <- initRequestSeen.get
                            initNot <- initNotifSeen.get
                        yield
                            val admitted = order match
                                case McpConfig.HandshakeOrder.RequireInitializedNotification =>
                                    initReq && initNot
                                case McpConfig.HandshakeOrder.RequireInitializeRequestOnly =>
                                    initReq
                            if admitted then
                                JsonRpcMessageGate.Decision.Allow
                            else
                                val err = McpHandshakeNotInitializedException(req.method)
                                JsonRpcMessageGate.Decision.Reject(JsonRpcResponse.failure(req.id, err))
                            end if

                    case _ =>
                        Sync.defer(JsonRpcMessageGate.Decision.Allow)
                end match
            end beforeDispatch
        end new
    end server

end McpHandshakeGate
