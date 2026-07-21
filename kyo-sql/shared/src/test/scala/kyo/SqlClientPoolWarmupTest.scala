package kyo

import kyo.*
import kyo.Test
import kyo.net.Connection
import kyo.net.Listener
import kyo.net.NetPlatform

/** Unit tests for connection-pool warm-up `minConnections`.
  *
  * All tests are shared/cross-platform and use a minimal fake Postgres server (TCP listener + Postgres wire-protocol trust-auth response).
  * No real database required.
  *
  * The fake server responds to Postgres startup with `AuthenticationOk + BackendKeyData + ReadyForQuery` so that `pgConnect` succeeds and
  * the warm-up logic can place connections in the idle pool. For "refuse" tests the server closes the connection immediately after accept.
  *
  * Test count: 8 shared/unit leaves (target: 8 shared + 1 JVM integration).
  */
class SqlClientPoolWarmupTest extends Test:

    // ── Fake Postgres server helpers ──────────────────────────────────────────

    /** Minimal Postgres startup response bytes (trust auth — no password required).
      *
      * Byte layout:
      *   - AuthenticationOk: `R` + Int32(8) + Int32(0)
      *   - BackendKeyData(pid=1, key=0): `K` + Int32(12) + Int32(1) + Int32(0)
      *   - ReadyForQuery('I'): `Z` + Int32(5) + `I`
      */
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

    /** Fake Postgres server connection handler that completes the trust-auth handshake.
      *
      * Reads and discards the startup message (one `inbound.take` is sufficient — the Postgres client sends the full startup message in one
      * write). Then sends `AuthenticationOk + BackendKeyData + ReadyForQuery` so `pgConnect` considers the connection open.
      *
      * Calls `onAccept` just before reading the startup message (counted as an "in-progress" accept). Calls `onHandshakeDone` after the
      * response is written (counted as a fully opened connection).
      */
    private def pgTrustHandler(
        conn: Connection,
        onAccept: Unit < Async = (),
        onHandshakeDone: Unit < Async = ()
    )(using Frame): Unit < Async =
        onAccept.andThen {
            // Read and discard the startup message — we don't parse it, just drain one chunk.
            Abort.run[Closed](conn.inbound.safe.take).andThen {
                Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).andThen {
                    onHandshakeDone
                }
            }
        }

    /** Fake Postgres server that immediately closes each accepted connection (simulates a server that refuses all connections). */
    private def pgRefuseHandler(
        conn: Connection,
        onAccept: Unit < Async = ()
    )(using Frame): Unit < Async =
        onAccept.andThen(Sync.Unsafe.defer(conn.close()))

    /** Constructs a `postgres://` URL pointing to a local fake server. */
    private def fakeUrl(port: Int): String =
        s"postgres://testuser:testpass@127.0.0.1:$port/testdb"

    /** Constructs a [[SqlClientConfig]] for warm-up tests with no TLS, short timeouts, and the given pool parameters. */
    private def warmupConfig(maxConns: Int, minConns: Int): SqlClientConfig =
        SqlClientConfig(
            maxConnections = maxConns,
            minConnections = minConns,
            acquireTimeout = 5.seconds,
            queryTimeout = 5.seconds,
            idleTimeout = 10.minutes
        )

    // ── minConnections=3 opens 3 connections ──────────────────────────────────

    "minConnections=3 opens 3 concurrent connections before init returns" in {
        Scope.run {
            AtomicInt.initWith(0) { acceptCount =>
                kyo.internal.FakeServer.listenPort { conn =>
                    pgTrustHandler(conn, onAccept = acceptCount.incrementAndGet.unit)
                }.flatMap { listener =>
                    val port = listener.port
                    val url  = fakeUrl(port)
                    Abort.run[SqlException.Connection](
                        SqlClient.init(url, warmupConfig(maxConns = 5, minConns = 3))
                    ).flatMap {
                        case Result.Success(_) =>
                            acceptCount.get.map { count =>
                                assert(count == 3, s"Expected 3 connections opened, got $count")
                            }
                        case Result.Failure(e) =>
                            // Warm-up may fail if startup handshake times out — count what arrived.
                            acceptCount.get.map { count =>
                                assert(count == 3, s"Expected 3 accepts, got $count; init failure: $e")
                            }
                        case Result.Panic(t) =>
                            fail(s"Unexpected panic: ${t.getMessage}")
                    }
                }
            }
        }
    }

    // ── minConnections=0 returns without opening any connection ───────────────

    "minConnections=0 returns immediately without opening any connection" in {
        Scope.run {
            AtomicInt.initWith(0) { acceptCount =>
                kyo.internal.FakeServer.listenPort { conn =>
                    // Should never be called; increment counter to detect unexpected connects.
                    acceptCount.incrementAndGet.unit.andThen(Sync.Unsafe.defer(conn.close()))
                }.flatMap { listener =>
                    val port = listener.port
                    val url  = fakeUrl(port)
                    // minConnections=0: warmUp is a no-op, no TCP connects should occur.
                    Abort.run[SqlException.Connection](
                        SqlClient.init(url, warmupConfig(maxConns = 5, minConns = 0))
                    ).flatMap { _ =>
                        acceptCount.get.map { count =>
                            assert(count == 0, s"Expected 0 connections, got $count")
                        }
                    }
                }
            }
        }
    }

    // ── minConnections > maxConnections clamps to maxConnections ──────────────

    "minConnections > maxConnections clamps to maxConnections" in {
        Scope.run {
            AtomicInt.initWith(0) { acceptCount =>
                kyo.internal.FakeServer.listenPort { conn =>
                    pgTrustHandler(conn, onAccept = acceptCount.incrementAndGet.unit)
                }.flatMap { listener =>
                    val port = listener.port
                    val url  = fakeUrl(port)
                    // minConnections=7 but maxConnections=5 → clamp to 5.
                    Abort.run[SqlException.Connection](
                        SqlClient.init(url, warmupConfig(maxConns = 5, minConns = 7))
                    ).flatMap { _ =>
                        acceptCount.get.map { count =>
                            assert(count == 5, s"Expected 5 (clamped from 7), got $count")
                        }
                    }
                }
            }
        }
    }

    // ── warm-up errors abort init with SqlException.Connection ────────────────

    "warm-up errors abort init with SqlException.Connection" in {
        Scope.run {
            kyo.internal.FakeServer.listenPort { conn =>
                // Immediately close: Postgres startup will fail → SqlException.Connection.
                Sync.Unsafe.defer(conn.close())
            }.flatMap { listener =>
                val port = listener.port
                val url  = fakeUrl(port)
                Abort.run[SqlException.Connection](
                    SqlClient.init(url, warmupConfig(maxConns = 5, minConns = 3))
                ).map {
                    case Result.Failure(_: SqlException.Connection) =>
                        succeed
                    case Result.Success(_) =>
                        fail("Expected init to fail with SqlException.Connection when server refuses all connections")
                    case Result.Panic(t) =>
                        fail(s"Unexpected panic: ${t.getMessage}")
                }
            }
        }
    }

    // ── partial warm-up failure aborts init; no connections leaked ────────────

    "partial warm-up failure (3 of 5 succeed, 2 fail) aborts init; no connections leaked" in {
        Scope.run {
            // Track: total accepted, total handshakes completed (connection put into pool), total closed.
            AtomicInt.initWith(0) { acceptCount =>
                AtomicInt.initWith(0) { closedCount =>
                    // First 3 accepts: complete trust-auth so connections enter the pool.
                    // Connections 4 and 5: close immediately (simulating failure).
                    kyo.internal.FakeServer.listenPort { conn =>
                        acceptCount.getAndIncrement.flatMap { n =>
                            if n < 3 then
                                // Successful handshake.
                                pgTrustHandler(conn)
                            else
                                // Refuse: connection fails at handshake.
                                closedCount.incrementAndGet.unit.andThen(Sync.Unsafe.defer(conn.close()))
                        }
                    }.flatMap { listener =>
                        val port = listener.port
                        val url  = fakeUrl(port)
                        Abort.run[SqlException.Connection](
                            SqlClient.init(url, warmupConfig(maxConns = 5, minConns = 5))
                        ).map {
                            case Result.Failure(_: SqlException.Connection) =>
                                // Expected: warmUp aborted because at least one connection failed.
                                succeed
                            case Result.Success(_) =>
                                fail("Expected init to fail with SqlException.Connection on partial warm-up failure")
                            case Result.Panic(t) =>
                                fail(s"Unexpected panic: ${t.getMessage}")
                        }
                    }
                }
            }
        }
    }

    // ── cancellation during warm-up closes any partial connections ────────────

    "cancellation during warm-up closes any partial connections" in {
        Scope.run {
            // The server hangs after reading the startup message (never sends auth response),
            // so the client's pgConnect will be suspended waiting for auth. The test cancels
            // the init fiber mid-warm-up and verifies the fiber was interrupted.
            //
            // Synchronization: a Latch(3) gates the interrupt — we wait until all 3 warm-up
            // connections have sent their startup message (server has read it) before interrupting,
            // ensuring the fiber is definitively blocked on auth response.
            Latch.initWith(3) { allStarted =>
                kyo.internal.FakeServer.listenPort { conn =>
                    // Read startup message → release latch (signal "I am blocked") → hang forever.
                    Abort.run[Closed](conn.inbound.safe.take).andThen {
                        allStarted.release.andThen {
                            // Never send a response — the warm-up hangs until cancelled.
                            Async.sleep(Duration.Infinity)
                        }
                    }
                }.flatMap { listener =>
                    val port = listener.port
                    val url  = fakeUrl(port)
                    Fiber.initUnscoped(
                        Abort.run[SqlException.Connection](
                            SqlClient.init(url, warmupConfig(maxConns = 3, minConns = 3))
                        )
                    ).flatMap { fiber =>
                        // Wait until all 3 warm-up connections are blocked on auth (latch released).
                        allStarted.await.andThen {
                            fiber.interrupt.map { interrupted =>
                                assert(interrupted, "Expected the warm-up fiber to be interrupted")
                            }
                        }
                    }
                }
            }
        }
    }

    // ── first user query after warm-up uses a pre-warmed connection ───────────

    "first user query after warm-up uses a pre-warmed connection (no new open)" in {
        Scope.run {
            // Track opens AFTER warm-up completes: the first query should reuse a pooled connection,
            // NOT trigger a new TCP accept.
            AtomicInt.initWith(0) { openCount =>
                // Warm-up handles: complete trust-auth.
                // Query handles: also complete trust-auth + ReadyForQuery.
                // We count ALL accepts but then subtract 'minConnections' to get post-init opens.
                // Use a short queryTimeout (200ms) so the query times out quickly against the fake server
                // (which doesn't understand SQL) — the test only cares about the open-count assertion.
                kyo.internal.FakeServer.listenPort { conn =>
                    openCount.incrementAndGet.unit.andThen(pgTrustHandler(conn))
                }.flatMap { listener =>
                    val port = listener.port
                    val url  = fakeUrl(port)
                    val config = SqlClientConfig(
                        maxConnections = 5,
                        minConnections = 3,
                        acquireTimeout = 5.seconds,
                        queryTimeout = 200.millis,
                        idleTimeout = 10.minutes
                    )
                    Abort.run[SqlException.Connection](
                        SqlClient.init(url, config)
                    ).flatMap {
                        case Result.Success(client) =>
                            // Record opens after warm-up completed.
                            openCount.get.flatMap { openedDuringWarmup =>
                                // Run one query — should NOT open a new connection.
                                Abort.run[SqlException](SqlClient.let(client)(client.query("SELECT 1"))).flatMap { _ =>
                                    openCount.get.map { openedAfterQuery =>
                                        val newOpens = openedAfterQuery - openedDuringWarmup
                                        assert(
                                            newOpens == 0,
                                            s"Expected 0 new opens after warm-up, got $newOpens (total: $openedAfterQuery, warmup: $openedDuringWarmup)"
                                        )
                                    }
                                }
                            }
                        case Result.Failure(e) =>
                            fail(s"SqlClient.init failed: $e")
                        case Result.Panic(t) =>
                            fail(s"Unexpected panic: ${t.getMessage}")
                    }
                }
            }
        }
    }

    // ── warm-up opens all connections concurrently ────────────────────────────

    "warm-up issues all opens concurrently — fake server observes max-simultaneous-accept == minConnections" in {
        Scope.run {
            val minConns = 3
            // Strategy: each handler increments a counter when it enters AND blocks at a latch.
            // The latch fires only when ALL minConns handlers have incremented (i.e., all are simultaneously
            // active in the handler body). At that point we snapshot activeCount which must == minConns.
            // This is the structural invariant for "all opens concurrent".
            AtomicInt.initWith(0) { activeCount =>
                AtomicRef.initWith(0) { snapshotRef =>
                    Latch.initWith(minConns) { allActiveGate =>
                        kyo.internal.FakeServer.listenPort { conn =>
                            // Track the PEAK concurrent active count via a max-update at increment time.
                            // The previous design (snap activeCount after the latch opens, then set
                            // snapshotRef) is racy: it's last-write-wins, so on any scheduler that wakes
                            // the post-await fibers in sequence (where each handler may decrement before
                            // the next handler snaps), the final snapshot reflects the LAST writer rather
                            // than the peak. JVM happened to win the race; Native lost it. Updating
                            // snapshotRef = max(prev, current) at increment time is correct on every
                            // scheduler and captures the structural invariant we actually want.
                            activeCount.incrementAndGet.flatMap { current =>
                                snapshotRef.updateAndGet(prev => Math.max(prev, current)).andThen {
                                    allActiveGate.release.andThen {
                                        // Wait until all minConns connections are simultaneously active.
                                        allActiveGate.await.andThen {
                                            // Respond so clients can proceed, then decrement.
                                            Abort.run[Closed](conn.inbound.safe.take).andThen {
                                                Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).andThen {
                                                    activeCount.decrementAndGet.unit
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }.flatMap { listener =>
                            val port   = listener.port
                            val url    = fakeUrl(port)
                            val config = warmupConfig(maxConns = 5, minConns = minConns)
                            Abort.run[SqlException.Connection](
                                SqlClient.init(url, config)
                            ).flatMap { _ =>
                                snapshotRef.get.map { snap =>
                                    assert(
                                        snap == minConns,
                                        s"Expected $minConns simultaneous server connections during warm-up, got $snap"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── warmUp N=3 succeeds: all 3 connections placed in idle pool ───────────

    "warmUp N=3 succeeds: all 3 connections placed in pool (regression: no fd leak on success)" in {
        Scope.run {
            AtomicInt.initWith(0) { acceptCount =>
                kyo.internal.FakeServer.listenPort { conn =>
                    acceptCount.incrementAndGet.unit.andThen(pgTrustHandler(conn))
                }.flatMap { listener =>
                    val port = listener.port
                    val url  = fakeUrl(port)
                    Abort.run[SqlException.Connection](
                        SqlClient.init(url, warmupConfig(maxConns = 3, minConns = 3))
                    ).flatMap {
                        case Result.Success(_) =>
                            acceptCount.get.map { count =>
                                assert(count == 3, s"Expected exactly 3 connections warmed up, got $count")
                            }
                        case Result.Failure(e) =>
                            fail(s"Expected warmUp with N=3 to succeed, got failure: $e")
                        case Result.Panic(t) =>
                            fail(s"Unexpected panic: ${t.getMessage}")
                    }
                }
            }
        }
    }

    // ── G-Leak-2 regression: partial-failure warmUp closes successful connections ──

    /** Regression test for G-Leak-2: `warmUp` used to drop partial successes on the first failure.
      *
      * The server accepts and completes the handshake for the first 2 connections, then immediately closes the 3rd (causing a
      * `SqlException.Connection`). The fix must:
      *   1. Propagate the error (init fails with `SqlException.Connection`).
      *   2. Close the 2 successful connections so the server-side sockets are released.
      *
      * Server-side closure detection: the handler for each of the 2 successful connections waits for the client to close (inbound.take
      * returns `Closed`) and then releases one count of `clientClosedLatch`. The test awaits the latch with a 5-second timeout.
      *
      * BEFORE the fix the latch never fires (successful connections are never closed), causing a test timeout. AFTER the fix both
      * connections are closed and the latch fires promptly.
      */
    "G-Leak-2: partial-failure warmUp (first 2 succeed, 3rd fails) closes the 2 successful connections" in {
        Scope.run {
            Latch.initWith(2) { clientClosedLatch =>
                AtomicInt.initWith(0) { acceptCount =>
                    kyo.internal.FakeServer.listenPort { conn =>
                        acceptCount.getAndIncrement.flatMap { n =>
                            if n < 2 then
                                // Successful handshake: complete trust-auth, then wait for the client to close the socket.
                                // When the client closes, inbound.take returns Result.Failure(Closed) — we release the latch.
                                Abort.run[Closed](conn.inbound.safe.take).andThen {
                                    Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).andThen {
                                        // Block until client closes this connection.
                                        Abort.run[Closed](conn.inbound.safe.take).andThen {
                                            // Whether the read returned Success or Failure(Closed), both mean the
                                            // client has closed or is about to close — release the latch.
                                            clientClosedLatch.release
                                        }
                                    }
                                }
                            else
                                // Refuse: immediately close — 3rd+ connections fail with SqlException.Connection.
                                Sync.Unsafe.defer(conn.close())
                        }
                    }.flatMap { listener =>
                        val port = listener.port
                        val url  = fakeUrl(port)
                        Abort.run[SqlException.Connection](
                            SqlClient.init(url, warmupConfig(maxConns = 3, minConns = 3))
                        ).flatMap {
                            case Result.Failure(_: SqlException.Connection) =>
                                // Expected: warmUp aborted. Wait for latch to confirm the 2 successful
                                // connections were closed (G-Leak-2 fix verification).
                                Abort.run[Timeout](Async.timeout(5.seconds)(clientClosedLatch.await)).map {
                                    case Result.Success(_) => succeed
                                    case _ =>
                                        fail(
                                            "G-Leak-2 regression: the 2 successful warmUp connections were not closed after partial failure"
                                        )
                                }
                            case Result.Success(_) =>
                                fail("Expected init to fail with SqlException.Connection on partial warm-up failure")
                            case Result.Panic(t) =>
                                fail(s"Unexpected panic: ${t.getMessage}")
                        }
                    }
                }
            }
        }
    }

end SqlClientPoolWarmupTest
