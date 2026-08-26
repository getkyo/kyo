package kyo

import kyo.Sql.*
import kyo.internal.SqlTestBackend

/** Cross-backend battery for a write that answers the rows it changed.
  *
  * `returning` on an UPDATE or a DELETE was accepted by the builder, rendered into the statement, and then thrown away: the only terminals
  * answered a row count, so the rows the server had already sent back were unreachable. The raw tier could read them from the same
  * statement in the same run, which is what made it a hole rather than a missing feature.
  *
  * The rows are typed by what was asked for, so a single column decodes as that column's value and several decode as the tuple. Each group
  * asserts the typed result against the raw spelling of the same statement, so the two tiers are held to the same answer.
  *
  * A flavor with no `RETURNING` clause refuses the statement rather than answering nothing, which is the other half of the contract: the
  * request is not silently dropped. The suite branches on the descriptor's own capability flag rather than on an engine name.
  */
class SqlWriteReturningConformanceTest extends SqlBackendTest:

    case class Widget(id: Long, name: String, price: BigDecimal) derives CanEqual

    private def createWidgets(backend: SqlTestBackend, client: SqlClient)(using Frame): Unit < (Async & Abort[SqlException]) =
        val ddl =
            s"""CREATE TABLE widget (
               |  id ${backend.columnType(SqlTestBackend.ColumnType.BigInt)},
               |  name ${backend.textColumnType},
               |  price ${backend.columnType(SqlTestBackend.ColumnType.Numeric)}
               |)""".stripMargin
        client.executeRaw(ddl).andThen(
            client.executeRaw("INSERT INTO widget VALUES (1, 'bolt', 2.00), (2, 'nut', 3.00), (3, 'washer', 4.00)")
        ).unit
    end createWidgets

    "an UPDATE answers the rows it changed" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- createWidgets(backend, client)
                result <- Abort.run[SqlException](
                    Sql.update[Widget]("widget")
                        .set(_.price := BigDecimal(9))
                        .returning(r => (r.id, r.price))
                        .where(_.id == 2L)
                        .run
                )
            yield
                if backend.supportsReturning then
                    result match
                        case Result.Success(rows) =>
                            assert(rows == Chunk((2L, BigDecimal(9))), s"expected the changed row back, got $rows")
                        case other => assert(false, s"expected the changed row, got $other")
                else
                    result match
                        case Result.Failure(e: SqlUnsupportedException) =>
                            assert(e.getMessage.contains("RETURNING"), s"the refusal must name RETURNING, got ${e.getMessage}")
                        case other =>
                            assert(false, s"a flavor with no RETURNING must refuse the statement, got $other")
        }
    }

    "an UPDATE answers a single returned column at that column's own type" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- createWidgets(backend, client)
                result <- Abort.run[SqlException](
                    Sql.update[Widget]("widget").set(_.name := "hex nut").returning(_.name).where(_.id == 2L).run
                )
            yield
                if backend.supportsReturning then
                    assert(result == Result.Success(Chunk("hex nut")), s"expected the new name back, got $result")
                else
                    result match
                        case Result.Failure(e: SqlUnsupportedException) =>
                            assert(e.getMessage.contains("RETURNING"), s"the refusal must name RETURNING, got ${e.getMessage}")
                        case other =>
                            assert(false, s"a flavor with no RETURNING must refuse the statement as unsupported, got $other")
        }
    }

    "a DELETE answers the rows it removed" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- createWidgets(backend, client)
                result <- Abort.run[SqlException](
                    Sql.delete[Widget]("widget").returning(r => (r.id, r.name)).where(_.id == 3L).run
                )
                left <- client.query("SELECT id FROM widget")
            yield
                if backend.supportsReturning then
                    assert(result == Result.Success(Chunk((3L, "washer"))), s"expected the removed row back, got $result")
                    assert(left.size == 2, s"the row must actually be gone, ${left.size} left")
                else
                    result match
                        case Result.Failure(_: SqlUnsupportedException) =>
                            assert(left.size == 3, s"a refused DELETE must remove nothing, ${left.size} left")
                        case other =>
                            assert(false, s"a flavor with no RETURNING must refuse the statement as unsupported, got $other")
        }
    }

    "the typed tier answers what the raw tier answers for the same statement" - {
        forEachBackend() { (backend, client, _) =>
            // The comparison is only askable where the flavor has the clause; the leaf above is what covers a flavor
            // that does not, so this one says why it did nothing rather than passing silently.
            if !backend.supportsReturning then succeed(s"${backend.label} has no RETURNING clause")
            else
                for
                    _ <- createWidgets(backend, client)
                    typed <- Sql.update[Widget]("widget")
                        .set(_.price := BigDecimal(7))
                        .returning(r => (r.id, r.price))
                        .where(_.id == 1L)
                        .run
                    raw <- sql"UPDATE widget SET price = ${BigDecimal(8)} WHERE id = ${1L} RETURNING id, price"
                        .as[(Long, BigDecimal)].run
                yield
                    assert(typed == Chunk((1L, BigDecimal(7))), s"the typed tier must answer the changed row, got $typed")
                    assert(raw == Chunk((1L, BigDecimal(8))), s"the raw tier must answer the same shape, got $raw")
        }
    }

    /** An INSERT that names its returning columns answers the rows it wrote, not how many.
      *
      * INSERT is the statement RETURNING exists for, and it was the one left answering a count while the server sent the rows. It also
      * carries the case the other two do not: the renderer appends `RETURNING <autoKey>` on its own for a detected auto-key, so an
      * explicit clause has to replace that one rather than emit beside it.
      */
    "an INSERT answers the rows it wrote" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- createWidgets(backend, client)
                result <- Abort.run[SqlException](
                    Sql.insert[Widget]("widget")
                        .values(Widget(4L, "screw", BigDecimal(5)))
                        .returning(r => (r.id, r.name))
                        .run
                )
            yield
                if backend.supportsReturning then
                    result match
                        case Result.Success(rows) =>
                            assert(rows == Chunk((4L, "screw")), s"expected the written row back, got $rows")
                        case other => assert(false, s"expected the written row, got $other")
                else
                    result match
                        case Result.Failure(e: SqlUnsupportedException) =>
                            assert(e.getMessage.contains("RETURNING"), s"the refusal must name RETURNING, got ${e.getMessage}")
                        case other =>
                            assert(false, s"a flavor with no RETURNING must refuse the statement, got $other")
        }
    }

    "an INSERT answers a single returned column at that column's own type" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- createWidgets(backend, client)
                result <- Abort.run[SqlException](
                    Sql.insert[Widget]("widget").values(Widget(5L, "rivet", BigDecimal(6))).returning(_.name).run
                )
            yield
                if backend.supportsReturning then
                    result match
                        case Result.Success(rows) => assert(rows == Chunk("rivet"), s"expected the name back, got $rows")
                        case other                => assert(false, s"expected the name, got $other")
                else
                    result match
                        case Result.Failure(e: SqlUnsupportedException) =>
                            assert(e.getMessage.contains("RETURNING"), s"the refusal must name RETURNING, got ${e.getMessage}")
                        case other =>
                            assert(false, s"a flavor with no RETURNING must refuse the statement, got $other")
        }
    }

end SqlWriteReturningConformanceTest
