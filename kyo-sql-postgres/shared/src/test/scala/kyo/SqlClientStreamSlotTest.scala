package kyo

import kyo.*
import kyo.Test
import kyo.net.Connection
import kyo.net.NetPlatform

/** Pins the pool-side lifetime of a streaming lease, the one [[SqlClient.streamQuery]] takes when no transaction is enclosing.
  *
  * The subject is `SqlConnectionPool.leaseScoped` and it is driven directly, not through `streamQuery`. Two reasons, and the second is the
  * one that matters. Consuming a stream needs a fake server that speaks the whole extended-query protocol, which this fixture does not. And
  * every property here is settled BEFORE the first row is requested: the permit is taken, the connection is opened, and both finalizers are
  * registered, all while `streamQuery`'s consumer has not yet asked for anything. Asserting behind the stream would put the row exchange
  * between the defect and the assertion for no gain. `postgres/CancelIntegrationTest` drives the same path end to end against a container.
  *
  * The lifetime under test is the caller's [[kyo.Scope]], never the pool's own internals. A stream holds one permit and one connection from
  * the moment it acquires them until the consumer's `Scope.run` closes, so each leaf opens an inner `Scope.run` to stand in for that
  * consumer, asserts while it is open, and asserts again after it closes. A property that only holds at one of those two moments is the
  * defect this suite exists to catch: the connection must not go back into the idle ring until the caller's scope closes, or a second
  * caller could poll it and two fibers would share one socket.
  *
  * All leaves are shared/cross-platform against a fake server. No real database required.
  */
