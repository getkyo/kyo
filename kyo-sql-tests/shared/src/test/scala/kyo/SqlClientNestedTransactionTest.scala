package kyo

import kyo.Sql.*

/** Covers what `transaction { transaction { ... } }` does on every backend that contributes a descriptor: the inner call takes a savepoint
  * instead of opening a second transaction, and the two levels commit and roll back independently of each other.
  *
  * Driven against real servers through the typed nested-transaction surface ([[SqlClient.transaction]]), because a savepoint's whole point is
  * what the server does with it; the suite writes no raw `SAVEPOINT`. One body runs unchanged on each backend because the savepoint machinery
  * lives above the engine: the depth counter and the `kyo_sp_<depth>_` names come from [[SqlClient]], not the dialect. The depth-introspection
  * leaf reads the fiber's [[SqlClient.txLocal]], which is where the savepoint stack is observable at all.
  *
  * The `note` table uses `VARCHAR`, a DDL type that reads identically on every shipping engine, so the DDL carries no engine-specific branch.
  */
class SqlClientNestedTransactionTest extends SqlBackendTest:

    private case class Note(body: String) derives SqlSchema, CanEqual

    /** An arbitrary [[SqlException]] used only to force a rollback: the transaction machinery rolls back for any `Abort[SqlException]`, and
      * these leaves are not about which one.
      */
    private def rollbackTrigger(marker: Long)(using Frame): SqlException =
        SqlRequestAdvisoryLockException(marker, Absent)

    private def createNotes(client: SqlClient)(using Frame): Unit < (Async & Abort[SqlException]) =
        client.executeRaw("CREATE TABLE note (body VARCHAR(64) NOT NULL)").unit

    private def addNote(body: String)(using Frame): Unit < (Abort[SqlException] & DB) =
        Sql.insert[Note].values(Note(body)).run.unit

    /** Every `body` in the `note` table, sorted, so an assertion names exactly what survived. */
    private def notes(using Frame): Chunk[String] < (Abort[SqlException] & DB) =
        Sql.from[Note]("n").select(c => c.n.body).orderBy(c => c.n.body.asc).run

    "an inner transaction that returns keeps its work, and the outer commit keeps both" - forEachBackend() { (_, client, _) =>
        for
            _ <- createNotes(client)
            _ <- client.transaction {
                addNote("outer").andThen {
                    client.transaction(addNote("inner"))
                }
            }
            body <- notes
        yield assert(body == Chunk("inner", "outer"), s"both levels must be committed, saw $body")
    }

    "an inner transaction that fails rolls back to its savepoint and leaves the outer running" - forEachBackend() { (_, client, _) =>
        for
            _ <- createNotes(client)
            _ <- client.transaction {
                addNote("outer").andThen {
                    Abort.run[SqlException](
                        client.transaction(
                            addNote("inner").andThen(Abort.fail[SqlException](rollbackTrigger(1L)))
                        )
                    ).andThen {
                        // The outer transaction is still open and still usable: that is what rolling back to a
                        // savepoint means, as opposed to rolling back the transaction.
                        addNote("after")
                    }
                }
            }
            body <- notes
        yield assert(
            body == Chunk("after", "outer"),
            s"the inner insert must be gone and the outer work kept, saw $body"
        )
    }

    "an outer transaction that fails discards the inner work too" - forEachBackend() { (_, client, _) =>
        for
            _ <- createNotes(client)
            _ <- Abort.run[SqlException](
                client.transaction {
                    addNote("outer").andThen {
                        client.transaction(addNote("inner")).andThen {
                            Abort.fail[SqlException](rollbackTrigger(2L))
                        }
                    }
                }
            )
            body <- notes
        yield assert(body == Chunk.empty[String], s"an outer rollback must discard both levels, saw $body")
    }

    "each nesting level pushes one savepoint, named for its depth under the reserved prefix" - forEachBackend() { (_, client, _) =>
        client.transaction {
            SqlClient.txLocal.use { outer =>
                client.transaction {
                    SqlClient.txLocal.use { middle =>
                        client.transaction {
                            SqlClient.txLocal.use { inner =>
                                val outerCtx  = outer.getOrElse(fail("the outermost transaction must install a context"))
                                val middleCtx = middle.getOrElse(fail("the first nested transaction must install a context"))
                                val innerCtx  = inner.getOrElse(fail("the second nested transaction must install a context"))

                                assert(outerCtx.depth == 0, s"the outermost transaction is depth 0, was ${outerCtx.depth}")
                                assert(middleCtx.depth == 1, s"the first nesting is depth 1, was ${middleCtx.depth}")
                                assert(innerCtx.depth == 2, s"the second nesting is depth 2, was ${innerCtx.depth}")

                                assert(
                                    outerCtx.savepointStack == Chunk.empty[String],
                                    s"the outermost transaction owns a real BEGIN and no savepoint, had ${outerCtx.savepointStack}"
                                )
                                assert(
                                    middleCtx.savepointStack.size == 1 && innerCtx.savepointStack.size == 2,
                                    s"each nesting pushes exactly one savepoint, had ${middleCtx.savepointStack.size}" +
                                        s" and ${innerCtx.savepointStack.size}"
                                )
                                assert(
                                    middleCtx.savepointStack.head.startsWith("kyo_sp_1_"),
                                    s"depth 1's savepoint must be named for its depth, was ${middleCtx.savepointStack.head}"
                                )
                                assert(
                                    innerCtx.savepointStack.head.startsWith("kyo_sp_2_"),
                                    s"depth 2's savepoint must be named for its depth, was ${innerCtx.savepointStack.head}"
                                )
                                assert(
                                    innerCtx.savepointStack(1) == middleCtx.savepointStack.head,
                                    "the inner stack must carry the outer savepoint underneath its own, most recent first"
                                )
                                assert(
                                    innerCtx.savepointStack.head != middleCtx.savepointStack.head,
                                    "two levels must not share a savepoint name"
                                )
                                assert(
                                    innerCtx.connection eq middleCtx.connection,
                                    "a nested transaction reuses the outer connection rather than leasing another"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

end SqlClientNestedTransactionTest
