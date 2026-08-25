package kyo.internal.postgres

import kyo.*
import kyo.Sql.*
import kyo.Test

/** Verifies the SQL [[PostgresDialect]] renders for a query used as a source of another, including how the inner query's columns are
  * re-aliased on the way out.
  *
  * Each scenario pins the whole statement, because the re-aliasing is the property under test: the outer projection has to name the
  * subquery's alias while the inner projection keeps the base table's, and a check for a paren or a keyword sees neither.
  */
class PostgresDialectNestedRenderTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long)
    case class Order(id: Long, userId: Long)

    // SELECT FROM (simple subquery)

    "simple Nested subquery emits subquery parens on PG" in {
        val q = Sql.nested[Person]("sub", Sql.from[Person]("p"))
        assert(
            q.render(PostgresDialect).onlySql.get ==
                """SELECT "sub"."id", "sub"."name", "sub"."age", "sub"."deptId" FROM (SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p") "sub""""
        )
        assert(q.render(PostgresDialect).params.isEmpty)
    }

    "simple Nested, PG SELECT enumerates Person columns under alias sub" in {
        val q = Sql.nested[Person]("sub", Sql.from[Person]("p"))
        // Outer columns carry the subquery alias, inner columns carry the table alias.
        assert(
            q.render(PostgresDialect).onlySql.get ==
                """SELECT "sub"."id", "sub"."name", "sub"."age", "sub"."deptId" FROM (SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p") "sub""""
        )
    }

    // Nested wrapping a WHERE subquery with bind param

    "Nested with inner WHERE threads the bind param on PG" in {
        val q  = Sql.nested[Person]("sub", Sql.from[Person]("p").where(c => c.p.age >= 18))
        val rp = q.render(PostgresDialect)
        assert(
            rp.onlySql.get ==
                """SELECT "sub"."id", "sub"."name", "sub"."age", "sub"."deptId" FROM (SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."age" >= $1)) "sub""""
        )
        assert(rp.params.size == 1)
        val bv: Sql.BoundValue[?] = rp.params.head
        given CanEqual[Any, Any]  = CanEqual.derived
        assert((bv.value: Any) == 18)
        assert(bv.schema eq SqlSchema.int)
    }

    // Nested in a JOIN position

    "Nested used as right-hand side of an INNER JOIN renders correctly" in {
        val q = Sql.from[Person]("p")
            .innerJoin(Sql.nested[Order]("sub", Sql.from[Order]("o")))
            .on(j => j.p.id == j.sub.userId)
            .select(j => (j.p.name, j.sub.id))
        val rp = q.render(PostgresDialect)
        assert(
            rp.onlySql.get ==
                """SELECT "p"."name", "sub"."id" FROM "person" "p" INNER JOIN (SELECT "o"."id", "o"."userId" FROM "order" "o") "sub" ON ("p"."id" = "sub"."userId")"""
        )
        assert(rp.params.isEmpty)
    }

    // --- Compile-time vs runtime render parity leaves ---
    //
    // STATIC-SQL-INLINE-ONLY: `SqlStaticProbe.render` requires a fully-inline expression (`q.value` cannot
    // reduce through a `val` reference), so the same query expression is duplicated between the runtime and
    // probe calls. Keep both copies identical when editing.

    "simple Nested, PG SQL matches the runtime render byte-for-byte" in {
        val rt = Sql.nested[Person]("sub", Sql.from[Person]("p")).render(PostgresDialect)
        val rs = SqlStaticProbe.render(Sql.nested[Person]("sub", Sql.from[Person]("p")))
        assert(rs.sqlFor(PostgresDialect.id).get == rt.onlySql.get)
    }

    "Nested with inner WHERE, PG SQL matches the runtime render byte-for-byte" in {
        val rt = Sql.nested[Person]("sub", Sql.from[Person]("p").where(c => c.p.age >= 18)).render(PostgresDialect)
        val rs = SqlStaticProbe.render(Sql.nested[Person]("sub", Sql.from[Person]("p").where(c => c.p.age >= 18)))
        assert(rs.sqlFor(PostgresDialect.id).get == rt.onlySql.get)
    }

    "Nested in JOIN, PG SQL matches the runtime render byte-for-byte" in {
        val rt = Sql.from[Person]("p")
            .innerJoin(Sql.nested[Order]("sub", Sql.from[Order]("o")))
            .on(j => j.p.id == j.sub.userId)
            .select(j => (j.p.name, j.sub.id))
            .render(PostgresDialect)
        val rs = SqlStaticProbe.render(
            Sql.from[Person]("p")
                .innerJoin(Sql.nested[Order]("sub", Sql.from[Order]("o")))
                .on(j => j.p.id == j.sub.userId)
                .select(j => (j.p.name, j.sub.id))
        )
        assert(rs.sqlFor(PostgresDialect.id).get == rt.onlySql.get)
    }

end PostgresDialectNestedRenderTest