class SqlClientStreamSlotTest extends Test:

    // ── Fake Postgres trust-auth handshake bytes ──────────────────────────────

    /** Trust-auth handshake bytes, carrying `processId` in the BackendKeyData.
      *
      * The pid is a parameter rather than a constant because `PostgresSqlConnection.id` IS the backend process id, so a fake server that
      * answers every connection with the same pid makes every connection report the same id. A leaf asserting that two leases were given
      * different connections would then fail on the fixture instead of on the code.
      */
    private def pgAuthOkBytes(processId: Int): Span[Byte] = Span.from(
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
            // BackendKeyData: type='K', length=12, pid=processId, secretKey=0
            'K'.toByte,
            0x00,
            0x00,
            0x00,
            0x0c,
            (processId >>> 24).toByte,
            (processId >>> 16).toByte,
            (processId >>> 8).toByte,
            processId.toByte,
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

    /** Completes trust-auth as `processId` and then hangs, so the connection stays open and every leaf observes the lease's own lifetime
      * rather than a socket teardown.
      */
    private def pgHandshakeThenHang(processId: Int)(conn: Connection)(using Frame): Unit < Async =
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes(processId))).andThen {
                Async.sleep(Duration.Infinity)
            }
        }

    /** Answers each accepted connection with its own process id, counting from 1 in accept order.
      *
      * Deterministic only because the leaf that uses it leases sequentially: the second lease starts after the first handshake finished, so
      * there is exactly one handler running at a time and the counter reads in accept order.
      */
    private def pgHandshakePerSession(pids: AtomicInt)(conn: Connection)(using Frame): Unit < Async =
        pids.incrementAndGet.flatMap(pid => pgHandshakeThenHang(pid)(conn))

    private def fakeUrl(port: Int): String =
        s"postgres://testuser:testpass@127.0.0.1:$port/testdb"

    /** `minConnections` stays at its default 0, so the idle ring is empty when the first lease arrives, which is the state every client is in
      * before its first statement and the state the fresh-connect branch is reached from.
      */
    private def slotConfig(
        maxConnections: Int = 1,
        metricsScope: Maybe[String] = Absent
    ): SqlConfig =
        SqlConfig(
            maxConnections = maxConnections,
            acquireTimeout = 500.millis,
            queryTimeout = 100.millis,
            idleTimeout = 10.minutes,
            metricsScope = metricsScope
        )

    /** Permits available right now, and the capacity they must return to.
      *
      * Absence fails the leaf rather than reading as a number, for the reason its sibling in [[SqlClientPoolSlotLeakTest]] records at
      * length: both accessors look the address up in the same map, so defaulting each to -1 makes them EQUAL whenever no slot channel
      * exists, and the equality assertion passes while measuring nothing.
      */
    private def permits(client: SqlClient)(using Frame, kyo.test.AssertScope): (Int, Int) < Sync =
        Sync.Unsafe.defer {
            // Unsafe: both accessors read a Channel size through Sync.Unsafe.evalOrThrow, as the pool's own
            // observability accessors do.
            (client.runtime.pool.slotPermits(client.url.address), client.runtime.pool.slotCapacity(client.url.address)) match
                case (Present(available), Present(capacity)) => (available, capacity)
                case _ =>
                    fail(s"no slot channel exists for ${client.url.address}, so no lease ever reached the pool")
        }

    /** How many connections the pool has counted back into its idle ring. */
    private def released(client: SqlClient)(using Frame): Long < Sync =
        client.runtime.pool.metrics.connectionsReleased.get

    /** Takes a streaming lease against `client`'s own endpoint, exactly as `SqlClient.streamQuery` does. */
    private def leaseScoped(client: SqlClient)(using Frame): kyo.db.Connection < (Async & Abort[SqlException] & Scope) =
        client.runtime.pool.leaseScoped(client.url.address, client.url.password, client.config)

    /** Opens a client against `handler`, runs `f`, and closes it. */
    private def withFake[A](handler: Connection => Unit < Async, config: SqlConfig)(
        f: SqlClient => A < (Async & Abort[SqlException] & Scope)
    )(using Frame): A < (Async & Abort[Any] & Scope) =
        kyo.internal.FakeServer.listenPort(handler).flatMap { listener =>
            SqlClient.initUnscoped(fakeUrl(listener.port), config).flatMap { client =>
                Scope.ensure(Abort.run(client.close).unit).andThen(f(client))
            }
        }

    // ── the permit is held for the whole stream ───────────────────────────────

    "a stream lease holds its permit until the caller's scope closes" in {
        // The permit assertion for the streaming path, which is a different mechanism from the statement path's.
        // `withSlot` takes and returns the permit around one body, and
        // `SqlClientPoolSlotLeakTest` pins that on four edges. `leaseScoped` instead takes the permit and
        // registers its return on the CALLER's scope, so the permit is owed back not when a call returns but
        // when the consumer's `Scope.run` closes. A leaf that only read the count at the end could not tell
        // the two apart, so this one reads it while the lease is open as well.
        Scope.run {
            withFake(pgHandshakeThenHang(1), slotConfig()) { client =>
                Scope.run {
                    leaseScoped(client).flatMap { _ =>
                        permits(client).map { case (available, capacity) =>
                            assert(capacity == 1, s"the configured maxConnections must be honoured exactly, capacity was $capacity")
                            assert(
                                available == 0,
                                s"a stream holds its permit for as long as it reads, had $available of $capacity"
                            )
                        }
                    }
                }.andThen {
                    permits(client).map { case (available, capacity) =>
                        assert(
                            available == capacity,
                            s"closing the caller's scope must return the permit, had $available of $capacity"
                        )
                    }
                }
            }
        }
    }

    // ── the connection does not go back until the stream is done ──────────────

    "a stream's fresh connection stays out of the idle ring until the caller's scope closes" in {
        // The connection's exit finalizer is registered OUTSIDE the reservation-release scope, which `Scope.run`
        // closes as soon as its body produces a value. That value is the connection, so a finalizer registered
        // inside would run the exit decision immediately with no error, see a healthy session, and put the
        // connection into the idle ring before the stream had read a single row: a concurrent lease could poll it
        // and two fibers would share one socket, idle eviction could close it mid-stream, and an interrupt would
        // find no finalizer left to run the cancel chain.
        //
        // Read as a count at two moments rather than as a consequence, because every consequence above needs
        // either a second caller or a timer to become visible, and both sit between the defect and the
        // assertion. The release counter moves at the defect itself.
        val config = slotConfig(metricsScope = Present("kyo.sql.streamslot.release"))
        Scope.run {
            withFake(pgHandshakeThenHang(1), config) { client =>
                Scope.run {
                    leaseScoped(client).flatMap { _ =>
                        released(client).map { count =>
                            assert(
                                count == 0L,
                                s"the stream still holds this connection, so nothing may have gone back to the ring yet, released=$count"
                            )
                        }
                    }
                }.andThen {
                    released(client).map { count =>
                        assert(
                            count == 1L,
                            s"closing the caller's scope must put the connection back exactly once, released=$count"
                        )
                    }
                }
            }
        }
    }

    "two streams open at once are given two different connections" in {
        // The same defect read from the ring instead of from the counter, and the shape a user would hit: with
        // the connection back in the ring the moment it exists, the second lease polls it and both streams
        // read the same socket. The ids are the fake server's backend process ids, handed out in accept order,
        // so `Chunk(1, 2)` says two sessions were opened and `Chunk(1, 1)` says one was handed out twice.
        //
        // Sequential rather than concurrent: nesting the second lease inside the first holds both at once
        // without a latch, and the accept order is then fixed, so there is no timing assumption anywhere.
        val config = slotConfig(maxConnections = 2)
        Scope.run {
            AtomicInt.init(0).flatMap { pids =>
                withFake(pgHandshakePerSession(pids), config) { client =>
                    Scope.run {
                        leaseScoped(client).flatMap { first =>
                            leaseScoped(client).map { second =>
                                assert(
                                    Chunk(first.id, second.id) == Chunk(1L, 2L),
                                    s"two open streams must hold two sessions, got ids ${first.id} and ${second.id}"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── the lease instruments a stream owes ───────────────────────────────────

    // The gauge and the counter are two leaves rather than two assertions in one, and that is not tidiness. An
    // `assert` ends its leaf, so the second of two assertions is never evaluated on a run where the first fails,
    // which means it can never be watched failing. The two instruments move together, so one leaf would leave the
    // second assertion permanently unproven.

    "a stream counts as one open lease for as long as it holds its connection" in {
        // `decideExit` is shared with the statement path and decrements `leases_in_flight` on every exit edge it
        // can take, and nothing resets the gauge. So a streaming path that never increments it drives the gauge to
        // -1 after one finished stream and -N after N: monotonically wrong rather than transiently off.
        //
        // Read at both moments for the same reason as the leaves above: the value while the lease is open says
        // the increment happened, and the value after it closes says the two edges are paired.
        val config = slotConfig(metricsScope = Present("kyo.sql.streamslot.gauge"))
        Scope.run {
            withFake(pgHandshakeThenHang(1), config) { client =>
                Scope.run {
                    leaseScoped(client).flatMap { _ =>
                        client.runtime.pool.metrics.leasesInFlight.collect.map { held =>
                            assert(held == 1.0d, s"one open stream is one open lease, the gauge read $held")
                        }
                    }
                }.andThen {
                    client.runtime.pool.metrics.leasesInFlight.collect.map { back =>
                        assert(back == 0.0d, s"closing the caller's scope must bring the gauge back to zero, it read $back")
                    }
                }
            }
        }
    }

    "a stream's connection is counted when it is handed out, not when it goes back" in {
        // The counter half of the same pairing. `decideExit` counts the release through `recordRelease`, so a
        // stream whose connection is not counted when it is handed out appears in `connections_released` and never
        // in `connections_acquired`: the two counters disagree by one per stream, in the direction that reads as
        // connections being returned that were never taken.
        val config = slotConfig(metricsScope = Present("kyo.sql.streamslot.acquired"))
        Scope.run {
            withFake(pgHandshakeThenHang(1), config) { client =>
                Scope.run {
                    leaseScoped(client).flatMap { _ =>
                        client.runtime.pool.metrics.connectionsAcquired.get.map { acquired =>
                            assert(
                                acquired == 1L,
                                s"a stream's connection must be counted when it is handed out, acquired=$acquired"
                            )
                        }
                    }
                }
            }
        }
    }

    // ── the failed-connect edge, which the caller-scoped exit must not disturb ─

    "a stream lease whose connect fails gives back both its permit and its reservation" in {
        // The reservation release and the connection's exit have different lifetimes, and this leaf is what
        // keeps them separated. The first ends the moment the connection exists, because from then on the ring
        // accounts for it as in use rather than as in flight; the second ends when the caller is done reading.
        // Placing the exit finalizer on the caller's scope must leave the reservation release where it is, and a
        // regression there is not visible in the permit count.
        //
        // Two failures rather than one, because the ring's reservation capacity is `maxConnections.max(2)`:
        // one stranded reservation still leaves room for the next attempt. Two fill it, `tryReserve` then
        // refuses against an empty ring, and no event can ever release it, so the third lease would spin to
        // its `acquireTimeout` instead of connecting. That livelock is the observable.
        val config = slotConfig(maxConnections = 1)
        Scope.run {
            AtomicInt.init(0).flatMap { accepted =>
                withFake(
                    conn =>
                        accepted.incrementAndGet.flatMap { n =>
                            // Unsafe: closing an accepted socket before answering the startup message, which is
                            // how this fixture makes a connect fail rather than a statement.
                            if n <= 2 then Sync.Unsafe.defer(conn.close())
                            else pgHandshakeThenHang(1)(conn)
                        },
                    config
                ) { client =>
                    val attempt: Result[SqlException, Unit] < (Async & Scope) =
                        Abort.run[SqlException](Scope.run(leaseScoped(client).unit))
                    attempt.flatMap { first =>
                        attempt.flatMap { second =>
                            Abort.run[SqlException](Scope.run(leaseScoped(client).map(_.id))).flatMap { third =>
                                permits(client).map { case (available, capacity) =>
                                    assert(
                                        first.isFailure && second.isFailure,
                                        s"a server that closes before the handshake must fail the connect, got $first and $second"
                                    )
                                    assert(
                                        third == Result.Success(1L),
                                        s"both abandoned reservations must be back, or this lease dies on acquireTimeout: $third"
                                    )
                                    assert(
                                        available == capacity,
                                        s"a failed connect must not keep the permit either, had $available of $capacity"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end SqlClientStreamSlotTest
