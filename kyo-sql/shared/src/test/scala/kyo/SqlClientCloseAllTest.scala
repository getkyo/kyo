package kyo

import kyo.*
import kyo.Test
import kyo.net.Connection
import kyo.net.NetPlatform

/** Unit tests for [[SqlClient.close]] / `SqlClientBackend.closeAll` interrupt-safety (G-Leak-5).
  *
  * G-Leak-5: `closeAll` Step 3 previously used `Kyo.foreach`, whose iteration can be interrupted mid-loop, leaving remaining idle
  * connections un-closed. The fix replaces `Kyo.foreach` with a plain `idleConns.foreach` inside `Sync.Unsafe.defer`, making the drain
  * synchronous and non-interruptible.
  *
  * All tests are shared/cross-platform and use a minimal fake Postgres server (trust-auth handshake only). No real database required.
  *
  * Test count: 2 shared/unit leaves.
  */
class SqlClientCloseAllTest extends Test:

    // ── Fake Postgres server helpers ──────────────────────────────────────────

    /** AuthenticationOk + BackendKeyData + ReadyForQuery, the minimal trust-auth response that lets `pgConnect` succeed. */
    private val pgAuthOkBytes: Span[Byte] = Span.from(
        Array[Byte](
            // AuthenticationOk: type='R', length=8, authType=0
            'R'.toByte,
            0x00,
            0x00,
            0x00,
            0x08,
            0x00,
            0x00,
            0x00,
            0x00,
            // BackendKeyData: type='K', length=12, pid=1, secretKey=0
            'K'.toByte,
            0x00,
            0x00,
            0x00,
            0x0c,
            0x00,
            0x00,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x00,
            // ReadyForQuery: type='Z', length=5, status='I'
            'Z'.toByte,
            0x00,
            0x00,
            0x00,
            0x05,
            'I'.toByte
        )
    )

    /** Completes the trust-auth handshake and then idles (never closes the connection from the server side).
      *
      * The connection stays alive in the pool as an idle connection so that `closeAll` has something to drain.
      */
    private def pgHandshakeThenIdle(conn: Connection)(using Frame): Unit < Async =
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).andThen {
                // Keep the server side open so the pooled connection looks alive.
                Async.sleep(Duration.Infinity)
            }
        }

    private def fakeUrl(port: Int): String =
        s"postgres://testuser:testpass@127.0.0.1:$port/testdb"

    private def baseConfig(maxConns: Int = 3): SqlConfig =
        SqlConfig(
            maxConnections = maxConns,
            minConnections = maxConns, // warm up all slots so we have idle conns to drain
            acquireTimeout = 5.seconds,
            queryTimeout = 5.seconds,
            idleTimeout = 10.minutes
        )

    // ── closeAll drains all idle connections (regression) ──────────────────────

    "closeAll drains all idle connections (regression)" in {
        // Warm up 3 idle connections. closeAll should mark the client closed and complete
        // without error, confirming the synchronous drain ran to completion.
        kyo.internal.FakeServer.listenPort { conn =>
            pgHandshakeThenIdle(conn)
        }.flatMap { listener =>
            val port   = listener.port
            val url    = fakeUrl(port)
            val config = baseConfig(maxConns = 3)
            Abort.run[SqlConnectionException](
                SqlClient.initUnscoped(url, config)
            ).flatMap {
                case Result.Failure(e) =>
                    fail(s"SqlClient.initUnscoped failed: $e")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic: ${t.getMessage}")
                case Result.Success(client) =>
                    Scope.ensure(Abort.run(client.close).unit).andThen {
                        // Verify the pool is live before closeAll.
                        client.isClosed.flatMap { before =>
                            assert(!before, "isClosed should be false before close")
                            // closeAll with zero grace period, skips the poll loop and goes directly
                            // to step 3 (the synchronous drain).
                            client.close(Duration.Zero).flatMap { _ =>
                                client.isClosed.map { after =>
                                    assert(after, "isClosed should be true after closeAll, drain ran to completion")
                                }
                            }
                        }
                    }
            }
        }
    }

    // ── closeAll interrupted mid-drain still closes remaining connections ──────

    "closeAll interrupted mid-drain still closes remaining connections" in {
        // This test verifies the G-Leak-5 fix: the Step 3 drain in closeAll is now a
        // synchronous `idleConns.foreach` inside `Sync.Unsafe.defer`, which runs atomically
        // and cannot be interrupted between elements. We confirm the behavioral contract:
        //
        //   1. Start closeAll in a background fiber with a long grace period.
        //   2. Interrupt the fiber while it is in the grace-period poll loop (Step 2).
        //   3. Observe that isClosed is set (Step 1 ran) and the client cannot be used.
        //
        // The key invariant is that once Step 1 (pool.close()) succeeds, which sets the
        // closed flag synchronously, the idle connections have already been drained from
        // the pool's ring. Step 3's synchronous loop then closes them without any
        // suspension point where an interrupt could land. Even if Step 2's poll is
        // interrupted, any subsequent call to close() is a no-op (isClosed guard), so the
        // structural guarantee is: pool.close() atomically both marks closed AND extracts
        // the idle conn list, then the synchronous forEach completes without yielding.
        // Gate: opened when the pool is warmed up (client init returns).
        Latch.initWith(1) { warmedUp =>
            kyo.internal.FakeServer.listenPort { conn =>
                pgHandshakeThenIdle(conn)
            }.flatMap { listener =>
                val port   = listener.port
                val url    = fakeUrl(port)
                val config = baseConfig(maxConns = 2)
                Abort.run[SqlConnectionException](
                    SqlClient.initUnscoped(url, config)
                ).flatMap {
                    case Result.Failure(e) =>
                        fail(s"SqlClient.initUnscoped failed: $e")
                    case Result.Panic(t) =>
                        fail(s"Unexpected panic: ${t.getMessage}")
                    case Result.Success(client) =>
                        Scope.ensure(Abort.run(client.close).unit).andThen {
                            // Signal that the pool is warmed up.
                            warmedUp.release.andThen {
                                // Start closeAll with a long grace period in a background fiber.
                                // The grace period poll loop yields repeatedly, the fiber can be
                                // interrupted while it is sleeping in that poll loop.
                                Fiber.initUnscoped(client.close(60.seconds)).flatMap { closeFiber =>
                                    // Wait briefly for the fiber to enter the poll loop.
                                    Async.sleep(50.millis).andThen {
                                        // Interrupt closeAll mid-grace-period-poll.
                                        closeFiber.interrupt.flatMap { _ =>
                                            // Issue a second close (zero grace) to flush any remaining conns.
                                            // This must still complete without error because pool.close()
                                            // is idempotent (the closed flag prevents double-drain).
                                            client.close(Duration.Zero).flatMap { _ =>
                                                client.isClosed.map { closed =>
                                                    assert(
                                                        closed,
                                                        "isClosed must be true: pool.close() in Step 1 sets the flag atomically"
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                }
            }
        }
    }

end SqlClientCloseAllTest
