package kyo

import kyo.*
import kyo.Test
import kyo.internal.SqlTestContainers
import kyo.net.Connection

/** Pins the two ways a caller stops a statement that is taking too long.
  *
  * There is no cancel handle to hold and no `cancel(handle)` to call: the fiber running the statement IS the handle. Wrapping it in
  * [[kyo.Async.timeout]] bounds it, and interrupting its [[kyo.Fiber]] stops it, and both work because every method on a client suspends
  * rather than blocking a thread. These scenarios drive a fake server that accepts the TCP connection and then never answers, so the statement
  * is genuinely in flight when the timeout fires and when the interrupt lands.
  */
class SqlClientInterruptTest extends SqlContainerTest:

    /** Accepts the startup packet and answers nothing, leaving the handshake suspended for as long as the test needs. */
    private def silentHandler(conn: Connection)(using Frame): Unit < Async =
        Abort.run[Closed](conn.inbound.safe.take).unit

    private def fakeUrl(port: Int): String =
        s"postgres://testuser:testpass@127.0.0.1:$port/testdb"

    /** No warm-up, so opening the client touches no socket and only the statement under test does. */
    private val config: SqlConfig =
        SqlConfig(maxConnections = 2, minConnections = 0, acquireTimeout = 30.seconds, queryTimeout = 30.seconds)

    private def withSilentClient[A](f: SqlClient => A < (Async & Abort[SqlException] & Scope))(using
        Frame
    ): A < (Async & Abort[SqlException] & Abort[kyo.net.NetException] & Scope) =
        kyo.internal.FakeServer.listenPort(silentHandler).flatMap { listener =>
            SqlClient.initUnscoped(fakeUrl(listener.port), config).flatMap { client =>
                Scope.ensure(Abort.run(client.close).unit).andThen(f(client))
            }
        }

    "Async.timeout bounds a statement that never completes" in {
        withSilentClient { client =>
            Abort.run[Timeout](
                Async.timeout(200.millis)(Abort.run[SqlException](client.query("SELECT 1")))
            ).map {
                case Result.Failure(_: Timeout) => succeed
                case other                      => fail(s"Expected the query to be bounded by Async.timeout, got $other")
            }
        }
    }

    "interrupting the statement's fiber stops it" in {
        withSilentClient { client =>
            Latch.initWith(1) { started =>
                Fiber.initUnscoped(
                    started.release.andThen(Abort.run[SqlException](client.query("SELECT 1")))
                ).flatMap { queryFiber =>
                    started.await.andThen {
                        queryFiber.interrupt.map { interrupted =>
                            assert(interrupted, "interrupting the fiber running a statement must stop it")
                        }
                    }
                }
            }
        }
    }

    /** A caller interrupted out of a connect must not strand the socket that connect owns.
      *
      * `transport.connect` returns a fiber owning a descriptor from the instant it is called, so the finalizer that closes it has to be
      * registered BEFORE the launch. While it was registered after, an interrupt landing in that window left nobody owning the socket:
      * the connect still succeeded, handed its connection to a promise the interrupted computation never read, and the descriptor stayed
      * ESTABLISHED for the life of the process along with the server-side peer it was connected to.
      *
      * The window is narrow, so ONE interrupt proves nothing: eight of 192 connects took it. The scenario is repeated until the failure
      * is reliable rather than probabilistic, which is what makes this a guard instead of a coin flip.
      *
      * Each cycle runs in its OWN `Scope.run`. Without that, the fake server and the client accumulate for the whole leaf and the
      * harness's descriptor check reports the fixture's own growth rather than anything about the interrupt path. Asserting that every
      * interrupt actually landed is what keeps the loop honest, since a cycle whose statement was never in flight exercises nothing;
      * the descriptor check is what catches the leak itself.
      */
    "an interrupted connect strands no descriptor" in {
        Kyo.foreachDiscard(Chunk.from(1 to 100)) { _ =>
            Scope.run {
                withSilentClient { client =>
                    Latch.initWith(1) { started =>
                        Fiber.initUnscoped(
                            started.release.andThen(Abort.run[SqlException](client.query("SELECT 1")))
                        ).flatMap { queryFiber =>
                            started.await.andThen {
                                queryFiber.interrupt.map { interrupted =>
                                    assert(interrupted, "each cycle must genuinely interrupt an in-flight statement")
                                }
                            }
                        }
                    }
                }
            }
        }.andThen(succeed)
    }

    /** The scoped lease behind `streamQuery` takes its permit through the same forked, timeout-bounded take as a statement, so a caller
      * interrupted on that take's join must leave the permit owned by the give-back registered before the take. A stranded permit is
      * observable through `close`: it waits its whole `closeGrace` for a permit that never comes back, so each cycle, close included, is
      * bounded well under that grace and a leak fails the cycle by name rather than by the suite timeout.
      */
    "an interrupted stream lease strands no permit" in {
        Kyo.foreachDiscard(Chunk.from(1 to 20)) { i =>
            Abort.run[Timeout] {
                Async.timeout(10.seconds) {
                    Scope.run {
                        withSilentClient { client =>
                            Latch.initWith(1) { started =>
                                Fiber.initUnscoped(
                                    started.release.andThen(Abort.run[SqlException](Scope.run(client.streamQuery("SELECT 1").run)))
                                ).flatMap { fiber =>
                                    started.await.andThen {
                                        fiber.interrupt.map { interrupted =>
                                            assert(interrupted, "each cycle must genuinely interrupt an in-flight stream lease")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }.map { r =>
                assert(r.isSuccess, s"cycle $i: the client's close waited on a permit the interrupted lease never gave back: $r")
            }
        }.andThen(succeed)
    }

    /** The shared Postgres container's URL, with `application_name` set so the sessions a leaf opens are its own in `pg_stat_activity`.
      * kyo-pod's HttpClient is scoped to the call so the container lookup leaves no pooled socket behind.
      */
    private def containerUrl[A](appName: String)(f: String => A < (Async & Abort[SqlException] & Scope))(using
        Frame
    ): A < (Async & Abort[SqlException | ContainerException] & Scope) =
        val cfg = ContainerPredef.Postgres.Config.default
        HttpClient.init().flatMap { httpClient =>
            HttpClient.let(httpClient) {
                SqlTestContainers.getOrInit(SqlTestContainers.containers, "postgres")(
                    SqlTestContainers.initSingleton(ContainerPredef.Postgres.buildContainerConfig(cfg), "postgres")
                ).flatMap { container =>
                    container.mappedPort(cfg.port).flatMap { port =>
                        f(s"postgres://${cfg.username}:${cfg.password}@${container.host}:$port/${cfg.database}?application_name=$appName")
                    }
                }
            }
        }
    end containerUrl

    /** `Runtime.init` warms the pool under an inner `Scope.run` whose finalizer closes the pool only on a failure edge; the clean edge hands
      * the pool on through that run's drain await and two more steps before `openScoped` registers the client's close. An interrupt on any
      * of those leaves a pool holding `minConnections` established sessions that nothing closes. The sessions are counted on the server by
      * the `application_name` the URL sets: a close still in flight drains within the bound, a leaked pool's sessions never go away.
      */
    "an interrupt landing as the warmed pool is handed over strands no session" in {
        val rounds = 30
        val warm   = SqlConfig(maxConnections = 2, minConnections = 2, acquireTimeout = 10.seconds, queryTimeout = 10.seconds)
        containerUrl("kyo-sql-warm-orphan") { url =>
            SqlClient.init(url.replace("kyo-sql-warm-orphan", "kyo-sql-warm-probe"), config).map { probe =>
                def sessions: Int < (Async & Abort[SqlException]) =
                    probe.query("SELECT count(*)::int FROM pg_stat_activity WHERE application_name = 'kyo-sql-warm-orphan'")
                        .map(rows => rows(0).decode[Int](0))
                Loop.indexed { i =>
                    if i >= rounds then Loop.done(succeed)
                    else
                        for
                            fiber <-
                                Fiber.initUnscoped(Abort.run[SqlException](Scope.run(SqlClient.init(url, warm).andThen(Async.never[Unit]))))
                            _    <- Async.delay(i.millis)(fiber.interrupt)
                            _    <- fiber.getResult
                            gone <- Abort.run[Timeout](Async.timeout(5.seconds)(assertEventually(sessions.map(_ == 0))))
                        yield
                            assert(gone.isSuccess, s"round $i: sessions of the interrupted client are still open on the server")
                            Loop.continue
                }
            }
        }
    }

    /** `withAdvisoryLock` takes the lock in a server round trip and registers its release in the step after the reply lands. An interrupt
      * landing between the grant and that step leaves the lock on the pooled session, which the pool reclaims and hands to the next
      * borrower still locked. The lock is read back from `pg_locks` through a second client once the interrupted fiber has settled: a
      * release still in flight clears it within the bound, a lock nobody registered stays for the session's life.
      */
    "an interrupt landing as the advisory lock is granted strands no lock" in {
        val rounds = 40
        val key    = 7340031L
        val one    = SqlConfig(maxConnections = 1, minConnections = 0, acquireTimeout = 10.seconds, queryTimeout = 10.seconds)
        containerUrl("kyo-sql-lock-orphan") { url =>
            SqlClient.init(url, one).map { locker =>
                SqlClient.init(url.replace("kyo-sql-lock-orphan", "kyo-sql-lock-probe"), config).map { probe =>
                    def held: Int < (Async & Abort[SqlException]) =
                        probe.query(s"SELECT count(*)::int FROM pg_locks WHERE locktype = 'advisory' AND classid = 0 AND objid = $key")
                            .map(rows => rows(0).decode[Int](0))
                    Loop.indexed { i =>
                        if i >= rounds then Loop.done(succeed)
                        else
                            for
                                fiber <- Fiber.initUnscoped(Abort.run[SqlException](locker.withAdvisoryLock(key)(Async.never[Unit])))
                                _     <- Async.delay((i % 4).millis)(fiber.interrupt)
                                _     <- fiber.getResult
                                gone  <- Abort.run[Timeout](Async.timeout(5.seconds)(assertEventually(held.map(_ == 0))))
                            yield
                                assert(gone.isSuccess, s"round $i: the advisory lock is still held on the pooled session")
                                Loop.continue
                    }
                }
            }
        }
    }

    /** Here a second client holds the lock so `pg_advisory_lock` blocks; the interrupt is
      * requested while it waits, then the holder releases so the grant lands into the abandoned fiber before its release
      * registers. Deterministic: the window is the holder's real lock duration, not a timer race.
      */
    "an interrupt landing as a contended advisory lock is granted strands no lock".pendingUntilFixed(
        "withAdvisoryLock grants the lock in a server round trip and registers Scope.ensure(release) only in the next step; " +
            "an interrupt in that window strands the lock on the pooled session, which the pool hands to its next borrower still locked"
    ) in {
        val key = 7340032L
        val one = SqlConfig(maxConnections = 1, minConnections = 0, acquireTimeout = 15.seconds, queryTimeout = 15.seconds)
        containerUrl("kyo-sql-lockdet-orphan") { url =>
            SqlClient.init(url, one).map { locker =>
                SqlClient.init(url.replace("kyo-sql-lockdet-orphan", "kyo-sql-lockdet-hold"), one).map { holder =>
                    SqlClient.init(url.replace("kyo-sql-lockdet-orphan", "kyo-sql-lockdet-probe"), config).map { probe =>
                        def count(granted: String): Int < (Async & Abort[SqlException]) =
                            probe.query(
                                s"SELECT count(*)::int FROM pg_locks WHERE locktype = 'advisory' AND classid = 0 AND objid = $key AND $granted"
                            ).map(rows => rows(0).decode[Int](0))
                        Latch.initWith(1) { lockHeld =>
                            Latch.initWith(1) { release =>
                                for
                                    holderFiber <- Fiber.initUnscoped(
                                        Abort.run[SqlException](holder.withAdvisoryLock(key)(lockHeld.release.andThen(release.await)))
                                    )
                                    _      <- lockHeld.await
                                    _      <- assertEventually(count("granted").map(_ == 1))
                                    aFiber <- Fiber.initUnscoped(Abort.run[SqlException](locker.withAdvisoryLock(key)(Async.never[Unit])))
                                    _      <- assertEventually(count("NOT granted").map(_ >= 1))
                                    _      <- aFiber.interrupt
                                    _      <- release.release
                                    _      <- holderFiber.getResult
                                    _      <- aFiber.getResult
                                    gone   <- Abort.run[Timeout](Async.timeout(5.seconds)(assertEventually(count("granted").map(_ == 0))))
                                yield assert(gone.isSuccess, "the contended advisory lock leaked on the pooled session after the interrupt")
                            }
                        }
                    }
                }
            }
        }
    }

    /** `closeAll` / `Runtime.close` extract the idle ring and install the force-close of what they extracted with no poll
      * between, so a stop landing there cannot abandon connections the pool no longer holds, out of the ring and unclosed.
      * The ring drain is microseconds, so the leaf spins to a staggered offset and requests the stop directly; a round
      * whose stop landed before the close began proves nothing, one whose close began must see the server's session count
      * for the client's `application_name` reach zero within the bound.
      */
    "an interrupt landing as close extracts the idle ring strands no session".notJs.notWasm in {
        val rounds = 120
        val warm   = SqlConfig(maxConnections = 2, minConnections = 2, acquireTimeout = 10.seconds, queryTimeout = 10.seconds)
        containerUrl("kyo-sql-close-orphan") { url =>
            SqlClient.init(url.replace("kyo-sql-close-orphan", "kyo-sql-close-probe"), config).map { probe =>
                def sessions: Int < (Async & Abort[SqlException]) =
                    probe.query("SELECT count(*)::int FROM pg_stat_activity WHERE application_name = 'kyo-sql-close-orphan'")
                        .map(rows => rows(0).decode[Int](0))
                Loop.indexed { i =>
                    if i >= rounds then Loop.done(succeed)
                    else
                        val closing = new java.util.concurrent.atomic.AtomicBoolean(false)
                        for
                            client <- SqlClient.initUnscoped(url, warm)
                            _      <- assertEventually(sessions.map(_ == 2))
                            fiber  <- Fiber.initUnscoped(Sync.defer(closing.set(true)).andThen(Abort.run[SqlException](client.close)))
                            _      <- Sync.Unsafe.defer {
                                val bound = java.lang.System.nanoTime() + 200_000_000L
                                while !closing.get() && java.lang.System.nanoTime() < bound do ()
                                val target = java.lang.System.nanoTime() + (i % 60) * 10_000L
                                while java.lang.System.nanoTime() < target do ()
                                discard(fiber.unsafe.interrupt())
                            }
                            _     <- fiber.getResult
                            began <- client.isClosed
                            gone  <-
                                if began then
                                    Abort.run[Timeout](Async.timeout(5.seconds)(assertEventually(sessions.map(_ == 0)))).map(_.isSuccess)
                                else Abort.run[SqlException](client.close).andThen(assertEventually(sessions.map(_ == 0))).andThen(true)
                        yield
                            assert(gone, s"round $i: sessions of the client whose close was stopped are still open on the server")
                            Loop.continue
                        end for
                }
            }
        }
    }

    /** A lease owns what the pool hands it from the step it is handed: a reservation is claimed into a flag whose release was registered
      * before the ring was asked, and a pooled connection's exit registers in the step that takes custody of it. A stop landing on any
      * poll in between must not leave a slot the pool cannot hand out again, or after enough such stops a pool of two refuses every
      * acquire. The leaf stops leases against one pool of two, at staggered sub-millisecond offsets from the step before each, and asks
      * the pool for a statement within its acquire budget after every one: the first refused acquire is the failure. Closing the pool
      * afterwards must complete and leave no session behind.
      */
    "leases stopped at staggered offsets leave a pool that still serves and closes clean".notJs.notWasm in {
        val rounds = 200
        val two    = SqlConfig(maxConnections = 2, minConnections = 0, acquireTimeout = 2.seconds, queryTimeout = 10.seconds)
        containerUrl("kyo-sql-lease-stops") { url =>
            SqlClient.init(url.replace("kyo-sql-lease-stops", "kyo-sql-lease-probe"), config).map { probe =>
                def sessions: Int < (Async & Abort[SqlException]) =
                    probe.query("SELECT count(*)::int FROM pg_stat_activity WHERE application_name = 'kyo-sql-lease-stops'")
                        .map(rows => rows(0).decode[Int](0))
                SqlClient.initUnscoped(url, two).map { client =>
                    Loop.indexed { i =>
                        if i >= rounds then Loop.done
                        else
                            val leasing = new java.util.concurrent.atomic.AtomicBoolean(false)
                            for
                                fiber <- Fiber.initUnscoped(
                                    Sync.defer(leasing.set(true)).andThen(Abort.run[SqlException](client.query("SELECT 1")))
                                )
                                _ <- Sync.Unsafe.defer {
                                    val bound = java.lang.System.nanoTime() + 200_000_000L
                                    while !leasing.get() && java.lang.System.nanoTime() < bound do ()
                                    val target = java.lang.System.nanoTime() + (i % 200) * 10_000L
                                    while java.lang.System.nanoTime() < target do ()
                                    discard(fiber.unsafe.interrupt())
                                }
                                _ <- fiber.getResult
                                n <- sessions
                            yield
                                assert(n <= 2, s"round $i: the server holds $n sessions for a pool of two")
                                Loop.continue
                            end for
                    }.andThen {
                        // The stops run back to back: a slot lost to one of them is only visible once the pool has none left to
                        // hand out, which a statement between the rounds would mask by re-pooling what the round did release.
                        for
                            served <- Abort.run[SqlException](client.query("SELECT 7").map(rows => rows(0).decode[Int](0)))
                            closed <- Abort.run[Timeout](Async.timeout(10.seconds)(Abort.run[SqlException](client.close)))
                            gone   <- Abort.run[Timeout](Async.timeout(5.seconds)(assertEventually(sessions.map(_ == 0))))
                        yield
                            assert(served.contains(7), s"the pool did not serve a statement after the stopped leases: $served")
                            assert(closed.isSuccess, "the pool's close did not complete within its bound after the stopped leases")
                            assert(gone.isSuccess, "a session outlived the pool's close after the stopped leases")
                        end for
                    }
                }
            }
        }
    }

end SqlClientInterruptTest
