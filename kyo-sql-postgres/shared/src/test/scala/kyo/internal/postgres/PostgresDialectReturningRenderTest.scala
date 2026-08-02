package kyo.internal.postgres

import kyo.*
import kyo.Sql.*
import kyo.SqlUnsupportedException
import kyo.Test

/** Verifies how [[PostgresDialect]] answers for a user-specified `RETURNING` clause on `INSERT`, `UPDATE`, and `DELETE`.
  */
class PostgresDialectReturningRenderTest extends Test:

    case class Event(id: Long, name: String, createdAt: String)

    case class Versioned(id: Long, value: String, version: Long)

    given CanEqual[Any, Any] = CanEqual.derived

    // Leaf 1: INSERT.returning(id, createdAt) on PG, both columns appear in RETURNING list.
    "INSERT.returning(id, createdAt) emits both columns on PG" in {
        val s = Sql.insert[Event].values(Event(0L, "boot", "2024-01-01")).returning(_.id, _.createdAt)
        val r = s.render(PostgresDialect)
        assert(r.onlySql.get.contains("RETURNING"))
        assert(r.onlySql.get.contains(""""id""""))
        assert(r.onlySql.get.contains(""""createdAt""""))
        // Both columns must appear after the single RETURNING keyword.
        val returningIdx   = r.onlySql.get.indexOf("RETURNING")
        val afterReturning = r.onlySql.get.substring(returningIdx)
        assert(afterReturning.contains(""""id""""))
        assert(afterReturning.contains(""""createdAt""""))
    }

    // Leaf 2: UPDATE.returning(version) on PG, column appears after RETURNING.
    "UPDATE.returning(version) emits on PG" in {
        val s = Sql.update[Versioned].set(_.value := "x").returning(_.version).where(_.id == 1L)
        val r = s.render(PostgresDialect)
        assert(r.onlySql.get.contains("UPDATE"))
        assert(r.onlySql.get.contains("RETURNING"))
        val returningIdx   = r.onlySql.get.indexOf("RETURNING")
        val afterReturning = r.onlySql.get.substring(returningIdx)
        assert(afterReturning.contains(""""version""""))
    }

    // Leaf 3: DELETE.returning(id) on PG, id appears after RETURNING.
    "DELETE.returning(id) emits on PG" in {
        val s = Sql.delete[Event].returning(_.id).where(_.name == "boot")
        val r = s.render(PostgresDialect)
        assert(r.onlySql.get.startsWith("DELETE FROM"))
        assert(r.onlySql.get.contains("RETURNING"))
        val returningIdx   = r.onlySql.get.indexOf("RETURNING")
        val afterReturning = r.onlySql.get.substring(returningIdx)
        assert(afterReturning.contains(""""id""""))
    }

end PostgresDialectReturningRenderTest
