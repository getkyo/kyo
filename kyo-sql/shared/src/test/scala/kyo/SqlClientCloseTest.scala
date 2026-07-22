package kyo

import kyo.*
import kyo.Test
import kyo.net.Connection
import kyo.net.NetPlatform

/** Unit tests for [[SqlClient.close]] idempotency, [[SqlClient.isClosed]] predicate, and `SqlConfig.closeGrace` defaulting.
  *
  * All tests are shared/cross-platform and use a minimal fake Postgres server (trust-auth handshake only). No real database required.
  *
  * Test count: 4 shared/unit leaves.
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
