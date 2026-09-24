package kyo.mysql

import kyo.*
import kyo.internal.SqlSharedContainers

/** Integration tests for stopping a long-running MySQL query, against a real server.
  *
  * There is no cancel handle: the fiber running the statement is the handle. Two scenarios pin that a statement the server will hold open
  * for half a minute does not hold its caller for half a minute, through either replacement idiom. A third pins the server behavior the
  * wire-level cancel depends on, that `KILL QUERY` naming a thread which no longer exists is accepted rather than an error, which is what
  * lets a cancel race the query's own completion without failing.
  *
  * A fourth covers the other direction, where the caller walks away from a stream rather than being released from a query. Abandoning a
  * stream leaves the server mid-scan, and the cleanup drain has to stop it rather than read the rest of the result set off the wire. The
  * result there is too large for any drain to finish, so only the kill lets the leaf complete.
  *
  * Which reclaim steps the pool runs after an interrupt, in what order, and under what budget is pinned at the pool boundary by
  * [[kyo.internal.SqlConnectionCancelTest]]; what these add is the live server underneath.
  *
  * Each test runs against a fresh schema in the per-fork-JVM shared MySQL container (via [[SqlSharedContainers.withFreshSchema]]).
  */
class MysqlCancelIntegrationTest extends SqlContainerTest:

    override def timeout: Duration = 3.minutes

    /** Sleeps far longer than any assertion below waits, so the query is unambiguously still in flight. */
    private val longQuery = "SELECT SLEEP(30)"

    private def initClient[A, S](ctx: SqlSharedContainers.SchemaCtx, maxConns: Int = 2)(
        f: MysqlClient => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Scope & Abort[SqlException]) =
        MysqlClient.initWith(
            s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}",
            SqlConfig.default.copy(
                maxConnections = maxConns,
                minConnections = maxConns
            )
        )(f)

    /** The id of the session a plain query runs on.
      *
      * An empty answer is a hard failure rather than a missing id: a connection left mid-result answers this `COM_QUERY` out of the
      * previous statement's unread packets and yields no row, so an empty result is how a dirty wire shows up.
      */
    private def sessionId(client: MysqlClient)(using Frame): Long < (Async & Abort[SqlException]) =
        client.simpleQuery("SELECT connection_id()").flatMap { rows =>
            if rows.isEmpty then
                Abort.panic(new IllegalStateException(
                    "SELECT connection_id() returned no row, so the connection was reused with a previous result still on the wire"
                ))
            else
                Abort.recover((e: SqlDecodeException) => Abort.fail(e: SqlException))(rows(0).decode[Long](0))
        }

    /** The id of the session a stream runs on, read through `streamQuery` so it is the streaming path that answers.
      *
      * The workload under test is a stream and the probe is a `simpleQuery`, so "they share a session" is a claim about two different entry
      * points into the pool. Reading the id through the same call the leaf measures is what makes the comparison meaningful; asking `query`
      * instead would identify the session of a path the leaf never takes.
      *
      * The inner `Scope.run` is load-bearing rather than tidiness. A stream holds its leased connection for the lifetime of its `Scope`, so
      * letting that `Scope` escape to the leaf's outer one would keep this connection checked out; against the `maxConnections = 1` pool
      * the leaf pins, the next statement would then wait for a connection that is never coming back.
      */
    private def streamSessionId(client: MysqlClient)(using Frame): Long < (Async & Abort[SqlException]) =
        Scope.run(client.streamQuery("SELECT connection_id()").run).flatMap { rows =>
            if rows.isEmpty then
                Abort.panic(new IllegalStateException("SELECT connection_id() over the streaming path returned no row"))
            else
                Abort.recover((e: SqlDecodeException) => Abort.fail(e: SqlException))(rows(0).decode[Long](0))
        }

    "an early-terminated stream over a large result set kills the statement instead of draining all of it" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx, maxConns = 1) { client =>
                    // A billion-row result from a 1000-row table: the cross join without `ORDER BY` streams its rows as it
                    // produces them, so nothing is materialized and the table stays small. No drain reads a billion rows
                    // within the leaf's timeout, so the scope below closes only if the drain kills the statement, and the
                    // leaf needs no count of how far the server got.
                    val rows = 1000
                    for
                        _ <- client.execute("CREATE TABLE drain_t (id INT PRIMARY KEY)")
                        _ <- client.execute(
                            "INSERT INTO drain_t (id) WITH RECURSIVE seq(n) AS (" +
                                s"SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < $rows" +
                                ") SELECT n FROM seq"
                        )
                        streamSession <- streamSessionId(client)
                        taken         <- Scope.run(
                            client.streamQuery("SELECT a.id FROM drain_t a CROSS JOIN drain_t b CROSS JOIN drain_t c").take(1).run
                        )
                        afterSession <- sessionId(client)
                    yield
                        assert(taken.size == 1, s"the stream must yield the one row it asked for, got ${taken.size}")
                        // `maxConnections = 1` bounds concurrency, not the number of sessions (the pool floors its
                        // transport at two), so the same session answering before and after is checked rather than
                        // assumed. A drain that gave up without killing fails `sessionId` first, on the unread rows.
                        assert(
                            afterSession == streamSession,
                            s"the query after the stream must run on the session the stream ran on ($streamSession), but it ran on " +
                                s"$afterSession, so the early-terminated stream did not leave its connection reusable"
                        )
                    end for
                }
            }
        }
    }

    "Async.timeout releases the caller from a query the server is still running" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx, maxConns = 2) { client =>
                    Clock.stopwatch.flatMap { stopwatch =>
                        Abort.run[Timeout](
                            Async.timeout(1.second)(Abort.run[SqlException](client.query(longQuery)))
                        ).flatMap {
                            case Result.Failure(_: Timeout) =>
                                // deviation: this asserts measured wall-clock elapsed. There is no barrier for "released by the 1s timeout, not by
                                // the 30s query": the pool cancels the query right after the release, so an information_schema.processlist probe
                                // races that cancel. The bound is catastrophic, not tight: a correct release lands at ~1s, a caller that waited out
                                // the query at ~30s, so 20s separates them with no runner-flippable margin.
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

    "interrupting the query's fiber releases the caller" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx, maxConns = 2) { client =>
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

    "KILL QUERY naming a thread that does not exist is accepted rather than an error" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx, maxConns = 1) { client =>
                    // A thread id far above anything a fresh container has handed out.
                    Abort.run[SqlException](client.executeRaw("KILL QUERY 9999999")).map {
                        case Result.Success(_) =>
                            succeed // the server answers OK for an absent thread
                        case Result.Failure(e: SqlServerException) =>
                            // ER_NO_SUCH_THREAD (1094) is the other permitted answer; either way it is not a transport failure.
                            assert(
                                e.extra.get("code").contains("1094"),
                                s"Expected OK or ER_NO_SUCH_THREAD (1094), got code=${e.extra.get("code")} state=${e.sqlState} msg=${e.message}"
                            )
                        case Result.Failure(e) =>
                            fail(s"Expected OK or a server error, got: $e")
                        case Result.Panic(t) =>
                            fail(s"Unexpected panic: ${t.getMessage}")
                    }
                }
            }
        }
    }

end MysqlCancelIntegrationTest
