package kyo

import kyo.SqlAst.*

/** Runtime-render coverage for [[SqlAst.Lateral]].
  *
  * Each leaf asserts the compiled SQL strings for both backends via `.renderPostgres` / `.renderMysql`.
  */
class SqlAstLateralRenderTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives Schema
    case class Department(id: Long, name: String) derives Schema

    // simple LATERAL subquery (Sql.lateral entry point)

    "simple Lateral subquery emits LATERAL keyword on both backends" in {
        val q = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        assert(q.renderPostgres.sql.contains("LATERAL"))
        assert(q.renderMysql.sql.contains("LATERAL"))
        assert(q.renderPostgres.params.isEmpty)
    }

    "simple Lateral, PG SELECT lists Department column names" in {
        val q = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        assert(q.renderPostgres.sql.contains(""""d"."id""""))
        assert(q.renderPostgres.sql.contains(""""d"."name""""))
    }

    "simple Lateral, MySQL SELECT lists Department column names" in {
        val q = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        assert(q.renderMysql.sql.contains("`d`.`id`"))
        assert(q.renderMysql.sql.contains("`d`.`name`"))
    }

    // correlated LATERAL (inner query has a WHERE bound param)

    "correlated Lateral with WHERE bind param threads the bind through both backends" in {
        val q  = Sql.lateral[Person]("lat", Sql.from[Person]("p").where(c => c.p.age >= 18))
        val rp = q.renderPostgres
        val rm = q.renderMysql
        assert(rp.sql.contains("LATERAL"))
        assert(rp.sql.contains("$1"))
        assert(rm.sql.contains("LATERAL"))
        assert(rm.sql.contains("?"))
        assert(rp.params.size == 1)
        val bv: SqlSchema.BoundValue[?] = rp.params.head
        given CanEqual[Any, Any]        = CanEqual.derived
        assert((bv.value: Any) == 18)
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[Int]]); assert(ok) }
    }

    // LATERAL with aggregate inner query

    "Lateral wrapping a GroupBy + aggregate inner query renders on both backends" in {
        val q = Sql.lateral[Person](
            "agg",
            Sql.from[Person]("p")
                .where(c => c.p.deptId == 1L)
                .groupBy(c => c.p.deptId)
                .select(view => view.deptId.count)
        )
        assert(q.renderPostgres.sql.contains("LATERAL"))
        assert(q.renderPostgres.sql.contains("GROUP BY"))
        assert(q.renderMysql.sql.contains("LATERAL"))
        assert(q.renderMysql.sql.contains("GROUP BY"))
    }

    // --- Compile-time vs runtime render parity leaves ---
    //
    // STATIC-SQL-INLINE-ONLY: `SqlStaticProbe.render` requires a fully-inline expression (`q.value` cannot
    // reduce through a `val` reference), so the same query expression is duplicated between the runtime and
    // probe calls. Keep both copies identical when editing.

    "simple Lateral, PG SQL matches SqlRender.render byte-for-byte" in {
        val rt = Sql.lateral[Department]("d", Sql.from[Department]("dept")).renderPostgres
        val rs = SqlStaticProbe.render(Sql.lateral[Department]("d", Sql.from[Department]("dept")))
        assert(rs.sql.postgres == rt.sql)
    }

    "correlated Lateral, PG SQL matches SqlRender.render byte-for-byte" in {
        val rt = Sql.lateral[Person]("lat", Sql.from[Person]("p").where(c => c.p.age >= 18)).renderPostgres
        val rs = SqlStaticProbe.render(Sql.lateral[Person]("lat", Sql.from[Person]("p").where(c => c.p.age >= 18)))
        assert(rs.sql.postgres == rt.sql)
    }

end SqlAstLateralRenderTest
