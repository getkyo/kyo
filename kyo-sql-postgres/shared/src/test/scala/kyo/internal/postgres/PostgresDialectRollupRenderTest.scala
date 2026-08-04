package kyo.internal.postgres

import kyo.*
import kyo.Sql.*
import kyo.Test

/** Verifies the SQL [[PostgresDialect]] renders for the grouping-set family: `ROLLUP`, which both flavors have and spell differently,
  * and `CUBE` / `GROUPING SETS`, which one flavor has and the other has never had at any release.
  */
class PostgresDialectRollupRenderTest extends Test:

    case class Sale(region: String, product: String, amount: Long)

    val sales = Sql.from[Sale]("s")

    given CanEqual[Any, Any] = CanEqual.derived

    // groupByRollup on Postgres emits GROUP BY ROLLUP (a, b).
    "GROUP BY ROLLUP renders as GROUP BY ROLLUP (a, b) on PG" in {
        val q = sales.groupByRollup(c => (c.s.region, c.s.product)).select(view => (view.region, view.product, view.amount.sum))
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get.contains("""GROUP BY ROLLUP ("""))
        assert(r.onlySql.get.contains(""""s"."region""""))
        assert(r.onlySql.get.contains(""""s"."product""""))
        assert(!r.onlySql.get.contains("WITH ROLLUP"))
    }

    // ── GROUP BY CUBE ────────────────────────────────────────────────────────

    "GROUP BY CUBE renders as GROUP BY CUBE (a, b) on PG" in {
        val q = sales.groupByCube(c => (c.s.region, c.s.product)).select(view => (view.region, view.product, view.amount.sum))
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get.contains("GROUP BY CUBE ("))
        assert(r.onlySql.get.contains(""""s"."region""""))
        assert(r.onlySql.get.contains(""""s"."product""""))
    }

    "GROUP BY CUBE single column renders as GROUP BY CUBE (a) on PG" in {
        val q = sales.groupByCube(c => c.s.region).select(view => (view.region, view.amount.sum))
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get.contains("GROUP BY CUBE ("))
        assert(r.onlySql.get.contains(""""s"."region""""))
    }

    // ── GROUP BY GROUPING SETS ───────────────────────────────────────────────

    "GROUP BY GROUPING SETS with three sets renders correctly on PG" in {
        val q = sales.groupByGroupingSets(c =>
            Chunk(
                Chunk(c.s.region, c.s.product),
                Chunk(c.s.region),
                Chunk.empty
            )
        ).select(view => view.s.region)
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get.contains("GROUP BY GROUPING SETS ("))
        assert(r.onlySql.get.contains("""("s"."region", "s"."product")"""))
        assert(r.onlySql.get.contains("""("s"."region")"""))
        assert(r.onlySql.get.contains("()"))
    }

    "GROUP BY GROUPING SETS with single set renders as (a) on PG" in {
        val q = sales.groupByGroupingSets(c => Chunk(Chunk(c.s.region))).select(view => view.s.region)
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get.contains("GROUP BY GROUPING SETS ("))
        assert(r.onlySql.get.contains("""("s"."region")"""))
    }

    "GROUP BY GROUPING SETS empty-set-only emits just ()" in {
        val q = sales.groupByGroupingSets(_ => Chunk(Chunk.empty)).select(view => view.s.region)
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get.contains("GROUP BY GROUPING SETS (()"))
    }

end PostgresDialectRollupRenderTest
