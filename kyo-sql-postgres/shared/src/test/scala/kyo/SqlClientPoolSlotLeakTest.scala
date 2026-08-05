package kyo

import kyo.*
import kyo.Test
import kyo.net.Connection
import kyo.net.NetPlatform

/** Asserts that a lease gives its slot permit back on every edge it can leave by: success, a typed `Abort`, a panic, and an interrupt. A fifth
  * leaf asserts that `close` returns promptly after a typed failure rather than waiting out its grace period.
  *
  * The panic leaf drives a real JVM throw rather than `Abort.panic`, and that choice is what makes it a fourth edge instead of a
  * restatement of the second. `Scope.run` composes two finalizer mechanisms, `Sync.ensure(finalizer.close)` inside `Abort.run[Any]`
  * (`Scope.scala:133-141`). An encoded panic never throws, so it flows to `Abort.run[Any]`, lands in `Result.Panic`, and closes the
  * finalizer through the same explicit `finalizer.close(result.error)` continuation a typed `Abort` takes; a leaf built on `Abort.panic`
  * really would only change which `Result` case arrives. A real throw propagates instead, and `Safepoint.ensuring` catches it, runs the
  * finalizer, and rethrows (`Safepoint.scala:152-161`), all before `Abort.run`'s `handleCatching` turns it into a `Result` at all. That is
  * a distinct path, it is the one ordinary code inside a lease reaches, and this is the only leaf here that takes it. The interrupt leaf
  * also reaches `Sync.ensure`, but by fiber teardown rather than by a thrown exception.
  *
  * That leaf is also the only permit assertion on the `pool.lease` path. The other three send `client.query`, which is
  * `pool.leaseStatement`: retry and the per-statement timeout wrapped around the same `withSlot`. `usePinnedConnection` has neither, and it
  * is what a transaction, an advisory lock, and a session reset all lease through.
  *
  * The assertion is the permit count itself, read through `SqlConnectionPool.slotPermits`, rather than an inferential signal. Inferring a
  * leak from an acquire timeout (configure `maxConnections = 1`, fail once, acquire again, and treat an acquire timeout as the signal)
  * could not fail: `getOrCreateSlotChan` clamps the channel to `maxConns.max(2)`, so the pool holds two permits, every scenario leaks at
  * most one, and the second acquire always finds the spare, leaving the leak these leaves exist to catch live and green. An inferential
  * signal that depends on the capacity arithmetic breaks silently when the arithmetic changes; the count does not.
  *
  * Each leaf reads the count in the SAME fiber that ran the lease, which is the whole point. `Sync.ensure`'s finalizer does fire
  * eventually, at fiber completion, so a leaf that ended its fiber first would see every permit back and prove nothing about the edge it
  * was testing.
  *
  * All leaves are shared/cross-platform against a fake server. No real database required.
  */
