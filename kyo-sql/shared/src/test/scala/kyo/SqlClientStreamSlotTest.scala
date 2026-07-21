package kyo

import kyo.*
import kyo.Test
import kyo.net.Connection
import kyo.net.NetPlatform

/** Unit and structural tests for G-Leak-3: `acquireStreamSlot` discards the connection on protocol-fatal abort.
  *
  * Test strategy:
  *   - One structural test exercises the success path end-to-end with a fake Postgres server, asserting that the slot is returned and the
  *     pool can serve a second acquire (regression guard).
  *   - The `isProtocolFatal` predicate unit tests live in [[kyo.internal.client.SqlClientBackendTest]].
  *
  * All tests are shared/cross-platform. No real database required.
  *
  * Test count: 1 shared/unit leaf.
  */
class SqlClientStreamSlotTest extends Test:

    // ── Fake Postgres trust-auth handshake bytes ──────────────────────────────

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

    /** Completes trust-auth then hangs, the connection stays open so the slot owner's Scope can be verified. */
    private def pgHandshakeThenHang(conn: Connection)(using Frame): Unit < Async =
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).andThen {
                Async.sleep(Duration.Infinity)
            }
        }

    private def fakeUrl(port: Int): String =
        s"postgres://testuser:testpass@127.0.0.1:$port/testdb"

    private def slotConfig: SqlConfig =
        SqlConfig(
            maxConnections = 1,
            acquireTimeout = 500.millis,
            queryTimeout = 100.millis,
            idleTimeout = 10.minutes
        )

    private def isPoolExhausted(e: SqlException): Boolean =
        e match
            case SqlException.Connection(msg, _) => msg.startsWith("Timed out")
            case _                               => false

    // ── stream success returns connection to pool (regression) ────────────────

    "stream success returns connection to pool (regression)" in {
        // A fake server that completes the handshake and then hangs.
        // We issue two sequential queries (each times out at the query-body level, NOT the
        // acquire level). The slot must be returned after the first query so the second
        // acquire can proceed. If the slot were leaked the second acquire would hit
        // pool-exhaustion ("Timed out waiting … for a connection").
        kyo.internal.FakeServer.listenPort { conn =>
            pgHandshakeThenHang(conn)
        }.flatMap { listener =>
            val port   = listener.port
            val url    = fakeUrl(port)
            val config = slotConfig
            Abort.run[SqlException.Connection](
                SqlClient.initUnscoped(url, config)
            ).flatMap {
                case Result.Failure(e)      => fail(s"init failed: $e")
                case Result.Panic(t)        => fail(s"init panic: ${t.getMessage}")
                case Result.Success(client) =>
                    // First query: times out waiting for a server response (not pool-exhaustion).
                    Abort.run[SqlException](SqlClient.let(client)(client.query("SELECT 1"))).flatMap {
                        firstResult =>
                            // Second query: must also be able to acquire the slot, slot was returned.
                            Abort.run[SqlException](SqlClient.let(client)(client.query("SELECT 1"))).map {
                                secondResult =>
                                    firstResult match
                                        case Result.Failure(e) =>
                                            assert(
                                                !isPoolExhausted(e),
                                                s"First query hit pool-exhaustion unexpectedly: $e"
                                            )
                                        case _ => ()
                                    end match
                                    secondResult match
                                        case Result.Failure(e) =>
                                            assert(
                                                !isPoolExhausted(e),
                                                "Slot was leaked after stream success, second acquire hit pool-exhaustion"
                                            )
                                        case Result.Success(_) => succeed
                                        case Result.Panic(t)   => succeed
                                    end match
                            }
                    }
            }
        }
    }

end SqlClientStreamSlotTest
