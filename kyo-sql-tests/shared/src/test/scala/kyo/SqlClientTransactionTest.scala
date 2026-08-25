package kyo

import kyo.internal.SqlSharedContainers
import kyo.internal.SqlSharedContainers.Backend
import scala.compiletime.testing.typeChecks

/** Covers `SqlClient.transaction`: the isolation vocabulary, the effect row it hands back, and
  * what each of them actually does to a server.
  *
  * One file rather than three. The isolation levels, the typed-error effect row, and the commit-or-roll-back behavior are three aspects of
  * the same method, and splitting them left two files of thirty-odd lines each whose relationship to this one had to be explained.
  * Savepoints are the exception and live in [[SqlClientNestedTransactionTest]], because nesting has five scenarios of its own.
  */
class SqlClientTransactionTest extends SqlContainerTest:

    override def timeout: Duration = 5.minutes

    case class DomainError(reason: String) derives CanEqual

    private def pgUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    private def myUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    private def text(row: SqlRow): String =
        row.column(0) match
            case Present(bytes) => new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
            case Absent         => "null"

    // --- The isolation vocabulary ---

    "IsolationLevel names the four standard levels and nothing else" in {
        val levels = SqlClient.IsolationLevel.values.toList
        assert(
            levels == List(
                SqlClient.IsolationLevel.ReadUncommitted,
                SqlClient.IsolationLevel.ReadCommitted,
                SqlClient.IsolationLevel.RepeatableRead,
                SqlClient.IsolationLevel.Serializable
            ),
            s"expected the four ISO SQL levels in ascending strictness, got $levels"
        )
    }

    "IsolationLevel compares by identity" in {
        assert(SqlClient.IsolationLevel.ReadCommitted == SqlClient.IsolationLevel.ReadCommitted)
        assert(SqlClient.IsolationLevel.Serializable != SqlClient.IsolationLevel.ReadCommitted)
    }

    "matching on IsolationLevel is exhaustive over the four levels" in {
        def name(level: SqlClient.IsolationLevel): String = level match
            case SqlClient.IsolationLevel.ReadUncommitted => "READ UNCOMMITTED"
            case SqlClient.IsolationLevel.ReadCommitted   => "READ COMMITTED"
            case SqlClient.IsolationLevel.RepeatableRead  => "REPEATABLE READ"
            case SqlClient.IsolationLevel.Serializable    => "SERIALIZABLE"
        assert(SqlClient.IsolationLevel.values.toList.map(name) ==
            List("READ UNCOMMITTED", "READ COMMITTED", "REPEATABLE READ", "SERIALIZABLE"))
    }

    // --- The effect row transaction hands back ---

    "transaction puts the body's own error type on the return row" in {
        // The positive half alone would pass even if the body's row were widened away, so the negative half is
        // what makes this a test: the two rows must be distinguishable. `transaction` carries the body's own `S`,
        // so an error the body raises arrives on the row the caller already declared for it.
        // Each snippet declares its own error type, since typeChecks compiles a literal on its own.
        assert(
            typeChecks("""import kyo.*
case class Domain(reason: String)
def probe(using Frame): Int < (Async & Abort[SqlException | Domain] & DB) =
    DB.transaction[Int, Abort[Domain]](Abort.fail(Domain("boom")))"""),
            "transaction must hand back the body's own Abort[Domain] alongside Abort[SqlException]"
        )
        assert(
            !typeChecks("""import kyo.*
case class Domain(reason: String)
def probe(using Frame): Int < (Abort[SqlException] & DB) =
    DB.transaction[Int, Abort[Domain]](Abort.fail(Domain("boom")))"""),
            "an SqlException-only row must not absorb the body's Domain; there is no S satisfying both bounds"
        )
    }

    "the isolation and readOnly overload keeps the body's error on the row as well" in {
        assert(
            typeChecks("""import kyo.*
case class Domain(reason: String)
def probe(using Frame): Int < (Async & Abort[SqlException | Domain] & DB) =
    DB.transaction[Int, Abort[Domain]](Maybe.Absent, readOnly = true)(Abort.fail(Domain("boom")))"""),
            "the isolation and readOnly overload must carry the body's error too"
        )
    }

    "transaction leaves the body's success type alone" in {
        assert(
            typeChecks("""import kyo.*
case class Domain(reason: String)
def probe(using Frame): String < (Async & Abort[SqlException | Domain] & DB) =
    DB.transaction[String, Abort[Domain]]("ok")"""),
            "a String body must come back as a String"
        )
        assert(
            !typeChecks("""import kyo.*
case class Domain(reason: String)
def probe(using Frame): Int < (Async & Abort[SqlException | Domain] & DB) =
    DB.transaction[String, Abort[Domain]]("ok")"""),
            "a String body must not be readable as an Int"
        )
    }

    // --- What it does to a server ---

    "a typed error inside an explicitly-typed transaction surfaces as itself and rolls the work back" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.init(pgUrl(ctx)).flatMap { client =>
                    DB.run(client) {
                        client.executeRaw("CREATE TABLE note (body TEXT NOT NULL)").andThen {
                            Abort.run[DomainError](
                                Abort.run[SqlException](
                                    client.transaction {
                                        client.executeRaw("INSERT INTO note VALUES ('inside')").andThen {
                                            Abort.fail(DomainError("caller changed its mind"))
                                        }
                                    }
                                )
                            ).flatMap { outcome =>
                                client.query("SELECT count(*)::text FROM note").map { rows =>
                                    assert(rows.size == 1)
                                    assert(text(rows(0)) == "0", s"the insert must have been rolled back, count was ${text(rows(0))}")
                                    outcome match
                                        case Result.Failure(DomainError(reason)) =>
                                            assert(reason == "caller changed its mind")
                                        case other =>
                                            fail(s"expected the DomainError to survive the rollback, got $other")
                                    end match
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // The guard on `reraise`. A `reraise` that stringified anything other than a `SqlException` would deliver
    // the body's `DomainError` as `Result.Panic(RuntimeException)`, leaving the caller unable to match on their
    // own error at all.
    "a body's typed error surfaces as itself rather than as a panic" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.init(pgUrl(ctx)).flatMap { client =>
                    DB.run(client) {
                        client.executeRaw("CREATE TABLE note (body TEXT NOT NULL)").andThen {
                            Abort.run[DomainError](
                                Abort.run[SqlException](
                                    client.transaction {
                                        client.executeRaw("INSERT INTO note VALUES ('inside')").andThen {
                                            Abort.fail(DomainError("boom"))
                                        }
                                    }
                                )
                            ).flatMap { outcome =>
                                client.query("SELECT count(*)::text FROM note").map { rows =>
                                    assert(rows.size == 1)
                                    assert(text(rows(0)) == "0", s"the insert must have been rolled back, count was ${text(rows(0))}")
                                    outcome match
                                        case Result.Failure(DomainError(reason)) =>
                                            assert(reason == "boom")
                                        case other =>
                                            fail(s"a body's typed error must surface as itself, not as a panic, got $other")
                                    end match
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "a body's typed error retires the session and returns its permit, so the next statement gets a fresh one" in {
        // The half a permit assertion cannot see. A lease resolution that caught `SqlException` only would let a
        // caller's own typed `E` skip it and fall back to a `Sync.ensure` finalizer that parks until the fiber
        // ends, leaving the connection in neither the idle ring nor closed: invisible to `cancelsInFlight` and so
        // not waited on by `closeAll`, on the exact path a caller's own typed failure takes.
        //
        // Asserted by session identity rather than by the next statement succeeding, because with the permit back
        // the next statement succeeds either way: it just opens a fresh connection. With one connection configured,
        // a statement after the failure must land on the SAME backend the transaction used.
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.init(pgUrl(ctx), SqlConfig(maxConnections = 1)).flatMap { client =>
                    DB.run(client) {
                        // On a one-connection pool the first statement rendered inside the transaction reads the
                        // server version off the transaction's own session rather than leasing a second connection,
                        // so this leaf is about the connection's fate and never stalls on a version lease.
                        Abort.run[DomainError](
                            Abort.run[SqlException](
                                client.transaction {
                                    // Carry the backend pid out through the failure itself, so the comparison
                                    // needs no state outside the transaction.
                                    client.query("SELECT pg_backend_pid()::text").flatMap { rows =>
                                        Abort.fail(DomainError(text(rows(0))))
                                    }
                                }
                            )
                        ).flatMap { outcome =>
                            val insidePid = outcome match
                                case Result.Failure(DomainError(pid)) => pid
                                case other                            => fail(s"expected a DomainError carrying the pid, got $other")
                            // Resolved, and resolved by RETIRING the session rather than pooling it. A
                            // caller's own error type is one the pool can form no opinion about, so
                            // `leftSessionIdle` answers false for it and the exit destroys: safer than
                            // handing the next borrower a session an unknown failure may have left
                            // mid-statement. So the property is that the transaction's session is gone and a
                            // fresh one serves the next statement, which on a ONE-connection pool can only
                            // happen if the old one was disposed of and its permit returned. A session left in
                            // neither state leaves this statement with no permit and no session.
                            client.query("SELECT pg_backend_pid()::text").map { rows =>
                                val nextPid = text(rows(0))
                                assert(
                                    nextPid != insidePid,
                                    s"a caller's typed error must retire the session, yet $nextPid came back"
                                )
                                assert(
                                    nextPid.nonEmpty && nextPid.forall(_.isDigit),
                                    s"the replacement must be a live backend, got '$nextPid'"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "a transaction that returns normally commits its work" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.init(pgUrl(ctx)).flatMap { client =>
                    DB.run(client) {
                        client.executeRaw("CREATE TABLE note (body TEXT NOT NULL)").andThen {
                            client.transaction(client.executeRaw("INSERT INTO note VALUES ('kept')")).andThen {
                                client.query("SELECT body FROM note").map { rows =>
                                    assert(rows.size == 1, s"expected the committed row to be there, got ${rows.size}")
                                    assert(text(rows(0)) == "kept")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "the isolation level a transaction names is the one the PG server reports inside it" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.init(pgUrl(ctx)).flatMap { client =>
                    DB.run(client) {
                        client.transaction(Present(SqlClient.IsolationLevel.RepeatableRead), readOnly = false) {
                            client.query("SHOW transaction_isolation")
                        }.map { rows =>
                            assert(rows.size == 1)
                            assert(
                                text(rows(0)) == "repeatable read",
                                s"expected the server to report repeatable read, got '${text(rows(0))}'"
                            )
                        }
                    }
                }
            }
        }
    }

    "a readOnly transaction on MySQL refuses a write" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                MysqlClient.init(myUrl(ctx)).flatMap { client =>
                    DB.run(client) {
                        client.executeRaw("CREATE TABLE note (body VARCHAR(32) NOT NULL)").andThen {
                            Abort.run[SqlException](
                                client.transaction(Maybe.Absent, readOnly = true) {
                                    client.executeRaw("INSERT INTO note VALUES ('nope')")
                                }
                            ).flatMap { outcome =>
                                client.query("SELECT count(*) FROM note").flatMap { rows =>
                                    rows(0).decode[Long](0).map { count =>
                                        assert(count == 0L, s"a read-only transaction must write nothing, count was $count")
                                        outcome match
                                            case Result.Failure(e: SqlServerException) =>
                                                assert(
                                                    e.serverMessage.toLowerCase.contains("read only") ||
                                                        e.serverMessage.toLowerCase.contains("read-only"),
                                                    s"expected the server to name the read-only transaction, got '${e.serverMessage}'"
                                                )
                                            case other =>
                                                fail(s"expected the write to be refused, got $other")
                                        end match
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- A child fiber inherits the transaction, so its statements join it ---

    "a statement in a child fiber inside a transaction runs on the transaction's session" in {
        // Asserts session IDENTITY directly rather than inferring anything from pool contention. TWO connections on
        // purpose: the child COULD take one of its own, so a match proves it chose the transaction's rather than proving
        // it had no alternative. On a one-connection pool a match would be forced and the leaf would assert nothing.
        //
        // The property is that `txLocal` is inheritable, and it is inheritable for atomicity. A child that could not see
        // the transaction would lease its own connection and AUTOCOMMIT, so an aborted `transaction { Async.parallel(a,
        // b) }` would leave both rows committed while the rollback reached only the parent. This leaf therefore pins the
        // guarantee, not a convenience.
        //
        // What it does NOT pin: that concurrent statements on that shared session are safe, which is the session
        // mutex's guarantee and has leaves of its own below (statements forked with no await between them). This
        // leaf forks and AWAITS, so the two statements are sequential and it observes routing in isolation.
        //
        // The child reaches its client through `DB.client`, which makes this leaf cover a second decision: the DB
        // effect is inherited across a fork too (its Env carrier is isolated into the child), but for a different
        // reason than the connection locals (it names a client, not a connection). Without that inheritance the child
        // would not compile, since DB.client would find no state to read.
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.init(pgUrl(ctx), SqlConfig(maxConnections = 2)).flatMap { client =>
                    DB.run(client) {
                        client.serverVersion.andThen {
                            client.transaction {
                                client.query("SELECT pg_backend_pid()::text").flatMap { own =>
                                    val parentPid = text(own(0))
                                    Fiber.initUnscoped(
                                        Abort.run[SqlException](DB.client.map(_.query("SELECT pg_backend_pid()::text")))
                                    ).flatMap { childFiber =>
                                        childFiber.get.map {
                                            case Result.Success(rows) =>
                                                val childPid = text(rows(0))
                                                assert(
                                                    childPid == parentPid,
                                                    s"a child fiber must join the transaction's session, parent was $parentPid and child $childPid"
                                                )
                                                assert(
                                                    childPid.nonEmpty && childPid.forall(_.isDigit),
                                                    s"the reported backend pid must be a real one, got '$childPid'"
                                                )
                                            case other =>
                                                fail(s"the child's statement must succeed on its own lease, got $other")
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

    // --- Concurrent statements inside a body serialise on the session mutex ---

    /** Forks `count` inserts inside one transaction with NO await between the starts, so all of them race onto the
      * pinned session at once. Without the session mutex the protocol frames of different statements interleave on
      * one socket: on PostgreSQL a reader consumes another statement's messages and fails to decode, and on MySQL the
      * session has no barrier to resynchronise against at all. With it they queue, every insert lands, and the commit
      * is atomic. The count read back OUTSIDE the transaction is what proves the commit carried all of them.
      */
    private def forkedInsertsCommit(url: String, count: Int)(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Async & Scope & Abort[SqlException]) =
        SqlClient.init(url).flatMap { client =>
            DB.run(client) {
                client.executeRaw("CREATE TABLE tx_fork (id INT PRIMARY KEY)").andThen {
                    client.transaction {
                        Kyo.foreach(1 to count)(i =>
                            Fiber.initUnscoped(client.execute(sql"INSERT INTO tx_fork VALUES ($i)"))
                        ).flatMap { fibers =>
                            Kyo.foreach(fibers)(_.get).unit
                        }
                    }.andThen {
                        client.query(sql"SELECT COUNT(*) FROM tx_fork").flatMap { rows =>
                            rows(0).decode[Long](0).map { total =>
                                assert(total == count.toLong, s"all $count forked inserts must commit, found $total rows")
                                ()
                            }
                        }
                    }
                }
            }
        }

    "statements forked without an await inside a transaction body all commit on PostgreSQL" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                forkedInsertsCommit(pgUrl(ctx), 24).andThen(succeed)
            }
        }
    }

    "statements forked without an await inside a transaction body all commit on MySQL" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                forkedInsertsCommit(myUrl(ctx), 24).andThen(succeed)
            }
        }
    }

end SqlClientTransactionTest
