package kyo.postgres

import kyo.*
import kyo.OwnContainer
import kyo.internal.SqlSharedContainers

/** Integration tests for stopping a long-running PostgreSQL query, against a real server.
  *
  * There is no cancel handle: the fiber running the statement is the handle. What these scenarios pin is that a statement the server will
  * hold open for half a minute does not hold its caller for half a minute, through either replacement idiom. The in-process versions of
  * both idioms are in `kyo.SqlClientInterruptTest`; these run them against a live backend, so the suspension being interrupted is a real
  * socket read.
  *
  * Whether the interrupt also reaches the server, so the backend stops computing rather than finishing into a connection nobody reads, is
  * the wire-cancel half. [[kyo.internal.SqlConnectionCancelTest]] pins it at the pool boundary: which reclaim steps run, in what order, and
  * under what budget. The last two leaves here observe the same property from the outside, against a live server.
  */
class CancelIntegrationTest extends SqlContainerTest:

    // Scope the podman/docker HttpClient per leaf so its idle-connection pool does not leak
    // unix sockets across tests that call ContainerPredef.*.initWith directly.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        super.aroundLeaf(HttpClient.init().flatMap(c => HttpClient.let(c)(body)))

    override def timeout: Duration = 8.minutes

    /** Sleeps far longer than any assertion below waits, so the query is unambiguously still in flight. */
    private val longQuery = "SELECT pg_sleep(30)"

    "Async.timeout releases the caller from a query the server is still running".tagged("kyo.OwnContainer") in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
                SqlClient.initWith(url) { client =>
                    Clock.stopwatch.flatMap { stopwatch =>
                        Abort.run[Timeout](
                            Async.timeout(1.second)(Abort.run[SqlException](client.query(longQuery)))
                        ).flatMap {
                            case Result.Failure(_: Timeout) =>
                                stopwatch.elapsed.map { waited =>
                                    assert(
                                        waited < 20.seconds,
                                        s"the caller must be released on the timeout, not on the query, waited $waited"
                                    )
                                }
                            case other =>
                                fail(s"Expected the query to be bounded by Async.timeout, got $other")
                        }
                    }
                }
            }
        }
    }

    "interrupting the query's fiber releases the caller".tagged("kyo.OwnContainer") in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
                SqlClient.initWith(url) { client =>
                    Latch.initWith(1) { started =>
                        Fiber.initUnscoped(
                            started.release.andThen(Abort.run[SqlException](client.query(longQuery)))
                        ).flatMap { queryFiber =>
                            started.await.andThen {
                                queryFiber.interrupt.map { interrupted =>
                                    assert(interrupted, "interrupting the fiber running a query must stop it")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- interrupted streams: the reclaim, observed from the outside ---

    /** Permits available right now, and the capacity they must return to. Absence fails the leaf rather than reading as a number, for the
      * reason [[kyo.SqlClientPoolSlotLeakTest]] records: defaulting both accessors made them equal whenever no slot channel existed.
      */
    private def permits(client: SqlClient)(using Frame, kyo.test.AssertScope): (Int, Int) < Sync =
        Sync.Unsafe.defer {
            // Unsafe: both accessors read a Channel size, as the pool's own observability accessors do.
            (client.runtime.pool.slotPermits(client.url.address), client.runtime.pool.slotCapacity(client.url.address)) match
                case (Present(available), Present(capacity)) => (available, capacity)
                case _ =>
                    fail(s"no slot channel exists for ${client.url.address}, so no statement ever reached the pool")
        }

    /** Polls `read` until it reaches `target`, bounded at 3 seconds, so a reclaim that cannot begin fails the leaf as a fast typed
      * assertion instead of a stall. The bound is what turns a drain sequenced before the cancel (which parks the reclaim behind server
      * work nobody wants) into a red leaf rather than an INCONCLUSIVE timeout.
      */
    private def awaitAtLeast(read: Long < Async, target: Long, what: String)(using Frame, kyo.test.AssertScope): Unit < Async =
        Loop(0) { attempt =>
            read.flatMap { v =>
                if v >= target then Loop.done(())
                else if attempt >= 300 then fail(s"$what stuck at $v after $attempt polls, expected at least $target")
                else Async.sleep(10.millis).andThen(Loop.continue(attempt + 1))
            }
        }

    /** Waits until no reclaim chain is running, so the exit counters can be read without perturbing them. Mirrors
      * `untilCancelsSettled` in [[kyo.internal.SqlConnectionCancelTest]], and like it must not be replaced with `closeAll`, which decides
      * the outcome it would be used to observe.
      */
    private def untilCancelsSettled(client: SqlClient)(using Frame): Unit < Async =
        Loop(0) { attempt =>
            // Unsafe: reads the pool's atomic reclaim counter without suspending.
            Sync.Unsafe.defer(client.runtime.pool.cancelsInFlightCount).flatMap { inFlight =>
                if inFlight == 0 || attempt >= 300 then Loop.done(())
                else Async.sleep(10.millis).andThen(Loop.continue(attempt + 1))
            }
        }

    "interrupting a stream reclaims the connection inside the cancel budget".tagged("kyo.OwnContainer") in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
                val config = SqlConfig(
                    maxConnections = 1,
                    acquireTimeout = 3.seconds,
                    cancelTimeout = 1.second,
                    metricsScope = Present("kyosql.t56a")
                )
                SqlClient.initWith(url, config) { client =>
                    Latch.initWith(1) { started =>
                        val consume =
                            Scope.run {
                                client.streamQuery("SELECT i FROM generate_series(1, 500) i").foreach { _ =>
                                    // Park on the first row while the portal is open, so the interrupt lands
                                    // mid-stream: in-flight flag raised, unread rows still server-side.
                                    started.release.andThen(Async.never)
                                }
                            }
                        Fiber.initUnscoped(Abort.run[SqlException](consume)).flatMap { fiber =>
                            started.await.andThen {
                                fiber.interrupt.flatMap { interrupted =>
                                    assert(interrupted, "the consumer fiber must be interruptible mid-stream")
                                    // decideExit records cancels_fired before spawning the reclaim, so this pair of
                                    // bounded waits is deterministic: proof the reclaim started, then proof it
                                    // finished deciding the connection's fate.
                                    awaitAtLeast(client.runtime.pool.metrics.cancelsFired.get, 1L, "cancels_fired").andThen {
                                        untilCancelsSettled(client).andThen {
                                            client.runtime.pool.metrics.cancelsTimedOut.get.flatMap { timedOut =>
                                                client.runtime.pool.metrics.connectionsDiscarded.get.flatMap { discarded =>
                                                    permits(client).flatMap { case (available, capacity) =>
                                                        assert(
                                                            timedOut == 0L,
                                                            "the reclaim's drain must find the finalizer's barrier, not burn the budget on an idle wire"
                                                        )
                                                        assert(discarded == 0L, "an interrupted stream must not cost a healthy connection")
                                                        assert(
                                                            available == capacity,
                                                            s"the stream's permit must be back: $available of $capacity"
                                                        )
                                                        // The reclaimed connection serves the next statement.
                                                        client.query("SELECT 1").map(rows => assert(rows.size == 1))
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
    }

    "the wire cancel reaches a batch the server is still computing".tagged("kyo.OwnContainer") in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
                val config = SqlConfig(
                    maxConnections = 1,
                    acquireTimeout = 3.seconds,
                    cancelTimeout = 2.seconds,
                    metricsScope = Present("kyosql.t56b")
                )
                SqlClient.initWith(url, config) { client =>
                    Latch.initWith(1) { started =>
                        // Batch 1 (rows 1 to 100) is free; every later row costs 200ms server-side. The consumer
                        // keeps demanding, so it ends up blocked reading batch 2 while the server computes it,
                        // which is the state where a drain sequenced before the cancel waits on ~20 seconds of
                        // work nobody wants and the reclaim cannot begin until it is done.
                        val sql = "SELECT i, CASE WHEN i <= 100 THEN '' ELSE pg_sleep(0.2)::text END FROM generate_series(1, 300) i"
                        val consume =
                            Scope.run {
                                client.streamQuery(Sql.Fragment.lit[Any](sql), 100).foreach(_ => started.release)
                            }
                        Fiber.initUnscoped(Abort.run[SqlException](consume)).flatMap { fiber =>
                            started.await.andThen {
                                // By one second after the first row the consumer has demanded batch 2 and the
                                // server is inside it. The verdict does not depend on this timing: an interrupt
                                // that lands earlier exercises the idle-wire variant the previous leaf pins.
                                Async.sleep(1.second).andThen {
                                    fiber.interrupt.flatMap { interrupted =>
                                        assert(interrupted, "the consumer fiber must be interruptible mid-batch")
                                        awaitAtLeast(client.runtime.pool.metrics.cancelsFired.get, 1L, "cancels_fired").andThen {
                                            untilCancelsSettled(client).andThen {
                                                client.runtime.pool.metrics.cancelsTimedOut.get.flatMap { timedOut =>
                                                    client.runtime.pool.metrics.connectionsDiscarded.get.map { discarded =>
                                                        assert(
                                                            timedOut == 0L,
                                                            "the cancel must truncate the in-flight batch inside the budget"
                                                        )
                                                        assert(
                                                            discarded == 0L,
                                                            "a cancelled stream leaves a reusable connection on this engine"
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
    }

end CancelIntegrationTest
