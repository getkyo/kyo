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
  * stream leaves the server mid-scan, and the cleanup drain has to stop it rather than read the rest of the result set off the wire. That
  * one is measured on the streaming session's own rows-read counter; see [[rowsReadOnSession]] for why nothing global can answer it.
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

    /** How many rows the server has read off an index for ONE session, paired with the id of the session it was read on.
      *
      * This is the observable the drain leaf rests on, and every clause of it holds on mysql:8.0.
      *
      * WHY A ROWS-READ COUNTER AND NOT A KILL COUNTER. The harm the escalation exists to prevent is the server running an abandoned batch
      * to completion, so rows read IS the harm, measured at the only place that knows it. `Com_kill` cannot serve: it is GLOBAL, so it
      * counts every kill the server executes for any session, and this leaf's own teardown issues one by a different route when the client
      * closes. With the escalation unwired entirely, so the drain can kill nothing, that counter still moves and the leaf still passes. A
      * global counter cannot attribute anything to the code under test.
      *
      * WHY `Handler_read_next` SPECIFICALLY, out of the plausible candidates. `EXPLAIN` on the leaf's own statement reports `type: index,
      * key: PRIMARY`, so the scan walks the clustered index and lands on `Handler_read_next`, one increment per row after the first.
      * Measured end to end: a full 100000-row scan moves it by exactly 100000, a `LIMIT 10` scan moves it by exactly 9, and
      * `Handler_read_rnd_next`, which is the counter a heap table scan would use, stays at 0 throughout. So the number is the row count and
      * not a proxy for it. Any change to the leaf's statement has to be re-measured against the plan, since a different statement can take
      * a different plan and the counter would then be reporting on a query nothing runs.
      *
      * `Innodb_rows_read` was the other candidate and is DISQUALIFIED, which is worth recording because it does not look disqualified. It
      * moves by the same 100000 for the same scan, and `SHOW SESSION STATUS LIKE 'Innodb_rows_read'` is accepted without complaint, but the
      * variable is global-only and MySQL serves the global value under either scope: a second, idle session reads 100000 from it while
      * reading 0 from `Handler_read_next`. Asking for session scope is not the same as getting it.
      *
      * WHY BOTH COLUMNS COME OUT OF ONE STATEMENT. A counter is only session-local evidence if the session it was read on is known, and the
      * pool can hand back a different connection than the one before it. Reading `connection_id()` alongside the value makes each reading
      * carry its own session, so a replaced connection is a loud mismatch instead of a silently negative delta.
      *
      * THE PROBE DOES NOT MOVE WHAT IT READS, which cannot be assumed of a statement that scans a table to answer. Four consecutive
      * executions of this exact SQL in one session, with nothing between them, leave the value at 0.
      *
      * AN ABSENT COUNTER IS ZERO ROWS, NOT A ZERO. `WHERE VARIABLE_NAME = ...` matching nothing returns an empty result set, which this
      * method turns into a hard failure. A probe reading a view that does not carry the counter it names, such as `Com_xxx` in
      * `performance_schema.global_status`, matches no row and would otherwise answer a zero it never observed.
      */
    private def rowsReadOnSession(client: MysqlClient)(using Frame): (Long, Long) < (Async & Abort[SqlException]) =
        client.simpleQuery(
            "SELECT connection_id(), CAST(VARIABLE_VALUE AS UNSIGNED) FROM performance_schema.session_status " +
                "WHERE VARIABLE_NAME = 'Handler_read_next'"
        ).flatMap { rows =>
            if rows.isEmpty then
                Abort.panic(new IllegalStateException(
                    "performance_schema.session_status carries no Handler_read_next row, so this server cannot answer how many rows " +
                        "the session read and the drain assertion below would be reading a number it never observed"
                ))
            else
                Abort.recover((e: SqlDecodeException) => Abort.fail(e: SqlException)) {
                    rows(0).decode[Long](0).flatMap(session => rows(0).decode[Long](1).map(read => (session, read)))
                }
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
                    // 100k stored rows of ~200 bytes, streamed back ten times as wide, so one full drain moves about
                    // 191MB while the table on disk stays 20MB. The row count and the byte count are separate
                    // dimensions here on purpose.
                    //
                    // The budget is denominated in ELAPSED TIME, so what the drain gets through before escalating is a
                    // fixed number of BYTES, and the fixture has to be several times that for the assertion below to
                    // have any margin. At 20MB the escalating drain reads about 70000 of the 100000 rows on this
                    // machine, leaving a threshold window of only (70000, 100000). Streaming the rows wide multiplies
                    // the bytes the budget has to chew through while leaving the row count, and therefore the insert,
                    // where it was.
                    //
                    // `REPEAT(payload, 10)` rather than a wider COLUMN for two reasons. `CHAR` caps at 255 so the
                    // column cannot hold this directly, and widening in the SELECT keeps the stored table, the INSERT
                    // and the container's disk unchanged. It does not disturb the measurement: the widened statement
                    // still plans as `type: index, key: PRIMARY` and a full scan of it still moves
                    // `Handler_read_next` by exactly 100000 with `Handler_read_rnd_next` at 0.
                    //
                    // The large result set costs nothing on the passing path. When the escalation works the drain
                    // stops after its 250ms regardless of how much is queued behind it; only a BROKEN mechanism ever
                    // transfers the whole 191MB, which is the run that is supposed to be slow.
                    val rows = 100000
                    for
                        _ <- client.execute("CREATE TABLE drain_t (id INT PRIMARY KEY, payload CHAR(200) NOT NULL)")
                        _ <- client.execute(s"SET SESSION cte_max_recursion_depth = ${rows + 1}")
                        _ <- client.execute(
                            "INSERT INTO drain_t (id, payload) WITH RECURSIVE seq(n) AS (" +
                                s"SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < $rows" +
                                ") SELECT n, REPEAT('x', 200) FROM seq"
                        )
                        // Which session the streaming path runs on, asked through that path rather than inferred from
                        // `maxConnections = 1`. Every number below is session-scoped, so a probe on another session
                        // would answer for a session that streamed nothing and read a quiet, wrong 0.
                        streamSession <- streamSessionId(client)
                        before        <- rowsReadOnSession(client)
                        // Take one row and let the Scope close. The cleanup drain runs here, and with 100k rows still
                        // queued it must escalate rather than read them all.
                        taken <- Scope.run(client.streamQuery("SELECT id, REPEAT(payload, 10) FROM drain_t ORDER BY id").take(1).run)
                        after <- rowsReadOnSession(client)
                    yield
                        val (beforeSession, beforeRead) = before
                        val (afterSession, afterRead)   = after
                        assert(taken.size == 1, s"the stream must yield the one row it asked for, got ${taken.size}")
                        // THIS IS WHAT STOPS THE MEASUREMENT BELOW FROM BEING TAKEN ON A SESSION THAT DID NOTHING, and
                        // it is the assertion the whole leaf rests on rather than a sanity check on the fixture.
                        //
                        // The fixture constant does not give what it appears to give. `maxConnections = 1` bounds
                        // CONCURRENCY, through the pool's per-address slot channel, and does NOT bound this leaf to one
                        // physical session: `SqlConnectionPool` floors the transport at `config.maxConnections.max(2)`
                        // because the underlying ring requires at least two, and its own comment at that site records
                        // that this raises retention rather than concurrency. So a second session for this address is
                        // reachable, and "the probe reads the session the stream ran on" is an empirical regularity
                        // rather than a guarantee.
                        //
                        // What makes that worth an assertion is the direction it fails in. A probe on the wrong session
                        // reports a session that read nothing, so the delta below is ZERO, which is comfortably under
                        // the threshold and PASSES. The leaf would then go green on its headline claim while measuring
                        // a session that never ran the stream, permanently and silently.
                        //
                        // Reading the counter through a second client to the same database fires this assertion, with
                        // the two sessions differing and the delta at 0 against a threshold of 50000, which is exactly
                        // the shape it exists to catch.
                        //
                        // WHAT THIS ASSERTION DOES NOT CATCH. A drain that merely GAVE UP at the budget without killing
                        // never reaches here. It leaves unread row packets on the wire, the next probe answers its
                        // `COM_QUERY` out of those leftovers and yields no row at all, and `rowsReadOnSession` fails
                        // hard on the empty result first. It cannot present as a different session either, because the
                        // pool's `isAlive` is the socket alone, so a dirty connection is reused rather than replaced.
                        //
                        // So the three defences are disjoint and each one is the only cover for its own mutation: this
                        // assertion for a wrong-session probe, the probe's empty-result guard for an abandoned wire, and
                        // the rows-read delta below for an escalation that never fires.
                        assert(
                            beforeSession == streamSession && afterSession == streamSession,
                            s"both readings must come from the session the stream ran on ($streamSession), but the " +
                                s"first read session $beforeSession and the second $afterSession; a mismatch means this " +
                                "connection did not survive the early-terminated stream, so the drain did not leave the " +
                                "session reusable and the delta below compares two different sessions"
                        )
                        // HALF THE RESULT SET, and the number comes from the two measured endpoints rather than from
                        // picking a round fraction.
                        //
                        // The two endpoints do not have the same character, which is what sets where the margin goes.
                        // The DEFECT value is exact and has no variance: an unescalated drain reads to the terminator,
                        // so the counter delta equals the row count by construction, and it lands at 100000 of 100000.
                        // The WORKING value is around 12300 and is the only one that moves, because it is whatever the
                        // wire carries in 250ms and so scales with the machine.
                        //
                        // So the margin is deliberately asymmetric. Below the threshold there is nothing to guard
                        // against, since the defect cannot land at 99000; it lands at exactly `rows`, and any
                        // threshold under `rows` catches it. All the headroom therefore belongs above the working
                        // value, and half the result set is the largest fraction that still asserts a MINORITY of it,
                        // which is what "instead of draining all of it" claims. That leaves roughly 4x over the
                        // working value, so a machine four times faster than this one still passes.
                        assert(
                            afterRead - beforeRead < rows / 2,
                            s"the cleanup drain must stop the server rather than let it run the abandoned scan to the end, " +
                                s"but session $streamSession read ${afterRead - beforeRead} of the table's $rows rows " +
                                s"(Handler_read_next $beforeRead then $afterRead). Reading all of them is the defect: with " +
                                "the escalation absent the drain outlasts the server instead of stopping it"
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
