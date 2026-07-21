package kyo

import kyo.Sql.render
import kyo.SqlAst.*

/** Static-SQL coverage for [[SqlAst.ValuesFrom]] (G-Probe-6).
  *
  * Verifies that `ValuesFrom` nodes lift through [[SqlStatic.staticSql]] at compile time. Each leaf asserts the compiled SQL strings for
  * both backends. The `ValuesFrom.rows` field is `Chunk[Chunk[BoundValue[?]]]`, pure data that `FromExpr.derived` lifts with zero
  * reflection.
  *
  * Note: `staticSql` requires the query to be written inline, storing it in a `val` introduces a local binding that `q.value` cannot
  * reduce through. All byte-for-byte runtime parity checks duplicate the query expression. STATIC-SQL-INLINE-ONLY: keep both copies of the
  * duplicated expression identical when editing leaves below, drift between them would silently weaken the parity check.
  *
  * Leaf count (audit W-1 correction): 7 leaves in this file; combined with `SqlStaticLateralTest` (7) and `SqlStaticNestedTest` (7) the
  * Phase 9 total is 21, not the "23 tests pass" the original commit message stated. The plan minimum is 3 per AST node, well exceeded.
  *
  * Verification glob (audit W-2 correction): to exercise all three Phase 9 stems use `sbt 'kyo-sql/testOnly *SqlStatic*Test'` (the plan's
  * narrower `*SqlStaticTest` glob misses these three files).
  */
class SqlStaticValuesTest extends Test:

    case class Point(x: Int, y: Int) derives Schema
    case class Person(id: Long, name: String, age: Int, deptId: Long) derives Schema

    // ── Leaf 1, single-row VALUES ────────────────────────────────────────────

    "single-row Sql.values lifts and emits VALUES keyword" in {
        val r = SqlStatic.staticSql(Sql.values[Point]("v", Point(1, 2)))
        assert(r.sql.postgres.contains("VALUES"))
        assert(r.sql.mysql.contains("VALUES"))
        assert(r.params.isEmpty)
    }

    "single-row Sql.values, PG SQL contains the literal values" in {
        val r = SqlStatic.staticSql(Sql.values[Point]("v", Point(1, 2)))
        assert(r.sql.postgres.contains("1"))
        assert(r.sql.postgres.contains("2"))
    }

    "single-row Sql.values, PG SQL matches SqlRender.render byte-for-byte" in {
        val r  = SqlStatic.staticSql(Sql.values[Point]("v", Point(1, 2)))
        val rt = Sql.values[Point]("v", Point(1, 2)).render(SqlBackend.Postgres)
        assert(r.sql.postgres == rt.sql)
    }

    // ── Leaf 2, multi-row VALUES ─────────────────────────────────────────────

    "multi-row Sql.values lifts and emits both rows in SQL" in {
        val r = SqlStatic.staticSql(Sql.values[Point]("v", Point(1, 2), Point(3, 4)))
        assert(r.sql.postgres.contains("VALUES"))
        assert(r.sql.mysql.contains("VALUES"))
        assert(r.params.isEmpty)
    }

    "multi-row Sql.values, PG SQL contains all four literal values" in {
        val r = SqlStatic.staticSql(Sql.values[Point]("v", Point(1, 2), Point(3, 4)))
        // Rendered as (1, 2), (3, 4)
        assert(r.sql.postgres.contains("(1, 2)"))
        assert(r.sql.postgres.contains("(3, 4)"))
    }

    "multi-row Sql.values, MySQL SQL matches SqlRender.render byte-for-byte" in {
        val r  = SqlStatic.staticSql(Sql.values[Point]("v", Point(1, 2), Point(3, 4)))
        val rt = Sql.values[Point]("v", Point(1, 2), Point(3, 4)).render(SqlBackend.Mysql)
        assert(r.sql.mysql == rt.sql)
    }

    // ── Leaf 3, VALUES used with a multi-column case class ──────────────────

    "Sql.values with a 4-column Person row lifts and renders all column names" in {
        val r = SqlStatic.staticSql(Sql.values[Person]("pv", Person(0L, "Alice", 30, 1L)))
        assert(r.sql.postgres.contains("VALUES"))
        assert(r.sql.mysql.contains("VALUES"))
        // The SELECT clause enumerates person's column names via columnNames
        assert(r.sql.postgres.contains(""""pv"."id""""))
        assert(r.sql.postgres.contains(""""pv"."name""""))
        assert(r.sql.postgres.contains(""""pv"."age""""))
        assert(r.sql.postgres.contains(""""pv"."deptId""""))
    }

    "Sql.values 4-column Person, PG SQL matches SqlRender.render byte-for-byte" in {
        val r  = SqlStatic.staticSql(Sql.values[Person]("pv", Person(0L, "Alice", 30, 1L)))
        val rt = Sql.values[Person]("pv", Person(0L, "Alice", 30, 1L)).render(SqlBackend.Postgres)
        assert(r.sql.postgres == rt.sql)
    }

end SqlStaticValuesTest
