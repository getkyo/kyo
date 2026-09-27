package kyo

import kyo.Sql.*

/** What `.run` decodes and where it runs, once per backend: a decoded `Chunk[A]`, a `.run` inside `transaction { ... }` landing on the
  * transaction's pinned connection, and the ambient `DB` forms reaching one connection and one set of data.
  */
trait SqlRunConformanceTest extends SqlBackendTest:

    private case class Person(id: Long, name: String, age: Int, deptId: Long) derives SqlSchema

    /** One column holding whichever server session wrote the row. See the routing leaves below. */
    private case class Probe(pid: Long) derives SqlSchema

    "Query.run returns a decoded Chunk[A]" - forEachBackend() { (_, client, _) =>
        // `deptId` is the one mixed-case column, so it is quoted through the live dialect: an unquoted `deptId` folds
        // to lowercase on a case-folding engine, and the case-preserving reference the renderer emits would then not
        // find it. The other identifiers are lowercase and need no quoting, and `name` is a portable VARCHAR.
        val ddl =
            "CREATE TABLE person (id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL, age INT NOT NULL, " +
                s"${client.dialect.quoteIdent("deptId")} BIGINT NOT NULL)"
        client.executeRaw(ddl)
            .andThen(client.executeRaw("INSERT INTO person VALUES (1, 'ada', 36, 7)"))
            .andThen(client.executeRaw("INSERT INTO person VALUES (2, 'bob', 41, 7)"))
            .andThen {
                Sql.from[Person]("p").select(c => (c.p.name, c.p.age)).orderBy(c => c.p.id.asc).run.map { rows =>
                    assert(rows == Chunk(("ada", 36), ("bob", 41)), s"expected both rows decoded in id order, got $rows")
                }
            }
    }

    // The routing property behind `.run`: `SqlClient.routed` reads `txLocal` and uses the transaction's pinned
    // connection rather than leasing from the pool, which is the whole reason a `.run` inside a `transaction { ... }`
    // body needs no receiver.
    //
    // Asserted by session identity, not by the query succeeding. A `.run` that leased a fresh connection would still
    // return rows against a committed table and still satisfy a result-only assertion, so the fixture is built so
    // that only the pinned connection can produce the expected value:
    //
    //   - The server records which session wrote the row, and that is precisely what `Connection.id` carries for each
    //     adapter, so the row's value is comparable to the id on the context `txLocal` holds.
    //   - The row is uncommitted, so no other session can read it at all. A `.run` on a pooled connection reads
    //     Chunk.empty and fails on the count before it ever reaches the value.
    //
    // The second assertion closes the one way the first could pass while the routing is broken: if the INSERT had
    // autocommitted, any connection could read the row and report the inserting session's id. Reading through a
    // second client, with `txLocal` cleared so the read cannot be routed onto the pinned connection, is what makes
    // the invisibility a fact rather than an assumption.

    /** Asserts that a `.run` issued inside `client`'s transaction executes on that transaction's pinned connection.
      *
      * `sessionIdSql` is the engine's own name for the current session, which is the value `Connection.id` mirrors. `observer` is a second
      * client with its own pool, so a read issued through it cannot land on `client`'s connection.
      */
    private def assertRunUsesPinnedConnection(client: SqlClient, observer: SqlClient, sessionIdSql: String)(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Async & Abort[SqlException] & Scope & DB) =
        client.transaction {
            SqlClient.txLocal.use { active =>
                val pinned = active.getOrElse(fail("transaction must install a TransactionContext")).session.connection
                client.executeRaw(s"INSERT INTO probe VALUES ($sessionIdSql)").andThen {
                    Sql.from[Probe]("p").select(c => c.p.pid).run.flatMap { seen =>
                        SqlClient.txLocal.let(Absent)(observer.query("SELECT pid FROM probe")).map { outside =>
                            assert(
                                seen == Chunk(pinned.id),
                                s"Q.run must read the row the transaction's own session ${pinned.id} wrote, it read $seen"
                            )
                            assert(
                                outside.isEmpty,
                                s"the row must stay invisible to any other session while the transaction is open, one saw ${outside.size}"
                            )
                        }
                    }
                }
            }
        }

    "transaction { Q.run } runs on the transaction's connection, not a pooled one" - forEachBackend(
        where = _.sessionIdSql.nonEmpty
    ) { (backend, client, schema) =>
        // The SQL expression yielding the server's own session id has no cross-engine spelling, so the descriptor
        // names it through `backend.sessionIdSql`. `observer` is a second client with its own pool, opened from the
        // same fresh schema, so its reads cannot land on the transaction's pinned connection.
        val sessionIdSql = backend.sessionIdSql.getOrElse(throw new AssertionError(s"${backend.label} has no session id"))
        backend.open(schema.url).flatMap { observer =>
            client.executeRaw("CREATE TABLE probe (pid BIGINT NOT NULL)").andThen {
                assertRunUsesPinnedConnection(client, observer, sessionIdSql)
            }
        }
    }

    /** The same property on an engine with no per-session identifier to compare against.
      *
      * Asking the server which session it is cannot work where there is no such value, and a constant would pass no matter which session
      * ran the statement. What can still be observed is VISIBILITY, and it is the half that actually matters: an uncommitted row is
      * readable through the transaction and invisible to every other session, which is only true if `Q.run` went to the transaction's own
      * connection rather than to a second one from the pool.
      */
    "transaction { Q.run } reads its own uncommitted write, which no other session can see" - forEachBackend(
        where = _.sessionIdSql.isEmpty
    ) { (backend, client, schema) =>
        backend.open(schema.url).flatMap { observer =>
            client.executeRaw("CREATE TABLE probe (pid BIGINT NOT NULL)").andThen {
                client.transaction {
                    client.executeRaw("INSERT INTO probe VALUES (7)").andThen {
                        Sql.from[Probe]("p").select(c => c.p.pid).run.flatMap { seen =>
                            SqlClient.txLocal.let(Absent)(observer.query("SELECT pid FROM probe")).map { outside =>
                                assert(
                                    seen == Chunk(7L),
                                    s"Q.run must read the row the transaction's own session wrote, it read $seen"
                                )
                                assert(
                                    outside.isEmpty,
                                    s"the row must stay invisible to any other session while the transaction is open, one saw ${outside.size}"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // `DB.executeRaw`, `DB.transaction`, and the fragment run forms all read the client the enclosing `DB.run`
    // installed, which is what lets a unit of work be written without threading a receiver through it. The group
    // runs them together rather than one leaf each, because what needs asserting is that they reach the SAME
    // connection and the same data: four statements that each work in isolation would still be four sessions.

    "the ambient common path: DB.executeRaw, fragment execute, and fragment run inside DB.transaction" - forEachBackend() {
        (backend, _, _) =>
            val first  = "first"
            val second = "second"
            for
                _        <- DB.executeRaw(s"CREATE TABLE note (body ${backend.textColumnType} NOT NULL)")
                affected <- DB.transaction {
                    sql"INSERT INTO note (body) VALUES ($first)".execute.flatMap { one =>
                        sql"INSERT INTO note (body) VALUES ($second)".execute.map(_ + one)
                    }
                }
                rows <- sql"SELECT body FROM note ORDER BY body".as[String].run
            yield
                assert(affected == 2L, s"each fragment execute reports its own affected-row count, got $affected")
                assert(rows == Chunk(first, second), s"a committed transaction's rows must be readable after it, got $rows")
            end for
    }

    // The other half of the transaction contract on the ambient form: an aborted body leaves nothing behind. Without
    // it, a `DB.transaction` that silently never opened one would pass the leaf above, since both inserts commit
    // either way.
    "DB.transaction rolls back its body's writes when the body aborts" - forEachBackend() { (backend, _, _) =>
        val doomed = "doomed"
        for
            _       <- DB.executeRaw(s"CREATE TABLE note (body ${backend.textColumnType} NOT NULL)")
            outcome <- Abort.run[SqlException] {
                DB.transaction {
                    // The second statement names a column the table does not have, so the server rejects it and the
                    // body aborts with a real failure rather than a fabricated one.
                    sql"INSERT INTO note (body) VALUES ($doomed)".execute.andThen(
                        DB.executeRaw("INSERT INTO note (missing_column) VALUES ('x')")
                    )
                }
            }
            rows <- sql"SELECT body FROM note".as[String].run
        yield
            assert(outcome.isFailure, s"the abort must reach the caller rather than being swallowed, got $outcome")
            assert(rows.isEmpty, s"the rolled-back insert must leave no row, got $rows")
        end for
    }

end SqlRunConformanceTest
