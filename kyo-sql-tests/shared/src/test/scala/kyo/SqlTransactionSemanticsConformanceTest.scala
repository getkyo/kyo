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
                _       <- createLedger(backend, client)
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
                    case other                                                            =>
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
                _       <- createLedger(backend, client)
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
                _       <- createLedger(backend, client)
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
      * read under the same mutex. Which side of the COMMIT the statement landed on is what its own outcome says: refused by the server, it
      * ran inside and the commit must have become a rollback; refused as late, the seed committed alone. A statement that succeeded is
      * neither, and is the defect.
      */
    "a forked statement that fails after the body returns still stops the commit" - {
        forEachBackend() { (backend, client, _) =>
            Kyo.foreachDiscard(1 to 8) { round =>
                for
                    _       <- client.executeRaw("DROP TABLE IF EXISTS ledger")
                    _       <- createLedger(backend, client)
                    lateRef <- AtomicRef.init(Maybe.empty[Result[SqlException, Unit]])
                    done    <- Latch.init(1)
                    outcome <- Abort.run[SqlException](
                        client.transaction {
                            for
                                _       <- Sql.insert[Ledger].values(Ledger(1, "seed")).run
                                started <- Latch.init(1)
                                _       <- Fiber.initUnscoped(
                                    Sync.ensure(done.release) {
                                        // Same key as the seed, so the server refuses it. Recovered inside the fiber,
                                        // so nothing propagates out of the body: the recorded outcome is the only
                                        // channel left.
                                        started.release
                                            .andThen(Abort.run[SqlException](Sql.insert[Ledger].values(Ledger(1, "dup")).run.unit))
                                            .flatMap(late => lateRef.set(Present(late)))
                                    }
                                )
                                _ <- started.await
                            yield ()
                        }
                    )
                    _    <- done.await
                    late <- lateRef.get
                    rows <- Sql.from[Ledger]("l").run
                yield late match
                    case Present(Result.Failure(_: SqlRequestTransactionEndedException)) =>
                        // Ran after the COMMIT, so it was refused, and the seed committed on its own, which is what the
                        // body actually did.
                        assert(
                            outcome.isSuccess && rows.map(_.id) == Chunk(1),
                            s"${backend.label} round $round: with no failure inside the transaction the seed must commit, " +
                                s"got outcome $outcome holding ${rows.map(_.id)}"
                        )
                    case Present(Result.Failure(_)) =>
                        // The server refused it, so it ran inside, and the transaction must roll back and say so.
                        outcome match
                            case Result.Failure(_: SqlRequestTransactionFailedStatementException) =>
                                assert(
                                    rows.isEmpty,
                                    s"${backend.label} round $round: a statement failed while the transaction was open, so it must " +
                                        s"commit nothing, got ${rows.map(_.id)}"
                                )
                            case other =>
                                fail(s"${backend.label} round $round: a statement failed while the transaction was open, so the " +
                                    s"commit must become a typed rollback, got $other holding ${rows.map(_.id)}")
                    case other =>
                        fail(s"${backend.label} round $round: the forked statement must be refused by the server or as late, got $other")
            }.andThen(succeed)
        }
    }

    /** The transaction's connection goes back to the pool at COMMIT, and a fiber the body forked keeps the context that names it. On a pool
      * of one connection the next transaction holds that same session, so a statement the fiber issues then would join a transaction it was
      * never part of, and be committed or rolled back with it. The statement was written for a transaction that has ended, and refusing it
      * is the only answer that does not change what it means.
      */
    "a statement from a fiber that outlives its transaction is refused rather than run on the session's next borrower" - {
        forEachBackend(SqlConfig(maxConnections = 1, acquireTimeout = 10.seconds)) { (backend, client, _) =>
            for
                _     <- createLedger(backend, client)
                go    <- Latch.init(1)
                fiber <- client.transaction {
                    Sql.insert[Ledger].values(Ledger(1, "first")).run.andThen {
                        Fiber.initUnscoped(
                            go.await.andThen(Abort.run[SqlException](Sql.insert[Ledger].values(Ledger(2, "late")).run.unit))
                        )
                    }
                }
                late <- client.transaction {
                    // The late statement is released while this transaction holds the connection and has nothing in
                    // flight, which is the ordering that lands it inside this transaction when nothing refuses it.
                    go.release.andThen(fiber.get).flatMap { late =>
                        Sql.insert[Ledger].values(Ledger(3, "second")).run.andThen(late)
                    }
                }
                rows <- Sql.from[Ledger]("l").orderBy(_.l.id.asc).run
            yield
                late match
                    case Result.Failure(_: SqlRequestTransactionEndedException) => succeed
                    case other                                                  =>
                        fail(s"${backend.label}: a statement issued after its transaction ended must be refused as late, got $other")
                end match
                assert(
                    rows.map(_.id) == Chunk(1, 3),
                    s"${backend.label}: the late statement must not join the next transaction on the session, got ${rows.map(_.id)}"
                )
        }
    }

end SqlTransactionSemanticsConformanceTest
