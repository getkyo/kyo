package kyo.internal.postgres

import kyo.*
import kyo.Sql.*
import kyo.Test

/** Verifies the SQL [[PostgresDialect]] renders for the eager `groupBy` view: the grouped key in a projection, a `HAVING` predicate
  * over an aggregate, an ordering and limit over the grouped result, and an aggregate projection.
  */
class PostgresDialectGroupedViewRenderTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long)

    val people = Sql.from[Person]("p")

    "leaf 1, bare groupBy(deptId).select(view.deptId) renders byte-identical SQL" in {
        val q = people.groupBy(_.p.deptId).select(view => view.deptId)
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get == """SELECT "p"."deptId" FROM "person" "p" GROUP BY "p"."deptId"""")
    }

    "leaf 2, groupBy.having.select renders HAVING clause with one bind" in {
        val q = people.groupBy(_.p.deptId).having(view => view.deptId.count > 5).select(view => view.deptId)
        val r = q.render(PostgresDialect)
        assert(
            r.onlySql.get == """SELECT "p"."deptId" FROM "person" "p" GROUP BY "p"."deptId" HAVING (COUNT("p"."deptId") > $1)"""
        )
        assert(r.params.size == 1)
    }

    "leaf 3, groupBy.select.orderBy.limit renders ORDER BY DESC + LIMIT" in {
        val q = people.groupBy(_.p.deptId).select(view => view.deptId).orderBy(view => view.deptId.desc).limit(10)
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get == """SELECT "p"."deptId" FROM "person" "p" GROUP BY "p"."deptId" ORDER BY "p"."deptId" DESC LIMIT 10""")
    }

    "leaf 4, groupBy.select(view.age.sum) renders SELECT SUM" in {
        val q = people.groupBy(_.p.deptId).select(view => view.age.sum)
        val r = q.render(PostgresDialect)
        assert(
            r.onlySql.get == """SELECT SUM("p"."age") FROM "person" "p" GROUP BY "p"."deptId""""
        )
    }

    "leaf 7, end-to-end groupBy + having + tuple select renders correctly" in {
        val q = people.groupBy(_.p.deptId)
            .having(view => view.deptId.count > 3)
            .select(view => (view.deptId, view.age.avg))
        val r = q.render(PostgresDialect)
        assert(
            r.onlySql.get == """SELECT "p"."deptId", AVG("p"."age") FROM "person" "p" GROUP BY "p"."deptId" HAVING (COUNT("p"."deptId") > $1)"""
        )
        assert(r.params.size == 1)
    }

    "leaf 8, regression sanity (canonical SqlTest groupBy case)" in {
        // Mirrors SqlTest.scala 'group by single column with having + select' verbatim.
        val q = people.groupBy(c => c.p.deptId)
            .having(view => view.deptId.count > 5)
            .select(view => (view.deptId, view.age.sum))
        val r = q.render(PostgresDialect)
        assert(
            r.onlySql.get == """SELECT "p"."deptId", SUM("p"."age") FROM "person" "p" GROUP BY "p"."deptId" HAVING (COUNT("p"."deptId") > $1)"""
        )
    }

end PostgresDialectGroupedViewRenderTest
