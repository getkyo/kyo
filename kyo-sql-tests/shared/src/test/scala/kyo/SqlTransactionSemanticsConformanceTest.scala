package kyo

import kyo.internal.SqlTestBackend.ColumnType

/** What a transaction MEANS on every registered backend: the same sequence of statements commits the same rows, whichever engine ran it.
  *
  * The sibling suite proves the isolation vocabulary is accepted. This one is about what survives when something inside the transaction goes
  * wrong, which is where the engines disagree most expensively: the same program can commit different data with no error reaching the caller.
  */
class SqlTransactionSemanticsConformanceTest extends SqlBackendTest:

    case class Ledger(id: Int, note: String) derives SqlSchema, CanEqual

    private def createLedger(backend: kyo.internal.SqlTestBackend, client: SqlClient)(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        client.executeRaw(
            s"CREATE TABLE ledger (id ${backend.columnType(ColumnType.Int)} PRIMARY KEY, note ${backend.textColumnType} NOT NULL)"
        ).unit

    /** Unpinned, one engine commits both surviving rows and the other commits nothing, neither telling the caller. Nothing surviving is the
      * forced direction: no driver can make a poisoned transaction continue. The recovery is the point, since without `Abort.run` the failure
      * propagates and rolls back on any engine.
      */
    "a failed statement inside a transaction leaves nothing committed, and says so" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- createLedger(backend, client)
                outcome <- Abort.run[SqlException](
                    client.transaction {
                        for
                            _ <- Sql.insert[Ledger].values(Ledger(1, "before")).run
                            // Same primary key, so the server refuses it. Recovered, so the transaction body completes
                            // normally and reaches its commit.
                            _ <- Abort.run[SqlException](Sql.insert[Ledger].values(Ledger(1, "duplicate")).run)
                            _ <- Sql.insert[Ledger].values(Ledger(2, "after")).run
                        yield ()
                    }
                )
                rows <- Sql.from[Ledger]("l").orderBy(_.l.id.asc).run
            yield
                assert(
                    rows.isEmpty,
                    s"${backend.label}: a transaction carrying a failed statement must commit nothing, got ${rows.map(_.id)}"
                )
                // Asserted beside the rows because silence is the failure this replaces. One engine already committed
                // nothing here, by turning the commit into a rollback and telling the caller nothing, so a leaf that
                // only checked the rows would have called that engine conformant while it silently discarded work.
                outcome match
                    case Result.Failure(_: SqlRequestTransactionFailedStatementException) => succeed
                    case other =>
                        fail(s"${backend.label}: expected a typed failure naming the statement, got $other")
                end match
        }
    }

    /** The control beside the leaf above: the rollback must be caused by the failure, not by transactions rolling back generally. */
    "a transaction with no failure commits every row" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- createLedger(backend, client)
                _ <- client.transaction {
                    Sql.insert[Ledger].values(Ledger(1, "first")).run
                        .andThen(Sql.insert[Ledger].values(Ledger(2, "second")).run)
                }
                rows <- Sql.from[Ledger]("l").orderBy(_.l.id.asc).run
            yield assert(
                rows.map(_.id) == Chunk(1, 2),
                s"${backend.label}: expected both rows committed, got ${rows.map(_.id)}"
            )
        }
    }

    /** The documented way to handle a failure and keep going. The savepoint PREDATES the failure here, which is the ordering that makes
      * rolling back to it meaningful.
      */
    "a failure scoped in a nested transaction is recovered and the rest commits" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- createLedger(backend, client)
                _ <- client.transaction {
                    for
                        _ <- Sql.insert[Ledger].values(Ledger(1, "before")).run
                        // Scoped: the nested transaction takes a savepoint, and its failure rolls back to it.
                        _ <- Abort.run[SqlException](
                            client.transaction(Sql.insert[Ledger].values(Ledger(1, "duplicate")).run)
                        )
                        _ <- Sql.insert[Ledger].values(Ledger(2, "after")).run
                    yield ()
                }
                rows <- Sql.from[Ledger]("l").orderBy(_.l.id.asc).run
            yield assert(
                rows.map(_.id) == Chunk(1, 2),
                s"${backend.label}: a scoped failure must leave both surrounding rows committed, got ${rows.map(_.id)}"
            )
        }
    }

    /** They race onto the transaction's pinned session at once, which is what the session mutex exists for. The count is read back OUTSIDE the
      * transaction, so it proves the commit carried them.
      */
    "statements forked inside a transaction body with no await all commit" - {
        forEachBackend() { (backend, client, _) =>
            val count = 24
            client.executeRaw(s"CREATE TABLE tx_fork (id ${backend.columnType(ColumnType.Int)} PRIMARY KEY)").andThen {
                client.transaction {
                    Kyo.foreach(1 to count)(i => Fiber.initUnscoped(client.execute(sql"INSERT INTO tx_fork VALUES ($i)")))
                        .flatMap(fibers => Kyo.foreach(fibers)(_.get).unit)
                }.andThen {
                    client.query(sql"SELECT COUNT(*) FROM tx_fork").flatMap { rows =>
                        rows(0).decode[Long](0).map { total =>
                            assert(
                                total == count.toLong,
                                s"${backend.label}: all $count forked inserts must commit, found $total rows"
                            )
                        }
                    }
                }
            }
        }
    }

    /** `simpleQuery` routes to the transaction's connection through a different helper than `query`, so it is a separate lane for a handled
      * failure to reach the commit unrecorded.
      */
    "a handled simpleQuery failure inside a transaction still stops the commit" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- createLedger(backend, client)
                outcome <- Abort.run[SqlException](
                    client.transaction {
                        for
                            _ <- Sql.insert[Ledger].values(Ledger(1, "before")).run
                            _ <- Abort.run[SqlException](client.simpleQuery("SELECT * FROM no_such_table_here"))
                        yield ()
                    }
                )
                rows <- Sql.from[Ledger]("l").run
            yield
                assert(
                    rows.isEmpty,
                    s"${backend.label}: a transaction carrying a handled simpleQuery failure must commit nothing, got ${rows.map(_.id)}"
                )
                assert(
                    outcome.isFailure,
                    s"${backend.label}: the caller must be told the transaction was rolled back, got $outcome"
                )
        }
    }

    /** A savepoint taken after a failure cannot undo it, so rolling back to it must not make the outer transaction committable. The engines do
      * not agree on their own: one refuses the SAVEPOINT outright, the other takes it.
      */
    "a nested transaction opened after a handled failure does not rescue the outer one" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- createLedger(backend, client)
                outcome <- Abort.run[SqlException](
                    client.transaction {
                        for
                            _ <- Sql.insert[Ledger].values(Ledger(1, "before")).run
                            _ <- Abort.run[SqlException](Sql.insert[Ledger].values(Ledger(1, "duplicate")).run)
                            // Opened after the failure, so its savepoint reverses none of it.
                            _ <- Abort.run[SqlException](client.transaction(Sql.insert[Ledger].values(Ledger(2, "retry")).run))
                        yield ()
                    }
                )
                rows <- Sql.from[Ledger]("l").run
            yield
                assert(
                    rows.isEmpty,
                    s"${backend.label}: a nested transaction must not rescue a failure it did not undo, got ${rows.map(_.id)}"
                )
                assert(
                    outcome.isFailure,
                    s"${backend.label}: the caller must be told the transaction was rolled back, got $outcome"
                )
        }
    }

    /** A fiber the body forked and never awaited can be mid-statement when the body returns, so COMMIT queues behind it and the flag has to be
      * read under the same mutex. The leaf records whether the failure landed while the transaction was still OPEN: an unawaited fiber may
      * legitimately run after it ended, and a commit that got there first is correct.
      */
    "a forked statement that fails after the body returns still stops the commit" - {
        forEachBackend() { (backend, client, _) =>
            Kyo.foreachDiscard(1 to 8) { round =>
                for
                    _         <- client.executeRaw("DROP TABLE IF EXISTS ledger")
                    _         <- createLedger(backend, client)
                    txEnded   <- AtomicBoolean.init(false)
                    failedIn  <- AtomicBoolean.init(false)
                    forkedRef <- AtomicRef.init(Maybe.empty[Fiber[Unit, DB]])
                    outcome <- Abort.run[SqlException](
                        client.transaction {
                            for
                                _       <- Sql.insert[Ledger].values(Ledger(1, "seed")).run
                                started <- Latch.init(1)
                                fiber <- Fiber.initUnscoped(
                                    started.release.andThen(
                                        // Same key as the seed, so the server refuses it. Recovered inside the fiber,
                                        // so nothing propagates out of the body: the flag is the only channel left.
                                        Abort.run[SqlException](Sql.insert[Ledger].values(Ledger(1, "dup")).run.unit).flatMap {
                                            case Result.Success(_) => Sync.defer(())
                                            // Records whether the transaction was still open when this failed. A fiber
                                            // that was never awaited can legitimately run AFTER the transaction ended,
                                            // and a commit that happened first is then correct rather than a defect;
                                            // without this the leaf cannot tell the two orderings apart and asserts a
                                            // property the code does not owe.
                                            case _ => txEnded.get.map(ended => if !ended then failedIn.set(true) else ())
                                        }
                                    )
                                )
                                _ <- forkedRef.set(Present(fiber))
                                _ <- started.await
                            yield ()
                        }
                    )
                    _      <- txEnded.set(true)
                    forked <- forkedRef.get
                    _ <- forked match
                        case Present(f) => Abort.run[Throwable](f.get).unit
                        case Absent     => Sync.defer(())
                    insideTx <- failedIn.get
                    rows     <- Sql.from[Ledger]("l").run
                yield
                    if !insideTx then
                        // Either the forked statement never ran, or it ran after the transaction ended. Both leave the
                        // seed committed on its own, which is what the body actually did.
                        assert(
                            rows.map(_.id) == Chunk(1),
                            s"${backend.label} round $round: with no failure inside the transaction the seed must commit, " +
                                s"got ${rows.map(_.id)}"
                        )
                    else
                        assert(
                            outcome.isFailure && rows.isEmpty,
                            s"${backend.label} round $round: a statement failed while the transaction was open, so it must roll back " +
                                s"and say so; got outcome $outcome holding ${rows.map(_.id)}"
                        )
            }.andThen(succeed)
        }
    }

end SqlTransactionSemanticsConformanceTest
