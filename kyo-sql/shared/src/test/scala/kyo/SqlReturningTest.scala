package kyo

import kyo.SqlAst.*

/** Tests for user-specified RETURNING clauses on INSERT, UPDATE, and DELETE.
  *
  * 4 leaves verifying the G-Parity-16 gap closure:
  *   - INSERT.returning(id, created_at) renders on PG, both columns appear after RETURNING.
  *   - UPDATE.returning(version) renders on PG.
  *   - DELETE.returning(id) renders on PG.
  *   - RETURNING on MySQL raises SqlException.Unsupported.
  */
class SqlReturningTest extends Test:

    case class Event(id: Long, name: String, createdAt: String) derives Schema

    case class Versioned(id: Long, value: String, version: Long) derives Schema

    given CanEqual[Any, Any] = CanEqual.derived

    // Leaf 1: INSERT.returning(id, createdAt) on PG, both columns appear in RETURNING list.
    "INSERT.returning(id, createdAt) emits both columns on PG" in {
        val s = Sql.insert[Event].values(Event(0L, "boot", "2024-01-01")).returning(_.id, _.createdAt)
        val r = s.render(SqlBackend.Postgres)
        assert(r.sql.contains("RETURNING"))
        assert(r.sql.contains(""""id""""))
        assert(r.sql.contains(""""createdAt""""))
        // Both columns must appear after the single RETURNING keyword.
        val returningIdx   = r.sql.indexOf("RETURNING")
        val afterReturning = r.sql.substring(returningIdx)
        assert(afterReturning.contains(""""id""""))
        assert(afterReturning.contains(""""createdAt""""))
    }

    // Leaf 2: UPDATE.returning(version) on PG, column appears after RETURNING.
    "UPDATE.returning(version) emits on PG" in {
        val s = Sql.update[Versioned].set(_.value := "x").returning(_.version).where(_.id == 1L)
        val r = s.render(SqlBackend.Postgres)
        assert(r.sql.contains("UPDATE"))
        assert(r.sql.contains("RETURNING"))
        val returningIdx   = r.sql.indexOf("RETURNING")
        val afterReturning = r.sql.substring(returningIdx)
        assert(afterReturning.contains(""""version""""))
    }

    // Leaf 3: DELETE.returning(id) on PG, id appears after RETURNING.
    "DELETE.returning(id) emits on PG" in {
        val s = Sql.delete[Event].returning(_.id).where(_.name == "boot")
        val r = s.render(SqlBackend.Postgres)
        assert(r.sql.startsWith("DELETE FROM"))
        assert(r.sql.contains("RETURNING"))
        val returningIdx   = r.sql.indexOf("RETURNING")
        val afterReturning = r.sql.substring(returningIdx)
        assert(afterReturning.contains(""""id""""))
    }

    // Leaf 4: RETURNING on MySQL raises SqlException.Unsupported (requires Frame for typed error).
    "RETURNING on MySQL raises Unsupported" in {
        val s = Sql.insert[Event].values(Event(0L, "boot", "2024-01-01")).returning(_.id)
        val ex = intercept[SqlException.Unsupported] {
            s.render(SqlBackend.Mysql)
        }
        assert(ex.getMessage.contains("RETURNING"))
    }

end SqlReturningTest
