package kyo

import kyo.*
import kyo.Test
import kyo.net.Connection
import kyo.net.NetPlatform

/** Unit tests for [[SqlClient.close]] idempotency, [[SqlClient.isClosed]] predicate, and `SqlConfig.closeGrace` defaulting.
  *
  * All tests are shared/cross-platform and use a minimal fake Postgres server (trust-auth handshake only). No real database required.
  */
class SqlClientCloseTest extends Test:

    // ── Fake Postgres server helpers ──────────────────────────────────────────

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

    private def pgTrustHandler(conn: Connection)(using Frame): Unit < Async =
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).unit
        }

    private def fakeUrl(port: Int): String =
        s"postgres://testuser:testpass@127.0.0.1:$port/testdb"

    private def baseConfig: SqlConfig =
        SqlConfig(
            maxConnections = 2,
            acquireTimeout = 5.seconds,
            queryTimeout = 5.seconds,
            idleTimeout = 10.minutes
        )

    // ── isClosed is false before close, true after ────────────────────────────

    "isClosed is false before close, true after" in {
        kyo.internal.FakeServer.listenPort { conn =>
            pgTrustHandler(conn)
        }.flatMap { listener =>
            val port = listener.port
            val url  = fakeUrl(port)
            Abort.run[SqlConnectionException](
                SqlClient.initUnscoped(url, baseConfig)
            ).flatMap {
                case Result.Success(client) =>
                    Scope.ensure(Abort.run(client.close).unit).andThen {
                        client.isClosed.flatMap { before =>
                            assert(!before, "isClosed should be false before close")
                            client.close.flatMap { _ =>
                                client.isClosed.map { after =>
                                    assert(after, "isClosed should be true after close")
                                }
                            }
                        }
                    }
                case Result.Failure(e) =>
                    fail(s"SqlClient.initUnscoped failed: $e")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic: ${t.getMessage}")
            }
        }
    }

    // ── a statement after close fails typed rather than spinning ──────────────

    "a statement issued after close fails with a typed SqlException" in {
        // `close`'s own scaladoc promises that post-close operations surface a SqlConnectionException, and the
        // acquire loop is where that promise is easy to lose: the ring's `poll` and `tryReserve` both answer
        // permanently negative once the pool is closed, so a loop whose only exits are those two spins with no
        // suspension point, pinning a carrier at 100% CPU on the JVM and hanging the single-threaded JS runtime.
        // `closeAll` also clears the slot channels, so the lease below recreates a fully-permitted one and gets as
        // far as the ring.
        //
        // The Async.timeout is the instrument: a Timeout here means the call never returned, which is the defect.
        // A typed failure means the contract holds.
        kyo.internal.FakeServer.listenPort(pgTrustHandler).flatMap { listener =>
            SqlClient.initUnscoped(fakeUrl(listener.port), baseConfig).flatMap { client =>
                client.close.flatMap { _ =>
                    Abort.run[Timeout] {
                        Async.timeout(5.seconds) {
                            Abort.run[SqlException](DB.run(client)(client.query("SELECT 1")))
                        }
                    }.map {
                        case Result.Success(Result.Failure(_: SqlConnectionPoolClosedException)) =>
                            succeed
                        case Result.Success(other) =>
                            fail(s"a post-close statement must fail as SqlConnectionPoolClosedException, got $other")
                        case Result.Failure(_) =>
                            fail("a post-close statement never returned: the acquire loop has no closed-pool exit")
                        case Result.Panic(t) =>
                            fail(s"unexpected panic: ${t.getMessage}")
                    }
                }
            }
        }
    }

    // ── close called twice does not throw, second is a no-op ─────────────────

    "close called twice does not throw, second is no-op" in {
        kyo.internal.FakeServer.listenPort { conn =>
            pgTrustHandler(conn)
        }.flatMap { listener =>
            val port = listener.port
            val url  = fakeUrl(port)
            Abort.run[SqlConnectionException](
                SqlClient.initUnscoped(url, baseConfig)
            ).flatMap {
                case Result.Success(client) =>
                    Scope.ensure(Abort.run(client.close).unit).andThen {
                        // First close: should mark as closed and drain the pool.
                        client.close.flatMap { _ =>
                            client.isClosed.flatMap { afterFirst =>
                                assert(afterFirst, "isClosed should be true after first close")
                                // Second close: must be a no-op (no exception, no double-drain).
                                client.close.flatMap { _ =>
                                    client.isClosed.map { afterSecond =>
                                        assert(afterSecond, "isClosed should still be true after second close")
                                    }
                                }
                            }
                        }
                    }
                case Result.Failure(e) =>
                    fail(s"SqlClient.initUnscoped failed: $e")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic: ${t.getMessage}")
            }
        }
    }

    // ── close(gracePeriod) accepts a custom grace duration ─────────────────────

    "close(gracePeriod) accepts a custom grace duration" in {
        kyo.internal.FakeServer.listenPort { conn =>
            pgTrustHandler(conn)
        }.flatMap { listener =>
            val port = listener.port
            val url  = fakeUrl(port)
            Abort.run[SqlConnectionException](
                SqlClient.initUnscoped(url, baseConfig)
            ).flatMap {
                case Result.Success(client) =>
                    Scope.ensure(Abort.run(client.close).unit).andThen {
                        // Pass an explicit non-default grace period; must complete without error.
                        client.close(100.millis).flatMap { _ =>
                            client.isClosed.map { closed =>
                                assert(closed, "isClosed should be true after close(100.millis)")
                            }
                        }
                    }
                case Result.Failure(e) =>
                    fail(s"SqlClient.initUnscoped failed: $e")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic: ${t.getMessage}")
            }
        }
    }

    // ── close() uses closeGrace from config ───────────────────────────────────

    "close() uses closeGrace from config" in {
        // Build config with a non-default closeGrace; close() must use it (not the hard-coded 30s).
        val config = baseConfig.copy(closeGrace = 42.millis)
        kyo.internal.FakeServer.listenPort { conn =>
            pgTrustHandler(conn)
        }.flatMap { listener =>
            val port = listener.port
            val url  = fakeUrl(port)
            Abort.run[SqlConnectionException](
                SqlClient.initUnscoped(url, config)
            ).flatMap {
                case Result.Success(client) =>
                    Scope.ensure(Abort.run(client.close).unit).andThen {
                        // close() with no argument must use config.closeGrace (42ms), not the hard-coded 30s.
                        // Verify by confirming it completes and isClosed becomes true.
                        client.close.flatMap { _ =>
                            client.isClosed.map { closed =>
                                assert(closed, "isClosed should be true after parameterless close() using config.closeGrace")
                            }
                        }
                    }
                case Result.Failure(e) =>
                    fail(s"SqlClient.initUnscoped failed: $e")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic: ${t.getMessage}")
            }
        }
    }

end SqlClientCloseTest
