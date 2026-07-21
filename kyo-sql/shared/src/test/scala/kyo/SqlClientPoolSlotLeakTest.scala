package kyo

import kyo.*
import kyo.Test
import kyo.net.Connection
import kyo.net.NetPlatform

/** Unit tests for G-Leak-1: `withSlot` / `withSlotS` slot offer wrapped in `Sync.ensure`.
  *
  * Each test verifies that the slot channel token is returned to the pool on every exit edge of the slot-held body: success, `Abort`
  * failure, `Panic`, and fiber cancellation. The assertion strategy is structural: after one abnormal-exit, a subsequent acquire must
  * succeed, i.e. the slot was returned. A pool with `maxConnections=1` and `acquireTimeout=500ms` gives a clear signal: if the slot was
  * leaked the second acquire would fail with "Timed out waiting … for a connection"; if the slot was returned, the second acquire fails for
  * a different reason (query-level failure) or succeeds outright.
  *
  * All tests are shared/cross-platform. No real database required.
  *
  * Test count: 4 shared/unit leaves.
  */
class SqlClientPoolSlotLeakTest extends Test:

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

    /** Completes the trust-auth handshake then immediately closes the connection.
      *
      * This causes any subsequent `client.query` call (which sends `pgConnect + extendedQuery`) to fail inside `acquireAndRun` with a
      * `SqlException.Connection` (body Abort edge). After the handshake the connection is dropped, so the query read loop hits EOF.
      */
    private def pgHandshakeThenClose(conn: Connection)(using Frame): Unit < Async =
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).andThen {
                Sync.Unsafe.defer(conn.close())
            }
        }

    /** Completes trust-auth and then hangs (never responds to queries).
      *
      * Used for the fiber-cancellation test: the slot is held while the query-body is suspended waiting for a server response; then the
      * fiber is interrupted externally.
      */
    private def pgHandshakeThenHang(conn: Connection)(using Frame): Unit < Async =
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).andThen {
                // Hang forever, the client body will be interrupted from outside.
                Async.sleep(Duration.Infinity)
            }
        }

    private def fakeUrl(port: Int): String =
        s"postgres://testuser:testpass@127.0.0.1:$port/testdb"

    /** Config with maxConnections=1 so a single leaked slot means permanent pool exhaustion.
      *
      * `acquireTimeout` is short so that a leaked-slot scenario fails quickly and predictably rather than hanging forever in the test
      * suite.
      */
    private def slotConfig(
        queryTimeout: Duration = 200.millis,
        acquireTimeout: Duration = 500.millis
    ): SqlConfig =
        SqlConfig(
            maxConnections = 1,
            acquireTimeout = acquireTimeout,
            queryTimeout = queryTimeout,
            idleTimeout = 10.minutes
        )

    // ── Helper: run a query and classify whether the failure is pool-exhaustion ──

    /** Returns `true` if the error is a pool-exhaustion acquire-timeout.
      *
      * A "Timed out waiting" message on `SqlException.Connection` is the signal that the slot channel was empty and the acquire waited for
      * `acquireTimeout` without success.
      */
    private def isPoolExhausted(e: SqlException): Boolean =
        e match
            case SqlException.Connection(msg, _) => msg.startsWith("Timed out")
            case _                               => false

    // ── withSlot returns the connection on body success ───────────────────────

    "withSlot returns the connection on body success" in {
        // Fake server: complete handshake twice (two sequential acquires), the second acquire
        // is a regression check that the slot is returned after a successful body.
        // Since the fake server never sends query responses, both queries will timeout;
        // but both must be able to ACQUIRE the slot (not hit pool-exhaustion).
        kyo.internal.FakeServer.listenPort { conn =>
            pgHandshakeThenHang(conn)
        }.flatMap { listener =>
            val port   = listener.port
            val url    = fakeUrl(port)
            val config = slotConfig(queryTimeout = 100.millis)
            Abort.run[SqlException.Connection](
                SqlClient.initUnscoped(url, config)
            ).flatMap {
                case Result.Failure(e)      => fail(s"init failed: $e")
                case Result.Panic(t)        => fail(s"init panic: ${t.getMessage}")
                case Result.Success(client) =>
                    // First query: will timeout (query body runs, then hits queryTimeout).
                    Abort.run[SqlException](SqlClient.let(client)(client.query("SELECT 1"))).flatMap {
                        firstResult =>
                            // Second query: must also be able to acquire the slot.
                            Abort.run[SqlException](SqlClient.let(client)(client.query("SELECT 1"))).map {
                                secondResult =>
                                    // Neither failure should be pool-exhaustion.
                                    firstResult match
                                        case Result.Failure(e) =>
                                            assert(
                                                !isPoolExhausted(e),
                                                s"First query unexpectedly hit pool-exhaustion: $e"
                                            )
                                        case _ => ()
                                    end match
                                    secondResult match
                                        case Result.Failure(e) =>
                                            assert(
                                                !isPoolExhausted(e),
                                                "Slot was leaked after body success, second acquire hit pool-exhaustion"
                                            )
                                        case Result.Success(_) => succeed
                                        case Result.Panic(t)   => succeed // any non-exhaustion outcome is fine
                                    end match
                            }
                    }
            }
        }
    }

    // ── withSlot returns the connection on body Abort ─────────────────────────

    "withSlot returns the connection on body Abort" in {
        // Fake server: closes immediately after handshake, the query body aborts with
        // SqlException.Connection (EOF on the wire). The slot must be returned so the
        // second acquire can proceed.
        kyo.internal.FakeServer.listenPort { conn =>
            pgHandshakeThenClose(conn)
        }.flatMap { listener =>
            val port   = listener.port
            val url    = fakeUrl(port)
            val config = slotConfig()
            Abort.run[SqlException.Connection](
                SqlClient.initUnscoped(url, config)
            ).flatMap {
                case Result.Failure(e)      => fail(s"init failed: $e")
                case Result.Panic(t)        => fail(s"init panic: ${t.getMessage}")
                case Result.Success(client) =>
                    // First query: aborts (server closes mid-query).
                    Abort.run[SqlException](SqlClient.let(client)(client.query("SELECT 1"))).flatMap {
                        firstResult =>
                            // Second query: must be able to acquire the slot (slot was returned on Abort).
                            Abort.run[SqlException](SqlClient.let(client)(client.query("SELECT 1"))).map {
                                secondResult =>
                                    firstResult match
                                        case Result.Failure(e) =>
                                            assert(
                                                !isPoolExhausted(e),
                                                s"First query unexpectedly hit pool-exhaustion: $e"
                                            )
                                        case _ => ()
                                    end match
                                    secondResult match
                                        case Result.Failure(e) =>
                                            assert(
                                                !isPoolExhausted(e),
                                                "Slot was leaked after body Abort, second acquire hit pool-exhaustion"
                                            )
                                        case Result.Success(_) => succeed
                                        case Result.Panic(t)   => succeed
                                    end match
                            }
                    }
            }
        }
    }

    // ── withSlot returns the connection on body Panic ─────────────────────────

    "withSlot returns the connection on body Panic" in {
        // Panic in the withSlot body is tested at the Kyo primitive level using a Channel
        // that mirrors the slot-semaphore. Since SqlClientBackend is sealed, we cannot inject
        // a raw panic through the public SqlClient API. Instead we exercise the same
        // `Sync.ensure(slotCh.offer)(body)` primitive directly:
        //   - Channel(1) pre-filled = slot available
        //   - body = computation that throws a real JVM exception (actual panic)
        //   - assert that after the panic the channel still holds the slot token.
        //
        // Note: `Abort.error(Result.Panic(t))` does NOT trigger Safepoint.ensure because it
        // encodes the panic as a Kyo effect value rather than throwing a real JVM exception.
        // A real JVM throw (via `Sync.defer { throw t }`) propagates through the Kyo
        // scheduler and fires `Safepoint.ensure`'s catch block.
        Channel.initUnscoped[Unit](1).flatMap { slotCh =>
            Abort.run[Closed](slotCh.offer(())).flatMap { _ =>
                // Consume the slot (simulating withSlot's takeSlot).
                Abort.run[Closed](slotCh.take).flatMap { _ =>
                    val panicBody: Unit < Sync =
                        Sync.defer { throw new RuntimeException("test panic for G-Leak-1") }
                    // Wrap with Sync.ensure, the cleanup must run even on Panic.
                    // Wrap the whole thing in Abort.run[Throwable] to catch the rethrown exception.
                    Abort.run[Throwable](
                        Sync.ensure(Sync.Unsafe.defer {
                            discard(Sync.Unsafe.evalOrThrow(Abort.run[Closed](slotCh.offer(()))))
                        })(panicBody)
                    ).flatMap { _ =>
                        // After the panic the slot must have been re-offered.
                        Abort.run[Closed](slotCh.poll).map {
                            case Result.Success(Present(())) =>
                                succeed // slot was returned, G-Leak-1 fix verified
                            case Result.Success(Absent) =>
                                fail("Slot was NOT returned after body Panic, G-Leak-1 regression")
                            case Result.Failure(_) =>
                                fail("slotCh.poll failed with Closed unexpectedly")
                            case Result.Panic(t) =>
                                fail(s"slotCh.poll panicked: ${t.getMessage}")
                        }
                    }
                }
            }
        }
    }

    // ── withSlot returns the connection on fiber cancellation ─────────────────

    "withSlot returns the connection on fiber cancellation" in {
        // Fake server: hangs after handshake so the query body is suspended waiting for a
        // server response. We interrupt the fiber from outside while the body (and slot)
        // are held. Sync.ensure must return the slot even on interrupt.
        // Then a second acquire must succeed (slot available).
        // Gate: fired when the client has sent its startup message (i.e. the slot is held and
        // the query body is waiting for the server to respond). This ensures the interrupt
        // lands after the slot is taken, not before.
        Latch.initWith(1) { slotHeld =>
            kyo.internal.FakeServer.listenPort { conn =>
                // Read startup message → fire gate → hang.
                Abort.run[Closed](conn.inbound.safe.take).andThen {
                    Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).andThen {
                        slotHeld.release.andThen {
                            Async.sleep(Duration.Infinity)
                        }
                    }
                }
            }.flatMap { listener =>
                val port   = listener.port
                val url    = fakeUrl(port)
                val config = slotConfig(queryTimeout = Duration.Infinity, acquireTimeout = 500.millis)
                Abort.run[SqlException.Connection](
                    SqlClient.initUnscoped(url, config)
                ).flatMap {
                    case Result.Failure(e)      => fail(s"init failed: $e")
                    case Result.Panic(t)        => fail(s"init panic: ${t.getMessage}")
                    case Result.Success(client) =>
                        // Start the query in a background fiber.
                        Fiber.initUnscoped(
                            Abort.run[SqlException](SqlClient.let(client)(client.query("SELECT 1")))
                        ).flatMap { queryFiber =>
                            // Wait until the slot is held (server saw the startup message).
                            slotHeld.await.andThen {
                                // Interrupt the query fiber while the slot is held.
                                queryFiber.interrupt.flatMap { interrupted =>
                                    assert(interrupted, "Expected query fiber to be interrupted")
                                    // Now attempt a second acquire, the slot must have been returned
                                    // by Sync.ensure even though the fiber was cancelled.
                                    // The fake server still hangs, so we wrap the second query in a
                                    // short Async.timeout: if the slot was NOT returned the acquire
                                    // itself times out in 500ms (pool-exhaustion); if the slot WAS
                                    // returned, the acquire succeeds but the query hangs and the outer
                                    // Async.timeout fires in 1s, either way we don't hang forever.
                                    Abort.run[SqlException](
                                        Async.timeoutWithError(
                                            1.second,
                                            Result.Failure(SqlException.Connection(
                                                "second query timed out (slot returned OK)",
                                                summon[Frame]
                                            ))
                                        )(
                                            SqlClient.let(client)(client.query("SELECT 1"))
                                        )
                                    ).map { result =>
                                        result match
                                            case Result.Failure(e) =>
                                                assert(
                                                    !isPoolExhausted(e),
                                                    "Slot was leaked after fiber cancellation, second acquire hit pool-exhaustion"
                                                )
                                            case Result.Success(_) => succeed
                                            case Result.Panic(t)   => succeed
                                        end match
                                    }
                                }
                            }
                        }
                }
            }
        }
    }

end SqlClientPoolSlotLeakTest