class SqlClientPoolSlotLeakTest extends SqlContainerTest:

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

    /** Completes the trust-auth handshake then closes, so the next statement fails typed with a connection error. */
    private def pgHandshakeThenClose(conn: Connection)(using Frame): Unit < Async =
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).andThen {
                // Unsafe: kyo-net Connection.close is unsafe-tier; closes the raw socket without suspending.
                Sync.Unsafe.defer(conn.close())
            }
        }

    /** Completes trust-auth and then never answers, so a statement hangs until its own timeout or an interrupt. */
    private def pgHandshakeThenHang(conn: Connection)(using Frame): Unit < Async =
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).andThen {
                Async.sleep(Duration.Infinity)
            }
        }

    private def fakeUrl(port: Int): String =
        s"postgres://testuser:testpass@127.0.0.1:$port/testdb"

    private def slotConfig(
        maxConnections: Int = 1,
        queryTimeout: Duration = 200.millis,
        acquireTimeout: Duration = 500.millis
    ): SqlConfig =
        SqlConfig(
            maxConnections = maxConnections,
            acquireTimeout = acquireTimeout,
            queryTimeout = queryTimeout,
            idleTimeout = 10.minutes
        )

    /** Permits available right now, and the capacity they must return to.
      *
      * Absence fails the leaf rather than reading as a number, and what that avoids is a false PASS, not a false
      * alarm. Both accessors begin by looking the address up in the same map, so they answer Present together or
      * Absent together. Defaulting each to -1 would set `available` and `capacity` BOTH to -1 whenever no slot
      * channel exists, and `assert(available == capacity)` would read that as a match and pass, reporting green
      * exactly when nothing could be measured. "Could not read the count" and "the count matched" must not be one
      * observable.
      */
    private def permits(client: SqlClient)(using Frame, kyo.test.AssertScope): (Int, Int) < Sync =
        Sync.Unsafe.defer {
            // Unsafe: both accessors read a Channel size through Sync.Unsafe.evalOrThrow, as the pool's own
            // observability accessors do.
            (client.runtime.pool.slotPermits(client.url.address), client.runtime.pool.slotCapacity(client.url.address)) match
                case (Present(available), Present(capacity)) => (available, capacity)
                case _ =>
                    fail(s"no slot channel exists for ${client.url.address}, so no statement ever reached the pool")
        }

    /** Opens a client against `handler`, runs `f`, and closes it. */
    private def withFake[A](handler: Connection => Unit < Async, config: SqlConfig)(
        f: SqlClient => A < (Async & Abort[SqlException] & Scope)
    )(using Frame, kyo.test.AssertScope): A < (Async & Abort[Any] & Scope) =
        kyo.internal.FakeServer.listenPort(handler).flatMap { listener =>
            SqlClient.initUnscoped(fakeUrl(listener.port), config).flatMap { client =>
                Scope.ensure(Abort.run(client.close).unit).andThen(f(client))
            }
        }

    // ── One permit per edge ───────────────────────────────────────────────────

    "a lease that succeeds returns its permit" in {
        Scope.run {
            withFake(pgHandshakeThenHang, slotConfig(queryTimeout = 150.millis)) { client =>
                // The fake server never answers, so the statement ends on its own queryTimeout. That is still a
                // resolved lease: the permit is owed back either way, and this leaf is the baseline the others
                // are compared against.
                Abort.run[SqlException](DB.run(client)(client.query("SELECT 1"))).andThen {
                    permits(client).map { case (available, capacity) =>
                        assert(
                            available == capacity,
                            s"every permit must be back after a resolved lease, had $available of $capacity"
                        )
                    }
                }
            }
        }
    }

    "a lease whose statement fails with a typed SqlException returns its permit" in {
        Scope.run {
            withFake(pgHandshakeThenClose, slotConfig()) { client =>
                // The ordinary failure path: the server drops the connection, the statement aborts typed, and the
                // caller handles it and carries on. This is the edge `Sync.ensure` does not cover, because a typed
                // abort handled outside the ensured region parks the finalizer until the fiber ends.
                Abort.run[SqlException](DB.run(client)(client.query("SELECT 1"))).flatMap { outcome =>
                    assert(outcome.isFailure, s"the fake server closes, so the statement must fail: $outcome")
                    permits(client).map { case (available, capacity) =>
                        assert(
                            available == capacity,
                            s"a typed statement failure must not strand a permit, had $available of $capacity"
                        )
                    }
                }
            }
        }
    }

    "a lease whose body panics returns its permit" in {
        Scope.run {
            withFake(pgHandshakeThenHang, slotConfig()) { client =>
                // A real JVM throw, not `Abort.panic`; the class scaladoc records why the two are not
                // interchangeable here.
                //
                // `usePinnedConnection` rather than `transaction`, because it is the narrowest real caller of
                // `withSlot`: `pool.lease` takes the permit, `acquireAndRun` opens the session, and then the body
                // runs. `transaction` would put its own `Abort.run[Any]` between the throw and the pool, so the
                // throw would arrive already normalised into a `Result.Panic` and this would be the encoded-panic
                // leaf wearing a throw. It does still get normalised one layer in, by `onLease`'s `Scope.run`,
                // and that is the point rather than a caveat: the permit lives one layer further out, so it has
                // to come back whichever layer caught the throw.
                val panicking: Unit < Sync = Sync.defer(throw new RuntimeException("panic inside a lease body"))
                Abort.run[SqlException](client.usePinnedConnection(_ => panicking)).flatMap { outcome =>
                    outcome match
                        case Result.Panic(t) =>
                            assert(
                                t.getMessage == "panic inside a lease body",
                                s"the body's own throwable must reach the caller unchanged, got: $t"
                            )
                        case other =>
                            fail(s"the body throws, so the lease must end in a panic: $other")
                    end match
                    permits(client).map { case (available, capacity) =>
                        assert(
                            available == capacity,
                            s"a panic must not strand a permit, had $available of $capacity"
                        )
                    }
                }
            }
        }
    }

    "a lease interrupted mid-statement returns its permit" in {
        Latch.initWith(1) { slotHeld =>
            Scope.run {
                withFake(
                    conn =>
                        Abort.run[Closed](conn.inbound.safe.take).andThen {
                            Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).andThen {
                                slotHeld.release.andThen(Async.sleep(Duration.Infinity))
                            }
                        },
                    slotConfig(queryTimeout = Duration.Infinity)
                ) { client =>
                    Fiber.initUnscoped(
                        Abort.run[SqlException](DB.run(client)(client.query("SELECT 1")))
                    ).flatMap { queryFiber =>
                        // Wait until the server has seen the startup message, so the interrupt lands with the
                        // permit held rather than before the lease started.
                        slotHeld.await.andThen {
                            queryFiber.interrupt.flatMap { interrupted =>
                                // A refused interrupt means the fiber already settled, and the settled value is the
                                // diagnosis: the fake never answers the query, so any completion here is a failure
                                // that raced the interrupt, and its shape names the path that produced it.
                                (if interrupted then ((): Unit < (Async & Abort[Nothing]))
                                 else
                                     queryFiber.get.map { settled =>
                                         fail(
                                             s"the query fiber must be interruptible while parked on the fake server, but it settled with: $settled"
                                         )
                                     }
                                ).andThen {
                                    // The reclaim runs on its own carrier, so the permit can come back a moment after
                                    // the interrupt is acknowledged. Poll briefly rather than reading once.
                                    untilPermitsRestored(client).map { case (available, capacity) =>
                                        assert(
                                            available == capacity,
                                            s"an interrupt must not strand a permit, had $available of $capacity"
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

    /** Reads the permit count until it reaches capacity or 2 seconds pass, so an asynchronous return is not read too early. */
    private def untilPermitsRestored(client: SqlClient)(using Frame, kyo.test.AssertScope): (Int, Int) < Async =
        Loop(0) { attempt =>
            permits(client).flatMap { case (available, capacity) =>
                if available >= capacity || attempt >= 200 then Loop.done((available, capacity))
                else Async.sleep(10.millis).andThen(Loop.continue(attempt + 1))
            }
        }

    // ── The user-visible consequence: close stops burning its whole grace ─────

    "close returns promptly after a typed statement failure instead of burning its grace" in {
        // The second symptom of a stranded permit, and the one a user actually notices. `closeAll`'s drain waits for
        // every slot channel to return to capacity, so one permit that never comes back makes `close(gracePeriod)`
        // wait out the entire grace before force-closing, which costs every leaf here the whole grace period.
        //
        // Asserted as elapsed time, which is the exception to this suite's own preference for counts, because here
        // the duration IS the property: the permit count is already covered above, and what this adds is that the
        // drain observes it. The threshold is deliberately loose, a third of the grace, so it fails on "waited out
        // the grace" rather than on machine speed.
        val grace     = 6.seconds
        val threshold = 2.seconds
        Scope.run {
            kyo.internal.FakeServer.listenPort(pgHandshakeThenClose).flatMap { listener =>
                SqlClient.initUnscoped(fakeUrl(listener.port), slotConfig()).flatMap { client =>
                    Abort.run[SqlException](DB.run(client)(client.query("SELECT 1"))).andThen {
                        Clock.stopwatch.flatMap { sw =>
                            client.close(grace).andThen {
                                sw.elapsed.map { waited =>
                                    assert(
                                        waited < threshold,
                                        s"close waited ${waited.show} of a ${grace.show} grace, so the drain is still " +
                                            "waiting on a permit that was never returned"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Pool exhaustion is not an instrument for this property, and no leaf here uses it. Driving capacity+1 handled
    // failures through one fiber and asserting the next acquire is not an acquire timeout stays GREEN even when
    // every typed failure strands its permit: each loop iteration crosses a scheduler boundary and a parked
    // finalizer drains when the task completes, so the leak does not accumulate across iterations the way it does
    // within one continuation. The permit count above is the detector; exhaustion is a consequence with a
    // scheduler-dependent path to it, and consequences make poor instruments when the mechanism is observable.

end SqlClientPoolSlotLeakTest
