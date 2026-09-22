package kyo

import kyo.SqlQualifiedNameConformanceTest.QualifiedProbe
import kyo.internal.SqlTestBackend
import kyo.internal.SqlTestBackend.ColumnType

/** What a SCHEMA-QUALIFIED table name means, run through the DSL against every registered backend.
  *
  * Every engine here has a schema, and every engine spells the qualification the same way: the schema and the table quoted separately with
  * a period between them. What they disagree about is what a schema IS, which is why nothing in this file names one. PostgreSQL holds a
  * schema inside the database, the MySQL lineage calls a database a schema, and the SQLite lineage calls an attached file one; the
  * descriptor answers for its own engine through `defaultSchemaName` and `secondSchema`, and the bodies below read only those.
  *
  * The property under test is that a qualified statement reaches the table it named. It takes a battery rather than a render assertion
  * because the two ways of getting it wrong are quiet. A schema folded into the table name is one identifier containing a period, which
  * only a server can tell you is not a relation. A schema held as session state reaches one pooled connection, and since the same table
  * name usually exists in both schemas the others answer wrong ROWS rather than an error.
  *
  * The statements go through the DSL rather than hand-written SQL, and each leaf covers every statement kind that renders a table name:
  * SELECT, INSERT, UPDATE and DELETE each reach the renderer by their own path, and a hand-written string would exercise none of them. The
  * schema comes from the descriptor, so these run through the RUNTIME renderer; what the compile-time fold emits for each flavor is pinned
  * by [[SqlQualifiedNameStaticTest]].
  *
  * DDL stays raw because the DSL has none.
  */
