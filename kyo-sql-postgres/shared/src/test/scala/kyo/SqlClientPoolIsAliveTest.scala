package kyo

import java.util.concurrent.CopyOnWriteArrayList
import kyo.*
import kyo.Test
import kyo.net.Connection
import kyo.net.NetPlatform

/** Pins what the pool does to a connection it takes out of the idle ring, before it lends it to a caller.
  *
  * The subject is `SqlConnectionPool.healthy` and the ring's `isAlive` callback, which split one decision in two. The callback answers from
  * a position that cannot suspend, so all it may do is read the socket flag. The configured `connectionTestQuery` is a server round trip
  * and runs one layer out, inside `acquireOrReserve`, where a suspension is legal on every platform.
  *
  * That split is what this suite guards. Running the probe inside the callback through a blocking bridge raises
  * `UnsupportedOperationException` on JS and Wasm; the callback would catch it and answer "not alive", so a configured probe would silently
  * discard and reopen every healthy connection on both platforms and the query would never reach the server. Every leaf here is shared and
  * cross-platform for that reason: such a defect is invisible to a JVM-only run.
  *
  * The observables are counts rather than consequences: messages the fake server received, TCP accepts it saw, permits in the slot channel,
  * and the pool's own `connections_discarded` counter. A probe failure is only visible as a reconnect several layers later, and the counter
  * moves at the defect itself. Each leaf that reads a counter takes its own `metricsScope`, because `Stat` registers a counter per scope
  * path and two clients sharing a scope share the counter.
  *
  * All leaves use a minimal fake Postgres server (TCP listener plus wire-protocol responses). No real database required.
  *
  * Wire-protocol notes:
  *   - Startup: client sends a startup packet; server replies with AuthenticationOk + BackendKeyData + ReadyForQuery.
  *   - Simple query: client sends `Q`; server replies with CommandComplete + ReadyForQuery.
  *   - `isAlive` fires when `pool.poll` fetches an idle connection, so on the second and later use of a connection.
  *   - When `connectionTestQuery` is `Present(sql)`, the pool sends `sql` through `simpleExecute` from `acquireOrReserve`, bounded by
  *     `queryTimeout`.
  *   - When `connectionTestQuery` is `Absent`, the pool checks `conn.isOpen` and sends nothing.
  */
