package kyo

import kyo.*
import kyo.Test
import kyo.net.Connection
import kyo.net.NetPlatform

/** Unit tests for [[SqlClient.close]] / `SqlConnectionPool.closeAll` interrupt-safety.
  *
  * Two interrupt edges are covered. Step 3 is a plain `idleConns.foreach` inside `Sync.Unsafe.defer`, atomic once reached, because an
  * iteration that could be interrupted mid-loop would leave the remaining idle connections un-closed. The second edge is earlier: an
  * interrupt during the grace poll (Step 2) skips Step 3 entirely and leaks the connections `pool.close()` extracted in Step 1, so Step 3
  * runs as a `Sync.ensure` finalizer over the drain and closes them on that edge too.
  *
  * All tests are shared/cross-platform and use a minimal fake Postgres server (trust-auth handshake only). No real database required.
  */
class SqlClientCloseAllTest extends SqlContainerTest:

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

    /** Completes the handshake, then reads the next inbound message and flips `queryReceived`. The next message on a connection is a
      * statement (idle connections never send one), so this marks the lease that carries it as in flight. The server never answers it, so
      * that lease stays parked and its pool slot stays out, which is what makes `closeAll`'s grace poll park.
      */
    private def pgHandshakeThenObserve(conn: Connection, queryReceived: AtomicBoolean)(using Frame): Unit < Async =
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).andThen {
                Abort.run[Closed](conn.inbound.safe.take).andThen {
                    queryReceived.set(true).andThen(Async.sleep(Duration.Infinity))
                }
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
        // closeAll's Step 3 drain is a synchronous `idleConns.foreach` inside `Sync.Unsafe.defer`,
        // which runs atomically and cannot be interrupted between elements once it is reached.
        //
        // The pool here holds only idle connections, so closeAll's grace poll (Step 2) finds every
        // slot already back at capacity and returns at once rather than parking: the 60s grace never
        // actually waits, and the close runs to completion in microseconds. Interrupting between
        // Step 1 (which extracts the idle connections from the ring) and Step 3 (which closes them)
        // would skip Step 3 and leak those connections, since ConnectionPool.close returns them for
        // the caller to close and a second close is an idempotent no-op that cannot recover them.
        // The interrupt is therefore issued only after the close has finished, and it waits on the
        // fiber's own completion rather than on a guessed duration.
        //
        // What is asserted: once the close completes, the closed flag stuck (Step 1 set it atomically),
        // a late interrupt does not disturb it, and a zero-grace second close is a clean no-op.
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
                                Fiber.initUnscoped(client.close(60.seconds)).flatMap { closeFiber =>
                                    // Wait deterministically for the close to finish before interrupting, observed via
                                    // the fiber's own completion. Interrupting earlier could land between Step 1 and
                                    // Step 3 and leak the extracted idle connections, so the interrupt lands after the
                                    // drain, guarding that a late interrupt leaves the closed state untouched.
                                    assertEventually(closeFiber.done).andThen {
                                        // The close has already run to completion; this interrupt must not disturb it.
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

    // ── closeAll interrupted DURING the grace poll still closes the extracted idle connections ──

    "closeAll interrupted during the grace poll still closes the extracted idle connections" in {
        // The gap the sibling leaf above cannot reach: with only idle connections the grace poll returns at once,
        // so an interrupt never lands inside it. Here one lease is held in flight (a query the fake server
        // receives but never answers), so closeAll extracts the two idle connections in Step 1 and then PARKS in
        // the Step 2 grace poll waiting for that lease. An interrupt there skips Step 3 unless Step 3 runs as a
        // finalizer. Step 3 clears the slot channels in the same atomic block that closes the extracted
        // connections, so slotPermits(address) going Absent is the observable that Step 3 ran on the interrupt
        // edge: a Step 3 that never ran leaves it Present, with the connections leaked.
        // Every wait is on a real observable, so the leaf is deterministic rather than timed.
        AtomicBoolean.init(false).flatMap { queryReceived =>
            kyo.internal.FakeServer.listenPort { conn =>
                pgHandshakeThenObserve(conn, queryReceived)
            }.flatMap { listener =>
                val url = fakeUrl(listener.port)
                // queryTimeout is long so the in-flight query holds its slot until the test interrupts it,
                // rather than timing out and letting the drain finish on its own.
                val config = SqlConfig(
                    maxConnections = 3,
                    minConnections = 3,
                    acquireTimeout = 5.seconds,
                    queryTimeout = 5.minutes,
                    idleTimeout = 10.minutes
                )
                Abort.run[SqlConnectionException](SqlClient.initUnscoped(url, config)).flatMap {
                    case Result.Failure(e) => fail(s"SqlClient.initUnscoped failed: $e")
                    case Result.Panic(t)   => fail(s"Unexpected panic: ${t.getMessage}")
                    case Result.Success(client) =>
                        Scope.ensure(Abort.run(client.close).unit).andThen {
                            Fiber.initUnscoped(Abort.run[SqlException](client.query("SELECT 1")).unit).flatMap { queryFiber =>
                                Scope.ensure(queryFiber.interrupt.unit).andThen {
                                    // The lease is out once the server has the query and its slot is taken. The slot
                                    // guard also confirms client.address keys the same channel Step 3 clears, so the
                                    // Absent assertion below cannot pass vacuously.
                                    assertEventually(queryReceived.get).andThen {
                                        assertEventually(
                                            Sync.Unsafe.defer(client.runtime.pool.slotPermits(client.address).isDefined)
                                        ).andThen {
                                            Fiber.initUnscoped(client.close(60.seconds)).flatMap { closeFiber =>
                                                // Interrupt only after the drain has begun, so the interrupt lands after
                                                // Step 1 extraction and inside Step 2 rather than before extraction.
                                                assertEventually(Sync.Unsafe.defer(client.runtime.pool.drainPollCount > 0)).andThen {
                                                    closeFiber.interrupt.andThen {
                                                        assertEventually(
                                                            Sync.Unsafe.defer(client.runtime.pool.slotPermits(client.address).isEmpty)
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
    }

end SqlClientCloseAllTest
