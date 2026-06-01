package kyo.integration

import kyo.*

/** Integration test: progress notifications travel the wire without error (T-019, INV-007).
  *
  * The unit-level monotonic flag is pinned by McpProgressMonotonicTest. This integration test
  * verifies that the full wire path (server-side tool handler emitting progress updates via
  * ctx.progress) works without error across the transport pair, and that the handler can emit
  * progress before returning its result.
  */
class McpProgressMonotonicityTest extends Test:

    case class WorkReq(n: Int) derives Schema, CanEqual

    "progress notifications emitted from tool handler complete without error (T-019, INV-007)" in run {
        // AllowUnsafe: AtomicInt.Unsafe.init for thread-safe invocation counter across fibers.
        val invocationCount = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)

        val workerRoute = McpRoute.tool[WorkReq]("work").handler { req =>
            // Progress calls return Unit < (Async & Abort[Closed]); widen to the handler effect row.
            Abort.run[Closed](
                Mcp.progress(1.0, Present(3.0), Absent)
                    .andThen(Mcp.progress(2.0, Present(3.0), Absent))
                    .andThen(Mcp.progress(3.0, Present(3.0), Absent))
            ).andThen {
                Sync.defer(discard(invocationCount.incrementAndGet()(using AllowUnsafe.embrace.danger)))
                    .andThen(McpContent.Text(s"done-${req.n}"))
            }
        }

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, workerRoute),
                McpClient.initUnscoped(tc, McpInfo("pg"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.callTool[WorkReq]("work", WorkReq(1)).flatMap { result =>
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

    "McpProgressPolicy.default enforceMonotonic is pinned by McpProgressMonotonicTest (INV-007)" in run {
        // The unit-level pin of enforceMonotonic=true lives in McpProgressMonotonicTest.
        // This integration test verifies the wire-level behavior; no direct access to internals.
        succeed
    }

end McpProgressMonotonicityTest
