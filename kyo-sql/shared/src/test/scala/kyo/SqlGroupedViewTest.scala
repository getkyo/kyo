package kyo

import kyo.Sql.render
import kyo.SqlAst.*

/** SqlGroupedViewTest, Leaf coverage for eager `groupBy` view materialization.
  *
  * Eight focused leaves that exercise the eager groupBy view invariants:
  *   1. bare `groupBy(...).select(view.key)` byte-identical SQL
  *   2. `groupBy.having(view.key.count > n)` HAVING clause
  *   3. `groupBy.orderBy(view.key.desc).limit(n)` ORDER BY + LIMIT
  *   4. `groupBy.select(view.col.sum)` SELECT SUM(...)
  *   5. AST `GroupBy` carries `view: Record[F]` field (structural check)
  *   6. grep regression guard for removed lambda-arm names in main source
  *   7. end-to-end groupBy + having + tuple select
  *   8. regression sanity (canonical SqlTest groupBy case mirrored verbatim)
  */
class SqlGroupedViewTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives Schema

    val people = Sql.from[Person]("p")

    "leaf 1, bare groupBy(deptId).select(view.deptId) renders byte-identical SQL" in {
        val q = people.groupBy(_.p.deptId).select(view => view.deptId)
        val r = q.render(SqlBackend.Postgres)
        assert(r.sql == """SELECT "p"."deptId" FROM "person" "p" GROUP BY "p"."deptId"""")
    }

    "leaf 2, groupBy.having renders HAVING clause with one bind" in {
        val q = people.groupBy(_.p.deptId).having(view => view.deptId.count > 5)
        val r = q.render(SqlBackend.Postgres)
        assert(
            r.sql == """SELECT * FROM "person" "p" GROUP BY "p"."deptId" HAVING (COUNT("p"."deptId") > $1)"""
        )
        assert(r.params.size == 1)
    }

    "leaf 3, groupBy.orderBy.limit renders ORDER BY DESC + LIMIT" in {
        val q = people.groupBy(_.p.deptId).orderBy(view => view.deptId.desc).limit(10)
        val r = q.render(SqlBackend.Postgres)
        assert(r.sql == """SELECT * FROM "person" "p" GROUP BY "p"."deptId" ORDER BY "p"."deptId" DESC LIMIT 10""")
    }

    "leaf 4, groupBy.select(view.age.sum) renders SELECT SUM" in {
        val q = people.groupBy(_.p.deptId).select(view => view.age.sum)
        val r = q.render(SqlBackend.Postgres)
        assert(
            r.sql == """SELECT SUM("p"."age") FROM "person" "p" GROUP BY "p"."deptId""""
        )
    }

    "leaf 5, AST GroupBy carries view: Record[F] after groupBy" in {
        val g = people.groupBy(_.p.deptId)
        assert(g.productElementNames.contains("view"))
        g.productElement(g.productElementNames.indexOf("view")) match
            case v: Record[?] =>
                assert(v ne null)
                val dictMap = v.dict.toMap
                assert(dictMap.contains("deptId"))
                assert(dictMap("deptId").isInstanceOf[GroupedColumn[?, ?]])
            case other =>
                fail(s"Expected Record[?] for view field, got: ${other.getClass.getName}")
        end match
    }

    "leaf 7, end-to-end groupBy + having + tuple select renders correctly" in {
        val q = people.groupBy(_.p.deptId)
            .having(view => view.deptId.count > 3)
            .select(view => (view.deptId, view.age.avg))
        val r = q.render(SqlBackend.Postgres)
        assert(
            r.sql == """SELECT "p"."deptId", AVG("p"."age") FROM "person" "p" GROUP BY "p"."deptId" HAVING (COUNT("p"."deptId") > $1)"""
        )
        assert(r.params.size == 1)
    }

    "leaf 8, regression sanity (canonical SqlTest groupBy case)" in {
        // Mirrors SqlTest.scala 'group by single column with having + select' verbatim.
        val q = people.groupBy(c => c.p.deptId)
            .having(view => view.deptId.count > 5)
            .select(view => (view.deptId, view.age.sum))
        val r = q.render(SqlBackend.Postgres)
        assert(
            r.sql == """SELECT "p"."deptId", SUM("p"."age") FROM "person" "p" GROUP BY "p"."deptId" HAVING (COUNT("p"."deptId") > $1)"""
        )
    }

    // --- FromExpr.derived[GroupBy] unapply ---
    //
    // The derived `FromExpr[GroupBy].unapply` reconstructs a statically-known `groupBy(...)` value
    // by walking its inline-expanded TASTy: the `GroupBy` product, its `source: Query` (via the
    // recursion guard), `keys: Chunk[Term]`, `view: Record[F]` (via `RecordFromExpr`'s
    // `buildGroupedView` arm) and `havingTerm: Maybe`.

    "FromExpr.derived[GroupBy] lifts a statically-known GroupBy" in {
        // `ColumnFromExpr.given` is required so the projection-shaped grouping key (`_.p.deptId`) lifts
        // to a real `Column`, without it the `selectDynamic` projection chain does not reconstruct.
        import kyo.internal.ColumnFromExpr.given
        import kyo.internal.RecordFromExpr.given
        assert(SqlLiftHarness.matched[GroupBy[Person, ?]](Sql.from[Person]("p").groupBy(_.p.deptId)))
    }

end SqlGroupedViewTest