class SqlClientPoolIsAliveTest extends SqlContainerTest:

    // ── Wire bytes ────────────────────────────────────────────────────────────

    /** Minimal Postgres startup response (trust auth). */
    private val pgAuthOkBytes: Span[Byte] = Span.from(
        Array[Byte](
            // AuthenticationOk: 'R', length=8, authType=0
            'R'.toByte,
            0x00,
            0x00,
            0x00,
            0x08,
            0x00,
            0x00,
            0x00,
            0x00,
            // BackendKeyData: 'K', length=12, pid=1, key=0
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
            // ReadyForQuery: 'Z', length=5, status='I'
            'Z'.toByte,
            0x00,
            0x00,
            0x00,
            0x05,
            'I'.toByte
        )
    )

    /** CommandComplete("BEGIN") + ReadyForQuery, success response to any simple query.
      *
      * Wire layout: CommandComplete type='C', Int32(10) = 4+6, "BEGIN\0"; ReadyForQuery type='Z', Int32(5), 'I'. Using "BEGIN" as the
      * command tag (6 bytes with null) gives length 10, matching SqlClientLogTest's known-good bytes.
      */
    private val pgSimpleOkBytes: Span[Byte] = Span.from(
        Array[Byte](
            // CommandComplete: 'C', length=10, "BEGIN\0"
            'C'.toByte,
            0x00,
            0x00,
            0x00,
            0x0a,
            'B'.toByte,
            'E'.toByte,
            'G'.toByte,
            'I'.toByte,
            'N'.toByte,
            0x00,
            // ReadyForQuery: 'Z', length=5, status='I'
            'Z'.toByte,
            0x00,
            0x00,
            0x00,
            0x05,
            'I'.toByte
        )
    )

    // ── Log capture ───────────────────────────────────────────────────────────

    /** Synchronous log sink, capturing every entry as a (level, message) pair.
      *
      * Synchronous (a `CopyOnWriteArrayList`) rather than buffered, so a leaf reads what was written and not what a drain has got round to.
      * Accepts every level so a leaf can assert the level as well as the text.
      */
    class TestLogSink extends Log.Unsafe:
        private val entries = new CopyOnWriteArrayList[(Log.Level, String)]()

        def name: String                       = "SqlClientPoolIsAliveTestLogSink"
        def withName(name: String): Log.Unsafe = this
        def level: Log.Level                   = Log.Level.trace

        def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.trace, msg.toString)))
        def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.trace, msg.toString)))
        def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.debug, msg.toString)))
        def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.debug, msg.toString)))
        def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.info, msg.toString)))
        def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.info, msg.toString)))
        def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.warn, msg.toString)))
        def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.warn, msg.toString)))
        def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.error, msg.toString)))
        def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.error, msg.toString)))

        /** Every captured entry, as an immutable snapshot. */
        def captured: Chunk[(Log.Level, String)] =
            Chunk.from(entries.toArray(Array.empty[(Log.Level, String)]))
    end TestLogSink

    /** Installs a fresh [[TestLogSink]] for the duration of `body`, and hands back the sink together with the body's value. */
    private def withLogCapture[A, S](body: => A < S)(using Frame): (TestLogSink, A) < (S & Async) =
        val sink = new TestLogSink
        Log.let(Log(sink))(body.map(a => Log.flush.andThen(a))).map(a => (sink, a))

    // ── Helpers ───────────────────────────────────────────────────────────────

    private def fakeUrl(port: Int): String =
        s"postgres://testuser:testpass@127.0.0.1:$port/testdb"

    private def baseConfig: SqlConfig =
        SqlConfig(
            maxConnections = 2,
            acquireTimeout = 5.seconds,
            queryTimeout = 5.seconds,
            idleTimeout = 10.minutes
        )

    /** One connection at a time and a configured probe, which is the shape every probe leaf below wants.
      *
      * `maxConnections` is 1 so the slot channel holds exactly one permit and a leak is a difference of one rather than a fraction of a
      * pool. `minConnections` stays at its default 0, so the idle ring is empty until the first statement releases into it, which is the
      * state the probe path is reached from.
      */
    private def probeConfig(
        queryTimeout: Duration = 5.seconds,
        metricsScope: Maybe[String] = Absent,
        connectionTestQuery: Maybe[String] = Present("SELECT 1")
    ): SqlConfig =
        SqlConfig(
            maxConnections = 1,
            acquireTimeout = 5.seconds,
            queryTimeout = queryTimeout,
            idleTimeout = 10.minutes,
            metricsScope = metricsScope,
            connectionTestQuery = connectionTestQuery
        )

    /** Fake server: startup OK, then respond to every subsequent message with CommandComplete+ReadyForQuery.
      *
      * `onMessage` is called once per subsequent message (after startup) so tests can count how many messages were sent.
      */
    private def pgLoopHandler(
        conn: Connection,
        onMessage: Unit < Async = ()
    )(using Frame): Unit < Async =
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).andThen {
                def loop: Unit < Async =
                    Abort.run[Closed](conn.inbound.safe.take).flatMap {
                        case Result.Success(_) =>
                            onMessage.andThen(Abort.run[Closed](conn.outbound.safe.put(pgSimpleOkBytes)).andThen(loop))
                        case _ => ()
                    }
                loop
            }
        }

    /** Fake server: startup OK, then answers the first `answers` subsequent messages and closes on the next one. */
    private def pgCloseAfter(answers: Int, onMessage: Unit < Async = ())(conn: Connection)(using Frame): Unit < Async =
        pgAfterStartup(conn) { n =>
            onMessage.andThen {
                if n < answers then Abort.run[Closed](conn.outbound.safe.put(pgSimpleOkBytes)).andThen(Loop.continue(n + 1))
                // Unsafe: kyo-net Connection.close is unsafe-tier; closes the raw socket without suspending.
                else Sync.Unsafe.defer(conn.close()).andThen(Loop.done(()))
            }
        }

    /** Fake server: startup OK, then answers the first `answers` subsequent messages and goes silent on the next one.
      *
      * Silent rather than closed, and the socket is left open, so the client waits: the wire shape of a probe that never comes back. What
      * ends the wait is the client's own `queryTimeout` or an interrupt, which is exactly the pair of edges the leaves using this exercise.
      * `onSilence` fires once the unanswered message has been read, so a leaf can interrupt at a moment it knows rather than a moment it
      * guesses.
      */
    private def pgSilentAfter(answers: Int, onSilence: Unit < Async = ())(conn: Connection)(using Frame): Unit < Async =
        pgAfterStartup(conn) { n =>
            if n < answers then Abort.run[Closed](conn.outbound.safe.put(pgSimpleOkBytes)).andThen(Loop.continue(n + 1))
            else onSilence.andThen(Loop.done(()))
        }

    /** Answers the startup packet, then hands each subsequent message's zero-based index to `onMessage` until it stops the loop. */
    private def pgAfterStartup(conn: Connection)(onMessage: Int => Loop.Outcome[Int, Unit] < Async)(using Frame): Unit < Async =
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).andThen {
                Loop(0) { n =>
                    Abort.run[Closed](conn.inbound.safe.take).flatMap {
                        case Result.Success(_) => onMessage(n)
                        case _                 => Loop.done(())
                    }
                }
            }
        }

    /** Binds `handler` behind an accept counter and opens a client against it, handing `f` the client and the accept count.
      *
      * Accepts are counted because a discarded connection is only visible from the wire as a reconnect, and the reconnect is one TCP
      * accept.
      */
    private def withProbeServer[A](config: SqlConfig)(handler: Connection => Unit < Async)(
        f: (SqlClient, AtomicInt) => A < (Async & Abort[Any] & Scope)
    )(using Frame): A < (Async & Abort[Any] & Scope) =
        AtomicInt.initWith(0) { accepts =>
            kyo.internal.FakeServer.listenPort(conn => accepts.incrementAndGet.unit.andThen(handler(conn))).flatMap { listener =>
                SqlClient.initUnscoped(fakeUrl(listener.port), config).flatMap { client =>
                    Scope.ensure(Abort.run(client.close).unit).andThen(f(client, accepts))
                }
            }
        }

    /** Runs one statement and folds its failure into the value, so a leaf asserts on the pool rather than on the statement.
      *
      * Whether the statement itself succeeds is not the property under test in any leaf below: a probe failure is meant to be invisible to
      * the caller, which gets a fresh connection, and the leaves that drive a server dropping connections cannot say in advance whether the
      * replacement survives long enough to answer.
      */
    private def statement(client: SqlClient)(using Frame): Unit < (Async & Abort[Timeout]) =
        Abort.run[SqlException](DB.run(client)(Async.timeout(5.seconds)(client.executeRaw("SELECT 1")))).unit

    /** Permits available right now, and the capacity they must return to. */
    private def permits(client: SqlClient)(using Frame, kyo.test.AssertScope): (Int, Int) < Sync =
        // Unsafe: both accessors read a Channel size through Sync.Unsafe.evalOrThrow, as the pool's own
        // observability accessors do.
        Sync.Unsafe.defer {
            (client.runtime.pool.slotPermits(client.url.address), client.runtime.pool.slotCapacity(client.url.address)) match
                case (Present(available), Present(capacity)) => (available, capacity)
                case _ =>
                    fail(s"no slot channel exists for ${client.url.address}, so no lease ever reached the pool")
        }

    /** How many connections the pool has closed instead of pooling. */
    private def discarded(client: SqlClient)(using Frame): Long < Sync =
        client.runtime.pool.metrics.connectionsDiscarded.get

    /** Reads the discard counter until it reaches `target` or 2 seconds pass, so an edge resolved off the calling fiber is not read early. */
    private def untilDiscarded(client: SqlClient, target: Long)(using Frame): Long < Async =
        Loop(0) { attempt =>
            discarded(client).flatMap { count =>
                if count >= target || attempt >= 200 then Loop.done(count)
                else Async.sleep(10.millis).andThen(Loop.continue(attempt + 1))
            }
        }

    /** Takes a streaming lease against `client`'s own endpoint, exactly as `SqlClient.streamQuery` does. */
    private def leaseScoped(client: SqlClient)(using Frame): kyo.db.Connection < (Async & Abort[SqlException] & Scope) =
        client.runtime.pool.leaseScoped(client.url.address, client.url.password, client.config)

    // ── Tests ─────────────────────────────────────────────────────────────────

    // ── isAlive uses connectionTestQuery when configured ─────────────────────

    "isAlive uses connectionTestQuery 'SELECT 1' when configured" in {
        // The fake server counts messages after startup. When connectionTestQuery is Present, the pool
        // sends the test query before lending the connection for the user's second call.
        // Expected messages per connection: query-1 from user, testQuery from the pool, query-2 from user = 3.
        // Without testQuery (Absent arm), it would be query-1 + query-2 = 2.
        Scope.run {
            AtomicInt.initWith(0) { msgCount =>
                kyo.internal.FakeServer.listenPort { conn =>
                    pgLoopHandler(conn, onMessage = msgCount.incrementAndGet.unit)
                }.flatMap { listener =>
                    val port   = listener.port
                    val url    = fakeUrl(port)
                    val config = baseConfig.copy(connectionTestQuery = Present("SELECT 1"))
                    Abort.run[SqlConnectionException](
                        SqlClient.init(url, config)
                    ).flatMap {
                        case Result.Success(client) =>
                            // First call: new connection (no probe), sends query-1.
                            Abort.run[SqlException](
                                DB.run(client)(
                                    Async.timeout(5.seconds)(client.executeRaw("SELECT 1"))
                                )
                            ).andThen {
                                // Second call: connection retrieved from the ring, probe fires, then query-2.
                                Abort.run[SqlException](
                                    DB.run(client)(
                                        Async.timeout(5.seconds)(client.executeRaw("SELECT 1"))
                                    )
                                )
                            }.andThen {
                                msgCount.get.map { count =>
                                    // Exactly three, and the exact count is what makes this leaf able to fail: with no
                                    // retrySchedule, minConnections 0 and two sequential statements, the wire carries
                                    // query-1, the probe and query-2 and nothing else. A `>=` could not tell that apart
                                    // from a fourth stray message.
                                    assert(count == 3, s"Expected exactly 3 server messages (query + testQuery + query), got $count")
                                }
                            }
                        case Result.Failure(e) =>
                            fail(s"Unexpected connection failure: $e")
                        case Result.Panic(t) =>
                            fail(s"Unexpected panic: ${t.getMessage}")
                    }
                }
            }
        }
    }

    // ── the Absent default reaches no server at all ──────────────────────────

    "isAlive checks the socket only, sending nothing, when connectionTestQuery is Absent" in {
        // When Absent, isAlive checks conn.isOpen only, no network round-trip.
        // The server receives only the user's messages (no extra test-query message).
        // Two user calls → 2 messages total.
        Scope.run {
            AtomicInt.initWith(0) { msgCount =>
                kyo.internal.FakeServer.listenPort { conn =>
                    pgLoopHandler(conn, onMessage = msgCount.incrementAndGet.unit)
                }.flatMap { listener =>
                    val port   = listener.port
                    val url    = fakeUrl(port)
                    val config = baseConfig.copy(connectionTestQuery = Absent)
                    Abort.run[SqlConnectionException](
                        SqlClient.init(url, config)
                    ).flatMap {
                        case Result.Success(client) =>
                            Abort.run[SqlException](
                                DB.run(client)(
                                    Async.timeout(5.seconds)(client.executeRaw("SELECT 1"))
                                )
                            ).andThen {
                                Abort.run[SqlException](
                                    DB.run(client)(
                                        Async.timeout(5.seconds)(client.executeRaw("SELECT 1"))
                                    )
                                )
                            }.andThen {
                                msgCount.get.map { count =>
                                    // Exactly 2 messages: one per user call, no extra test-query round-trip.
                                    assert(count == 2, s"Expected exactly 2 server messages with Absent testQuery, got $count")
                                }
                            }
                        case Result.Failure(e) =>
                            fail(s"Unexpected connection failure: $e")
                        case Result.Panic(t) =>
                            fail(s"Unexpected panic: ${t.getMessage}")
                    }
                }
            }
        }
    }

    // ── a connection that fails the probe leaves the pool ────────────────────

    "a connection whose probe fails is replaced, so the next statement gets a fresh socket" in {
        // Read as TCP accepts, which is the wire-level consequence and cannot be satisfied vacuously. A leaf that
        // called `succeed` in both arms of a match on the second statement's outcome would pass whether or not the
        // probe ever ran.
        //
        // Two accepts: the original connection, and the reconnect the discard forced. One accept means the
        // pooled connection was handed straight back out, which is what happened on JS and Wasm while the
        // probe ran through a blocking bridge that cannot work there.
        val config = probeConfig(metricsScope = Present("kyo.sql.isalive.replace"))
        Scope.run {
            withProbeServer(config)(pgCloseAfter(1)) { (client, accepts) =>
                statement(client).andThen(statement(client)).andThen {
                    accepts.get.map { count =>
                        assert(count == 2, s"a failed probe must force exactly one reconnect, the server accepted $count connection(s)")
                    }
                }
            }
        }
    }

    "a failed probe does not leak the pool slot" in {
        // The connection is out of the ring and owned by nobody while the probe runs, so the discard and the
        // loop back to poll happen with the permit still held. A permit stranded here is invisible until a
        // later acquire times out, which is why this reads the count directly.
        val config = probeConfig(metricsScope = Present("kyo.sql.isalive.slot"))
        Scope.run {
            withProbeServer(config)(pgCloseAfter(1)) { (client, _) =>
                statement(client).andThen(statement(client)).andThen {
                    permits(client).map { case (available, capacity) =>
                        assert(capacity == 1, s"the configured maxConnections must be honoured exactly, capacity was $capacity")
                        assert(available == capacity, s"a failed probe must not strand a permit, had $available of $capacity")
                    }
                }
            }
        }
    }

    "a failed probe discards the connection exactly once" in {
        // The guard against destroying on two paths. The finalizer owns the destroy; a `Result.Failure` arm
        // that destroyed as well would close the socket twice and count one eviction as two, and the counter
        // is where that is visible. It is also the discriminator against a probe run in the ring's own
        // callback, which would evict the connection there and record nothing at all.
        val config = probeConfig(metricsScope = Present("kyo.sql.isalive.discard"))
        Scope.run {
            withProbeServer(config)(pgCloseAfter(1)) { (client, _) =>
                statement(client).andThen(statement(client)).andThen {
                    discarded(client).map { count =>
                        assert(count == 1L, s"one failed probe must discard exactly one connection, counter read $count")
                    }
                }
            }
        }
    }

    "a probe that never answers fails typed under queryTimeout, and the reason is logged" in {
        // `bounded` raises SqlConnectionQueryTimeoutException on expiry, and the message carries the budget
        // that was exceeded. Folding `runAndBlock(queryTimeout)`'s empty return through `.getOrElse(false)`
        // would make an expiry indistinguishable from a connection that answered "dead" and record it nowhere.
        //
        // The server answers startup and the user's first statement, then reads the probe and never replies,
        // leaving the socket open, so nothing but the client's own budget can end the wait.
        val config = probeConfig(queryTimeout = 300.millis, metricsScope = Present("kyo.sql.isalive.timeout"))
        Scope.run {
            withProbeServer(config)(pgSilentAfter(1)) { (client, _) =>
                withLogCapture(statement(client).andThen(statement(client))).flatMap { case (sink, _) =>
                    discarded(client).map { count =>
                        assert(count == 1L, s"a probe that timed out must discard the connection, counter read $count")
                        val probeLines = sink.captured.filter(_._2.contains("connectionTestQuery failed"))
                        assert(probeLines.size == 1, s"the probe failure must be logged exactly once, captured ${probeLines.size} line(s)")
                        val (level, message) = probeLines.head
                        assert(level == Log.Level.debug, s"the probe failure is a lifecycle event, logged at $level")
                        assert(
                            message.contains("Query exceeded the configured timeout"),
                            s"the logged reason must name the timeout that fired, got: $message"
                        )
                    }
                }
            }
        }
    }

    "the scoped acquisition path probes a pooled connection too" in {
        // `acquireOrReserve` has two callers: the statement path and the scoped one `streamQuery` takes.
        // Placing the probe inside `acquireOrReserve` is what covers both, and this is the leaf that says so.
        // The scoped path sends no statement of its own, so the single message the server sees IS the probe.
        val config = probeConfig(metricsScope = Present("kyo.sql.isalive.scoped"))
        Scope.run {
            AtomicInt.initWith(0) { msgCount =>
                withProbeServer(config)(pgCloseAfter(0, onMessage = msgCount.incrementAndGet.unit)) { (client, accepts) =>
                    // First lease: fresh connection, returned to the idle ring when this inner scope closes.
                    Scope.run(leaseScoped(client).unit).andThen {
                        // Second lease: the ring hands the connection back, so the probe runs and the server
                        // closes on it.
                        Scope.run(leaseScoped(client).unit)
                    }.andThen {
                        msgCount.get.flatMap { messages =>
                            accepts.get.flatMap { connects =>
                                discarded(client).map { count =>
                                    assert(
                                        messages == 1,
                                        s"the scoped path must send the probe and nothing else, server saw $messages message(s)"
                                    )
                                    assert(connects == 2, s"the failed probe must force exactly one reconnect, server accepted $connects")
                                    assert(count == 1L, s"the scoped path must discard the probed connection once, counter read $count")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "an interrupt during the probe destroys the connection instead of leaking it" in {
        // Because the probe suspends across a round trip, the connection is held while it belongs to neither
        // the ring nor a caller, so an interrupt arriving here would leak both the socket and the place it held
        // unless the probe registers its own exit. `resolvingOnce` is that registration.
        //
        // The latch removes the timing assumption: the interrupt is fired only after the server has read the
        // probe and gone silent, so it lands inside the probe rather than before or after it.
        val config = probeConfig(metricsScope = Present("kyo.sql.isalive.interrupt"))
        Latch.initWith(1) { probeSeen =>
            Scope.run {
                withProbeServer(config)(pgSilentAfter(1, onSilence = probeSeen.release)) { (client, _) =>
                    statement(client).andThen {
                        Fiber.initUnscoped(Abort.run[Timeout](statement(client)).unit).flatMap { probing =>
                            probeSeen.await.andThen {
                                probing.interrupt.flatMap { interrupted =>
                                    assert(interrupted, "the probing fiber must actually be interrupted")
                                    untilDiscarded(client, 1L).flatMap { count =>
                                        permits(client).map { case (available, capacity) =>
                                            assert(
                                                count == 1L,
                                                s"an interrupt during the probe must destroy the connection, counter read $count"
                                            )
                                            assert(
                                                available == capacity,
                                                s"an interrupt during the probe must not strand a permit, had $available of $capacity"
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

end SqlClientPoolIsAliveTest
