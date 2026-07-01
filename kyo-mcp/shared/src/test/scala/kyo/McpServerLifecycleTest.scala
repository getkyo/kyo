package kyo

/** Tests for McpServer lifecycle hygiene: Scope.acquireRelease releases via the direct-close path. */
class McpServerLifecycleTest extends Test:

    private val toolRoute = McpHandler.tool[Unit]("noop")((_) => McpContent.Text(""))

    // McpServer.init (Scope-managed) releases via closeDirect when the Scope exits.
    // Verify the release does not throw and the Scope completes cleanly.
    "McpServer.init releases cleanly via Scope on normal exit" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            Scope.run {
                McpServer.init(ta, toolRoute).andThen(succeed)
            }
        }
    }

    // McpClient.init (Scope-managed) releases via closeDirect when the Scope exits.
    "McpClient.init releases cleanly via Scope on normal exit" in {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Scope.run {
                McpServer.init(ts, toolRoute).flatMap { _ =>
                    McpClient.init(tc, McpInfo("lifecycle-test"), McpCapabilities.Client()).andThen(succeed)
                }
            }
        }
    }

    // abort path: Scope.acquireRelease releases the server even when the scope body
    // aborts. The close count on the transport must be exactly 1 (no double-close, no leak).
    // A ClosingTransport wraps an inMemory transport and counts calls to close().
    "McpServer.init releases exactly once when scope body aborts" in {
        // Unsafe: AtomicInt.Unsafe for close-count shared across the release callback fiber.
        val closeCount = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        JsonRpcTransport.inMemory.flatMap { (inner, _) =>
            class ClosingTransport(base: JsonRpcTransport) extends JsonRpcTransport:
                def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
                    base.send(env)
                def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
                    base.incoming
                def close(using Frame): Unit < Async =
                    Sync.defer(discard(closeCount.incrementAndGet()(using AllowUnsafe.embrace.danger))).andThen(base.close)
            end ClosingTransport

            val ct = ClosingTransport(inner)
            Abort.run[String] {
                Scope.run {
                    McpServer.init(ct, toolRoute).flatMap { _ =>
                        Abort.fail("deliberate-abort"): Unit < (Async & Scope & Abort[String])
                    }
                }
            }.map { _ =>
                val count = closeCount.get()(using AllowUnsafe.embrace.danger)
                assert(count == 1, s"expected close count 1, got $count")
            }
        }
    }

end McpServerLifecycleTest
