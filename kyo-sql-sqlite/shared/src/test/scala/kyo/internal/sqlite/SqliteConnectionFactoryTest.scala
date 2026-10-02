package kyo.internal.sqlite

import kyo.*
import kyo.internal.SqliteTempDatabase

class SqliteConnectionFactoryTest extends Test:

    /** Statements left waiting on a lock: as many as Node's libuv worker pool has threads by default. */
    private val Waiters = 4

    /** Until `n` statements have reached the lock: asleep in SQLite's busy handler, which counts them as native calls beyond `baseline`, or
      * waiting on their fibers.
      */
    private def untilWaiting(n: Int, baseline: Int)(using Frame): Unit < Async =
        Loop.foreach {
            Sync.Unsafe.defer(math.max(SqliteNativeCalls.inFlight - baseline, SqliteLockWait.waiting)).map { reached =>
                if reached >= n then Loop.done(()) else Async.sleep(1.millis).andThen(Loop.continue)
            }
        }

    // deviation: a lock wait runs on the real clock, inside SQLite or between its retries, and the open's budget is a real timeout. The
    // 2 s budget is what an open queued behind the waits runs out of; an open that is not queued completes in milliseconds.
    "an open is not queued behind statements waiting on another database's lock" in {
        Scope.run {
            for
                locked  <- SqliteTempDatabase.create
                other   <- SqliteTempDatabase.create
                holder  <- SqlClient.init(s"sqlite://$locked", SqlConfig(maxConnections = 1))
                writers <- SqlClient.init(s"sqlite://$locked", SqlConfig(maxConnections = Waiters, acquireTimeout = 20.seconds))
                _       <- DB.run(holder)(holder.executeRaw("CREATE TABLE t (id INT)"))
                held    <- Latch.init(1)
                release <- Latch.init(1)
                holding <- Fiber.initUnscoped(DB.run(holder) {
                    holder.transaction(Absent, readOnly = false) {
                        holder.executeRaw("INSERT INTO t VALUES (0)").andThen(held.release).andThen(release.await)
                    }
                })
                _        <- held.await
                baseline <- Sync.Unsafe.defer(SqliteNativeCalls.inFlight)
                waiting  <- Fiber.initUnscoped(
                    Async.foreach(1 to Waiters, Waiters)(i => DB.run(writers)(writers.executeRaw(s"INSERT INTO t VALUES ($i)")))
                )
                _      <- untilWaiting(Waiters, baseline)
                opened <- Abort.run[SqlException] {
                    SqlClient.init(s"sqlite://$other?connectTimeout=2s", SqlConfig(maxConnections = 1)).map { client =>
                        DB.run(client)(client.query("SELECT 1")).unit
                    }
                }
                _      <- release.release
                _      <- holding.get
                writes <- Abort.run[SqlException](waiting.get)
            yield
                assert(opened.isSuccess, s"the open waited for the lock held on another database: $opened")
                assert(writes.isSuccess, s"the writers' waits outlasted the lock they were waiting for: $writes")
            end for
        }
    }

    /** Opens `path` with a 1 s budget while another connection holds it under an EXCLUSIVE lock, leaving that connection open.
      *
      * The file goes back to a rollback journal first, where an EXCLUSIVE lock keeps every other connection from reading it, so the open's
      * journal-mode switch waits for the lock until the budget runs out.
      */
    private def openUnderExclusiveLock(path: String)(using Frame): Result[SqlException, Unit] < (Async & Scope & Abort[SqlException]) =
        for
            holder  <- SqlClient.init(s"sqlite://$path", SqlConfig(maxConnections = 1))
            _       <- DB.run(holder)(holder.executeRaw("PRAGMA journal_mode = DELETE"))
            held    <- Latch.init(1)
            release <- Latch.init(1)
            holding <- Fiber.initUnscoped(DB.run(holder) {
                holder.executeRaw("BEGIN EXCLUSIVE")
                    .andThen(held.release)
                    .andThen(release.await)
                    .andThen(holder.executeRaw("ROLLBACK"))
            })
            _      <- held.await
            opened <- Abort.run[SqlException](openAndQuery(path, 1.second))
            _      <- release.release
            _      <- holding.get
        yield opened

    private def openAndQuery(path: String, budget: Duration)(using Frame): Unit < (Async & Abort[SqlException]) =
        Scope.run {
            SqlClient.init(s"sqlite://$path?connectTimeout=${budget.toMillis}ms", SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client)(client.query("SELECT 1")).unit
            }
        }

    /** How long a leaf waits for an abandoned handle to be closed before it reports the count it sees. Far longer than a close takes, and
      * only a bound: a leaked handle is never closed, and the leaf should fail on its assertion rather than its own limit.
      */
    private val SettleBound = 30.seconds

    /** The handles open to `path` once the count reaches `n`, or whatever it is after [[SettleBound]]. */
    private def handlesSettledAt(path: String, n: Int)(using Frame): Int < Async =
        Abort.run[Timeout] {
            Async.timeout(SettleBound) {
                Loop.foreach {
                    Sync.Unsafe.defer(SqliteNativeCalls.handlesOpen(path)).map { open =>
                        if open == n then Loop.done(()) else Async.sleep(1.millis).andThen(Loop.continue)
                    }
                }
            }
        }.andThen(Sync.Unsafe.defer(SqliteNativeCalls.handlesOpen(path)))

    // deviation: the open waits on a lock between real-clock retries, and its budget is a real timeout.
    "an open that runs out of budget reports its phases and the process's SQLite calls" in {
        Scope.run {
            SqliteTempDatabase.create.map(openUnderExclusiveLock).map {
                case Result.Failure(e: SqlConnectionEstablishTimeoutException) =>
                    val expected =
                        """phases open \d+ ms, configure \d+ ms, journal mode \d+ ms \(unfinished\); """ +
                            """\d+ SQLite calls in native code or queued for a worker, [1-9]\d* statements waiting on a lock"""
                    assert(e.diagnostics.exists(_.matches(expected)), s"diagnostics: ${e.diagnostics}")
                case other => fail(s"Expected the open to run out of budget waiting for the lock, got $other")
            }
        }
    }

    // deviation: the open waits on a lock between real-clock retries, and its budget is a real timeout.
    "an open that runs out of budget part way closes the handle it had opened" in {
        Scope.run {
            for
                path   <- SqliteTempDatabase.create
                opened <- openUnderExclusiveLock(path)
                // The connection holding the lock is still open, and must be the only one.
                open <- handlesSettledAt(path, 1)
            yield
                assert(opened.failure.exists(_.isInstanceOf[SqlConnectionEstablishTimeoutException]), s"opened: $opened")
                assert(open == 1, s"$open handles open to the file, one of them the timed-out open's")
            end for
        }
    }

    // deviation: the busy statements run on the real clock, and the open's budget is a real timeout. The statements are sized to outlast
    // the 200 ms budget several times over (about 0.7 s each on JS), so on JS the open is still queued behind them when it is abandoned. Where blocking calls do not share a
    // pool of Waiters threads the open is not queued and simply succeeds; either way no handle may outlive it.
    "an open abandoned while its call waits for a worker closes the handle once the call completes" in {
        Scope.run {
            for
                other <- SqliteTempDatabase.create
                busy  <- Kyo.fill(Waiters)(SqliteTempDatabase.create)
                // No query timeout: how long the statements take depends on the build, and only how long they outlast the open matters.
                clients <- Kyo.foreach(busy)(path =>
                    SqlClient.init(s"sqlite://$path", SqlConfig(maxConnections = 1, queryTimeout = Duration.Infinity))
                )
                _        <- Kyo.foreachDiscard(clients)(client => DB.run(client)(client.query("SELECT 1")).unit)
                baseline <- Sync.Unsafe.defer(SqliteNativeCalls.inFlight)
                running  <- Fiber.initUnscoped(Async.foreach(clients, Waiters) { client =>
                    DB.run(client)(client.query(
                        "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 5000000) SELECT count(*) FROM c"
                    ))
                })
                _      <- untilWaiting(Waiters, baseline)
                opened <- Abort.run[SqlException](openAndQuery(other, 200.millis))
                _      <- running.get
                open   <- handlesSettledAt(other, 0)
            yield assert(open == 0, s"$open handles open to a file whose open was abandoned ($opened)")
            end for
        }
    }

end SqliteConnectionFactoryTest
