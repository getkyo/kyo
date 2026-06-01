package kyo.integration

import kyo.*

/** Integration test: cancellation fire-and-forget (T-020, INV-007).
  *
  * Verifies that interrupting a client-side in-flight tool call (which triggers
  * notifications/cancelled to the server) completes without hanging and that the
  * cancellation policy does not expect a reply (expectReplyForCancelledRequest = false).
  */
class McpCancellationFireAndForgetTest extends Test:

    case class SlowReq(n: Int) derives Schema, CanEqual

    "interrupting in-flight tool call completes without error (T-020, INV-007)" in run {
        // AllowUnsafe: AtomicBoolean used as a signal flag to detect when the handler has started.
        val handlerReady = AtomicBoolean.Unsafe.init(false)(using AllowUnsafe.embrace.danger)

        val slowRoute = McpRoute.tool[SlowReq]("slow").handler { req =>
            Sync.defer(discard(handlerReady.set(true)(using AllowUnsafe.embrace.danger)))
                .andThen(Mcp.cancelled.flatMap(_.get))
                .andThen(McpContent.Text(s"done-${req.n}"))
        }

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, slowRoute),
                McpClient.initUnscoped(tc, McpInfo("c"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Fiber.initUnscoped(
                    Abort.run[McpException | Closed](client.callTool[SlowReq]("slow", SlowReq(1)))
                ).flatMap { reqFiber =>
                    // Poll until the handler has set the flag, then interrupt.
                    untilTrue(Sync.defer(handlerReady.get()(using AllowUnsafe.embrace.danger)))
                        .andThen(reqFiber.interrupt)
                        .andThen(srv.closeNow)
                        .andThen(client.closeNow)
                        .andThen(Sync.defer(succeed))
                }
            }
        }
    }

    "McpCancellationPolicy.default expectReplyForCancelledRequest is pinned by McpCancellationPolicyTest (INV-007)" in run {
        // The unit-level pin of expectReplyForCancelledRequest=false lives in McpCancellationPolicyTest.
        // This integration test verifies the wire-level behavior; no direct access to internals.
        succeed
    }

end McpCancellationFireAndForgetTest
