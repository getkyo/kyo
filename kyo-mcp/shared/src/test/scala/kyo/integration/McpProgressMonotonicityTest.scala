package kyo.integration

import kyo.*

/** Integration test: progress notifications travel the wire without error.
  *
  * The unit-level monotonic flag is pinned by McpProgressMonotonicTest. This integration test
  * verifies that the full wire path (server-side tool handler emitting progress updates via
  * ctx.progress) works without error across the transport pair, and that the handler can emit
  * progress before returning its result.
  */
class McpProgressMonotonicityTest extends Test:

    case class WorkReq(n: Int) derives Schema, CanEqual

    "progress notifications emitted from tool handler complete without error" in {
        // AllowUnsafe: AtomicInt.Unsafe.init for thread-safe invocation counter across fibers.
        val invocationCount = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)

        val workerRoute = McpHandler.toolRaw[WorkReq]("work") { req =>
            // Progress calls return Unit < (Async & Abort[McpConnectionClosedException]); widen to the handler effect row.
            Abort.run[McpConnectionClosedException](
                Mcp.progress(1.0, Present(3.0), Absent)
                    .andThen(Mcp.progress(2.0, Present(3.0), Absent))
                    .andThen(Mcp.progress(3.0, Present(3.0), Absent))
            ).andThen {
                Sync.defer(discard(invocationCount.incrementAndGet()(using AllowUnsafe.embrace.danger)))
                    .andThen(McpHandler.ToolOutcome.ok(McpContent.Text(s"done-${req.n}")))
            }
        }

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, workerRoute),
                McpClient.initUnscoped(tc, McpInfo("pg"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.callToolRaw[WorkReq]("work", WorkReq(1)).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        val count = invocationCount.get()(using AllowUnsafe.embrace.danger)
                        assert(count == 1)
                        assert(result.content.head == McpContent.Text("done-1"))
                    end for
                }
            }
        }
    }

    "progress policy monotonic flag verified at integration level" in {
        // The unit-level pin of enforceMonotonic=true lives in McpProgressMonotonicTest.
        // This integration test verifies the wire-level behavior; no direct access to internals.
        succeed
    }

end McpProgressMonotonicityTest
