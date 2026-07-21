package kyo

import kyo.SqlAst.*

/** Runtime-render coverage for [[SqlAst.Nested]].
  *
  * Each leaf asserts the compiled SQL strings for both backends via `.renderPostgres` / `.renderMysql`.
  */
class SqlAstNestedRenderTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives Schema
    case class Department(id: Long, name: String) derives Schema
    case class Order(id: Long, userId: Long) derives Schema

    // SELECT FROM (simple subquery)

    "simple Nested subquery emits subquery parens on both backends" in {
        val q = Sql.nested[Person]("sub", Sql.from[Person]("p"))
        assert(q.renderPostgres.sql.contains("("))
        assert(q.renderPostgres.sql.contains(")"))
        assert(q.renderMysql.sql.contains("("))
        assert(q.renderMysql.sql.contains(")"))
        assert(q.renderPostgres.params.isEmpty)
    }

    "simple Nested, PG SELECT enumerates Person columns under alias sub" in {
        val q = Sql.nested[Person]("sub", Sql.from[Person]("p"))
        assert(q.renderPostgres.sql.contains(""""sub"."id""""))
        assert(q.renderPostgres.sql.contains(""""sub"."name""""))
        assert(q.renderPostgres.sql.contains(""""sub"."age""""))
        assert(q.renderPostgres.sql.contains(""""sub"."deptId""""))
    }

    // Nested wrapping a WHERE subquery with bind param

    "Nested with inner WHERE threads the bind param through both backends" in {
        val q  = Sql.nested[Person]("sub", Sql.from[Person]("p").where(c => c.p.age >= 18))
        val rp = q.renderPostgres
        val rm = q.renderMysql
        assert(rp.sql.contains("$1"))
        assert(rm.sql.contains("?"))
        assert(rp.params.size == 1)
        val bv: SqlSchema.BoundValue[?] = rp.params.head
        given CanEqual[Any, Any]        = CanEqual.derived
        assert((bv.value: Any) == 18)
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[Int]]); assert(ok) }
    }

    // Nested in a JOIN position

    "Nested used as right-hand side of an INNER JOIN renders correctly" in {
        val q = Sql.from[Person]("p")
            .innerJoin(Sql.nested[Order]("sub", Sql.from[Order]("o")))
            .on(j => j.p.id == j.sub.userId)
            .select(j => (j.p.name, j.sub.id))
        val rp = q.renderPostgres
        val rm = q.renderMysql
        assert(rp.sql.contains("INNER JOIN"))
        assert(rp.sql.contains("""("p"."id" = "sub"."userId")"""))
        assert(rm.sql.contains("INNER JOIN"))
        assert(rm.sql.contains("(`p`.`id` = `sub`.`userId`)"))
        assert(rp.params.isEmpty)
    }

    // --- Compile-time vs runtime render parity leaves ---
    //
    // STATIC-SQL-INLINE-ONLY: `SqlStaticProbe.render` requires a fully-inline expression (`q.value` cannot
    // reduce through a `val` reference), so the same query expression is duplicated between the runtime and
    // probe calls. Keep both copies identical when editing.

    "simple Nested, PG SQL matches SqlRender.render byte-for-byte" in {
        val rt = Sql.nested[Person]("sub", Sql.from[Person]("p")).renderPostgres
        val rs = SqlStaticProbe.render(Sql.nested[Person]("sub", Sql.from[Person]("p")))
        assert(rs.sql.postgres == rt.sql)
    }

    "Nested with inner WHERE, PG SQL matches SqlRender.render byte-for-byte" in {
        val rt = Sql.nested[Person]("sub", Sql.from[Person]("p").where(c => c.p.age >= 18)).renderPostgres
        val rs = SqlStaticProbe.render(Sql.nested[Person]("sub", Sql.from[Person]("p").where(c => c.p.age >= 18)))
        assert(rs.sql.postgres == rt.sql)
    }

    "Nested in JOIN, PG SQL matches SqlRender.render byte-for-byte" in {
        val rt = Sql.from[Person]("p")
            .innerJoin(Sql.nested[Order]("sub", Sql.from[Order]("o")))
            .on(j => j.p.id == j.sub.userId)
            .select(j => (j.p.name, j.sub.id))
            .renderPostgres
        val rs = SqlStaticProbe.render(
            Sql.from[Person]("p")
                .innerJoin(Sql.nested[Order]("sub", Sql.from[Order]("o")))
                .on(j => j.p.id == j.sub.userId)
                .select(j => (j.p.name, j.sub.id))
        )
        assert(rs.sql.postgres == rt.sql)
    }

end SqlAstNestedRenderTest
