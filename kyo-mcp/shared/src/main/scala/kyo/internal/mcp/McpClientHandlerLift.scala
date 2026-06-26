package kyo.internal.mcp

import kyo.*

/** Lifts an [[McpClientHandler]] carrier to a [[JsonRpcRoute]] the client engine registers.
  *
  * A request carrier (`onSampling`/`onElicitation`/`onRoots`/`customClient`) lifts to a
  * `JsonRpcRoute.request`; a notification carrier (`onNotification`/`onLog`/`onResourceUpdated`)
  * lifts to a `JsonRpcRoute.notification` with no reply path. `onRoots` returns `Chunk[Root]`
  * and the engine wraps it in the wire envelope.
  */
private[kyo] object McpClientHandlerLift:

    def liftRequest[In, Out, E](
        c: McpClientHandler.RequestCarrier[In, Out, E],
        serverRef: AtomicRef[Maybe[McpServer.Unsafe]]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        if c.method == "roots/list" then
            // onRoots returns Chunk[Root]; wrap in the wire envelope the route decodes.
            // Schema[McpReverseDispatch.EmptyParams] and Schema[McpRootsListResponse] come from derives Schema.
            JsonRpcRoute.request[McpReverseDispatch.EmptyParams, McpRootsListResponse](c.method) { (_, jrCtx) =>
                McpHandlerLift.bindClientCtx(jrCtx, serverRef) {
                    c.handler.asInstanceOf[Unit => Chunk[McpServer.Root] < (Async & Abort[JsonRpcResponse.Halt | E])](())
                        .map(McpRootsListResponse(_))
                }
            }
        else
            given Schema[In]  = c.inSchema
            given Schema[Out] = c.outSchema
            JsonRpcRoute.request[In, Out](c.method) { (in, jrCtx) =>
                McpHandlerLift.bindClientCtx(jrCtx, serverRef)(c.handler(in))
            }

    def liftNotification[In, E](
        c: McpClientHandler.NotificationCarrier[In, E],
        serverRef: AtomicRef[Maybe[McpServer.Unsafe]]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        given Schema[In] = c.inSchema
        JsonRpcRoute.notification[In](c.method) { (in, jrCtx) =>
            McpHandlerLift.bindClientCtx(jrCtx, serverRef)(c.handler(in))
        }
    end liftNotification

end McpClientHandlerLift