class SqlQualifiedNameConformanceTest extends SqlBackendTest:

    private val table: String = "qualified_probe"

    /** Qualifying with the schema an unqualified name already resolves in must not change which table is reached.
      *
      * The portable half, and the one every backend answers, since an engine with no second schema still has the one it is sitting in. It
      * is also the leaf that would catch the two-names-quoted-as-one failure on any engine, because `"main.t"` names no relation on any of
      * them.
      *
      * Each statement kind is written qualified and read back unqualified, so a kind that dropped the schema would still pass while a kind
      * that fused the two names fails outright.
      */
    "a name qualified with the default schema reaches the same table as an unqualified one" - {
        forEachBackend() { (backend, client, schema) =>
            val column = backend.columnType(ColumnType.Int)
            val here   = backend.defaultSchemaName(schema)
            val plain  = Sql.from[QualifiedProbe](alias = "p", tableName = table)
            for
                _       <- client.executeRaw(s"CREATE TABLE ${backend.quoteIdent(table)} (v $column)")
                _       <- Sql.insert[QualifiedProbe](schemaName = here, tableName = table).values(QualifiedProbe(7)).run
                qual    <- Sql.from[QualifiedProbe](alias = "p", schemaName = here, tableName = table).run
                unqual  <- plain.run
                _       <- Sql.update[QualifiedProbe](schemaName = here, tableName = table).set(_.v := 8).build.run
                updated <- plain.run
                _       <- Sql.delete[QualifiedProbe](schemaName = here, tableName = table).build.run
                left    <- plain.run
            yield
                assert(
                    unqual == Chunk(QualifiedProbe(7)),
                    s"${backend.label}: the qualified insert must land where the plain name reads, got $unqual"
                )
                assert(qual == unqual, s"${backend.label}: qualified read answered $qual, unqualified answered $unqual")
                assert(updated == Chunk(QualifiedProbe(8)), s"${backend.label}: the qualified update must reach that row, got $updated")
                assert(left.isEmpty, s"${backend.label}: the qualified delete must reach that row, got $left")
            end for
        }
    }

    /** The same table name in two schemas is two tables, and a qualified statement picks the one it named.
      *
      * Runs only where the engine can provision a second schema for a whole pool. The SQLite lineage answers [[Absent]] because its other
      * schemas are attached per connection, so this leaf is not a coverage hole there: reaching a second schema from a pool is a
      * connect-time concern on that lineage and is covered where that attach lives.
      *
      * Both tables carry the same column and differ only in the row they hold, so a statement reaching the wrong schema answers a row
      * rather than an error. Only an assertion on the VALUE sees that; one on the row count would pass either way. Every statement kind is
      * aimed at the second schema and the first schema's row is re-read after each, so a kind that ignored its schema is caught by the
      * damage it does over there.
      */
    "the same table name in two schemas is two tables" - {
        forEachBackend(where = _.hasSecondSchema) { (backend, client, schema) =>
            val second = backend.secondSchema(schema).get
            val column = backend.columnType(ColumnType.Int)
            val there  = s"${backend.quoteIdent(second.name)}.${backend.quoteIdent(table)}"
            val hereQ  = Sql.from[QualifiedProbe](alias = "p", tableName = table)
            val thereQ = Sql.from[QualifiedProbe](alias = "p", schemaName = second.name, tableName = table)
            Scope.ensure {
                // The MySQL lineage's second schema is a database outside the per-leaf one, so it outlives the
                // leaf unless removed here, on every exit edge rather than only the happy one.
                Abort.run[SqlException](Kyo.foreachDiscard(second.drop)(client.executeRaw(_).unit)).unit
            }.andThen {
                for
                    _            <- Kyo.foreachDiscard(second.create)(client.executeRaw(_).unit)
                    _            <- client.executeRaw(s"CREATE TABLE ${backend.quoteIdent(table)} (v $column)")
                    _            <- client.executeRaw(s"CREATE TABLE $there (v $column)")
                    _            <- Sql.insert[QualifiedProbe](tableName = table).values(QualifiedProbe(1)).run
                    _            <- Sql.insert[QualifiedProbe](schemaName = second.name, tableName = table).values(QualifiedProbe(2)).run
                    inserted     <- thereQ.run
                    hereAfterIns <- hereQ.run
                    _            <- Sql.update[QualifiedProbe](schemaName = second.name, tableName = table).set(_.v := 20).build.run
                    updated      <- thereQ.run
                    hereAfterUpd <- hereQ.run
                    _            <- Sql.delete[QualifiedProbe](schemaName = second.name, tableName = table).build.run
                    deleted      <- thereQ.run
                    hereAfterDel <- hereQ.run
                yield
                    assert(inserted == Chunk(QualifiedProbe(2)), s"${backend.label}: ${second.name} holds 2, answered $inserted")
                    assert(
                        hereAfterIns == Chunk(QualifiedProbe(1)),
                        s"${backend.label}: the qualified insert reached the default schema, which now holds $hereAfterIns"
                    )
                    assert(
                        updated == Chunk(QualifiedProbe(20)),
                        s"${backend.label}: the qualified update must reach ${second.name}, which holds $updated"
                    )
                    assert(
                        hereAfterUpd == Chunk(QualifiedProbe(1)),
                        s"${backend.label}: the qualified update reached the default schema, which now holds $hereAfterUpd"
                    )
                    assert(deleted.isEmpty, s"${backend.label}: the qualified delete must empty ${second.name}, which holds $deleted")
                    assert(
                        hereAfterDel == Chunk(QualifiedProbe(1)),
                        s"${backend.label}: the qualified delete reached the default schema, which now holds $hereAfterDel"
                    )
                end for
            }
        }
    }

    /** A qualified statement answers the schema it named on every connection in the pool, concurrently.
      *
      * A schema held as session state (`SET search_path`, `USE`) belongs to the one connection that ran it, so concurrent statements answer
      * from whichever schema their own connection is on, and none of them errors. A qualified name travels in the statement instead, which
      * is what makes every connection answer alike.
      *
      * Ten concurrent reads against a ten-connection pool, every one asserted. A leaf reading once would pass on whichever connection
      * happened to be right.
      */
    "a qualified read answers the same schema on every pooled connection" - {
        forEachBackend(
            config = SqlConfig(maxConnections = 10, minConnections = 10),
            where = _.hasSecondSchema
        ) { (backend, client, schema) =>
            val second = backend.secondSchema(schema).get
            val column = backend.columnType(ColumnType.Int)
            val there  = s"${backend.quoteIdent(second.name)}.${backend.quoteIdent(table)}"
            Scope.ensure {
                Abort.run[SqlException](Kyo.foreachDiscard(second.drop)(client.executeRaw(_).unit)).unit
            }.andThen {
                for
                    _    <- Kyo.foreachDiscard(second.create)(client.executeRaw(_).unit)
                    _    <- client.executeRaw(s"CREATE TABLE ${backend.quoteIdent(table)} (v $column)")
                    _    <- client.executeRaw(s"CREATE TABLE $there (v $column)")
                    _    <- Sql.insert[QualifiedProbe](tableName = table).values(QualifiedProbe(1)).run
                    _    <- Sql.insert[QualifiedProbe](schemaName = second.name, tableName = table).values(QualifiedProbe(2)).run
                    read <- Async.fill(10, 10)(
                        Sql.from[QualifiedProbe](alias = "p", schemaName = second.name, tableName = table).run.map(_.head.v)
                    )
                yield assert(
                    read.forall(_ == 2),
                    s"${backend.label}: every concurrent qualified read must answer ${second.name}'s row, got ${read.mkString(",")}"
                )
                end for
            }
        }
    }

end SqlQualifiedNameConformanceTest

object SqlQualifiedNameConformanceTest:
    /** One `Int` column, so a statement reaching the wrong schema answers a row rather than failing to decode. */
    case class QualifiedProbe(v: Int) derives SqlSchema, CanEqual
end SqlQualifiedNameConformanceTest
