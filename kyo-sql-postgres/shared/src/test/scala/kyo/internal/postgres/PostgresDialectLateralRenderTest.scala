package kyo.internal.postgres

import kyo.*
import kyo.Sql.*
import kyo.Test

/** Verifies the SQL [[PostgresDialect]] renders for a `LATERAL` subquery, whose predicate may reference columns of the surrounding
  * `FROM`.
  *
  * The statement is pinned whole rather than by keyword, because what a `LATERAL` source has to get right is the nesting: the outer
  * projection names the lateral alias, the inner query keeps its own alias, and the closing paren carries the alias after it.
  */
class PostgresDialectLateralRenderTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long)
    case class Department(id: Long, name: String)

    // simple LATERAL subquery (Sql.lateral entry point)

    "simple Lateral subquery emits LATERAL keyword on PG" in {
        val q = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        assert(
            q.render(PostgresDialect).onlySql.get ==
                """SELECT "d"."id", "d"."name" FROM LATERAL (SELECT "dept"."id", "dept"."name" FROM "department" "dept") "d""""
        )
        assert(q.render(PostgresDialect).params.isEmpty)
    }

    "simple Lateral, PG SELECT lists Department column names" in {
        val q = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        // The outer projection is the lateral alias, not the inner table alias.
        assert(
            q.render(PostgresDialect).onlySql.get ==
                """SELECT "d"."id", "d"."name" FROM LATERAL (SELECT "dept"."id", "dept"."name" FROM "department" "dept") "d""""
        )
    }

    // correlated LATERAL (inner query has a WHERE bound param)

    "correlated Lateral with WHERE bind param threads the bind on PG" in {
        val q  = Sql.lateral[Person]("lat", Sql.from[Person]("p").where(c => c.p.age >= 18))
        val rp = q.render(PostgresDialect)
        assert(
            rp.onlySql.get ==
                """SELECT "lat"."id", "lat"."name", "lat"."age", "lat"."deptId" FROM LATERAL (SELECT "p"."id", "p"."name", "p"."age", "p"."deptId" FROM "person" "p" WHERE ("p"."age" >= $1)) "lat""""
        )
        assert(rp.params.size == 1)
        val bv: Sql.BoundValue[?] = rp.params.head
        given CanEqual[Any, Any]  = CanEqual.derived
        assert((bv.value: Any) == 18)
        assert(bv.schema eq SqlSchema.int)
    }

    // LATERAL with aggregate inner query

    "Lateral wrapping a GroupBy + aggregate inner query renders correctly on PG" in {
        val q = Sql.lateral[Person](
            "agg",
            Sql.from[Person]("p")
                .where(c => c.p.deptId == 1L)
                .groupBy(c => c.p.deptId)
                .select(view => view.deptId.count)
        )
        val rp = q.render(PostgresDialect)
        assert(
            rp.onlySql.get ==
                """SELECT "agg"."id", "agg"."name", "agg"."age", "agg"."deptId" FROM LATERAL (SELECT COUNT("p"."deptId") FROM "person" "p" WHERE ("p"."deptId" = $1) GROUP BY "p"."deptId") "agg""""
        )
        assert(rp.params.size == 1)
    }

    // --- Compile-time vs runtime render parity leaves ---
    //
    // STATIC-SQL-INLINE-ONLY: `SqlStaticProbe.render` requires a fully-inline expression (`q.value` cannot
    // reduce through a `val` reference), so the same query expression is duplicated between the runtime and
    // probe calls. Keep both copies identical when editing.

    "simple Lateral, PG SQL matches the runtime render byte-for-byte" in {
        val rt = Sql.lateral[Department]("d", Sql.from[Department]("dept")).render(PostgresDialect)
        val rs = SqlStaticProbe.render(Sql.lateral[Department]("d", Sql.from[Department]("dept")))
        assert(rs.sqlFor(PostgresDialect.id).get == rt.onlySql.get)
    }

    "correlated Lateral, PG SQL matches the runtime render byte-for-byte" in {
        val rt = Sql.lateral[Person]("lat", Sql.from[Person]("p").where(c => c.p.age >= 18)).render(PostgresDialect)
        val rs = SqlStaticProbe.render(Sql.lateral[Person]("lat", Sql.from[Person]("p").where(c => c.p.age >= 18)))
        assert(rs.sqlFor(PostgresDialect.id).get == rt.onlySql.get)
    }

end PostgresDialectLateralRenderTest
