package kyo

import kyo.SqlAst.*

/** Runtime-render coverage for [[SqlAst.ValuesFrom]].
  *
  * Each leaf asserts the compiled SQL strings for both backends via `.renderPostgres` / `.renderMysql`. The `ValuesFrom.rows` field is
  * `Chunk[Chunk[SqlSchema.BoundValue[?]]]`, pure data that the renderer emits inline as literal VALUES rows.
  */
class SqlAstValuesRenderTest extends Test:

    case class Point(x: Int, y: Int) derives Schema
    case class Person(id: Long, name: String, age: Int, deptId: Long) derives Schema

    // single-row VALUES

    "single-row Sql.values renders VALUES keyword on both backends" in {
        val q = Sql.values[Point]("v", Point(1, 2))
        assert(q.renderPostgres.sql.contains("VALUES"))
        assert(q.renderMysql.sql.contains("VALUES"))
        assert(q.renderPostgres.params.isEmpty)
    }

    "single-row Sql.values, PG SQL contains the literal values" in {
        val q = Sql.values[Point]("v", Point(1, 2))
        assert(q.renderPostgres.sql.contains("1"))
        assert(q.renderPostgres.sql.contains("2"))
    }

    // multi-row VALUES

    "multi-row Sql.values renders VALUES keyword on both backends" in {
        val q = Sql.values[Point]("v", Point(1, 2), Point(3, 4))
        assert(q.renderPostgres.sql.contains("VALUES"))
        assert(q.renderMysql.sql.contains("VALUES"))
        assert(q.renderPostgres.params.isEmpty)
    }

    "multi-row Sql.values, PG SQL contains all four literal values" in {
        val q = Sql.values[Point]("v", Point(1, 2), Point(3, 4))
        assert(q.renderPostgres.sql.contains("(1, 2)"))
        assert(q.renderPostgres.sql.contains("(3, 4)"))
    }

    // VALUES used with a multi-column case class

    "Sql.values with a 4-column Person row renders all column names in the SELECT clause" in {
        val q = Sql.values[Person]("pv", Person(0L, "Alice", 30, 1L))
        assert(q.renderPostgres.sql.contains("VALUES"))
        assert(q.renderMysql.sql.contains("VALUES"))
        assert(q.renderPostgres.sql.contains(""""pv"."id""""))
        assert(q.renderPostgres.sql.contains(""""pv"."name""""))
        assert(q.renderPostgres.sql.contains(""""pv"."age""""))
        assert(q.renderPostgres.sql.contains(""""pv"."deptId""""))
    }

    // --- Compile-time vs runtime render parity leaves ---
    //
    // STATIC-SQL-INLINE-ONLY: `SqlStaticProbe.render` requires a fully-inline expression (`q.value` cannot
    // reduce through a `val` reference), so the same query expression is duplicated between the runtime and
    // probe calls. Keep both copies identical when editing.

    "single-row Sql.values, PG SQL matches SqlRender.render byte-for-byte" in {
        val rt = Sql.values[Point]("v", Point(1, 2)).renderPostgres
        val rs = SqlStaticProbe.render(Sql.values[Point]("v", Point(1, 2)))
        assert(rs.sql.postgres == rt.sql)
    }

    "multi-row Sql.values, MySQL SQL matches SqlRender.render byte-for-byte" in {
        val rt = Sql.values[Point]("v", Point(1, 2), Point(3, 4)).renderMysql
        val rs = SqlStaticProbe.render(Sql.values[Point]("v", Point(1, 2), Point(3, 4)))
        assert(rs.sql.mysql == rt.sql)
    }

    "Sql.values 4-column Person, PG SQL matches SqlRender.render byte-for-byte" in {
        val rt = Sql.values[Person]("pv", Person(0L, "Alice", 30, 1L)).renderPostgres
        val rs = SqlStaticProbe.render(Sql.values[Person]("pv", Person(0L, "Alice", 30, 1L)))
        assert(rs.sql.postgres == rt.sql)
    }

end SqlAstValuesRenderTest
