package kyo

import kyo.SqlAst.*

/** SqlRenderGroupByRollupTest, Backend rendering of GROUP BY ROLLUP (G-Parity-18).
  *
  * Two leaves verifying that `groupByRollup` emits the correct backend-specific SQL:
  *   1. Postgres: `GROUP BY ROLLUP (a, b)` wrapped syntax.
  *   2. MySQL: `GROUP BY a, b WITH ROLLUP` trailing modifier syntax.
  */
class SqlRenderGroupByRollupTest extends Test:

    case class Sale(region: String, product: String, amount: Long) derives Schema

    val sales = Sql.from[Sale]("s")

    given CanEqual[Any, Any] = CanEqual.derived

    // Leaf 1: groupByRollup on Postgres, emits GROUP BY ROLLUP (a, b).
    "GROUP BY ROLLUP renders as GROUP BY ROLLUP (a, b) on PG" in {
        val q = sales.groupByRollup(c => (c.s.region, c.s.product))
        val r = q.renderPostgres
        assert(r.sql.contains("""GROUP BY ROLLUP ("""))
        assert(r.sql.contains(""""s"."region""""))
        assert(r.sql.contains(""""s"."product""""))
        assert(!r.sql.contains("WITH ROLLUP"))
    }

    // Leaf 2: groupByRollup on MySQL, emits GROUP BY a, b WITH ROLLUP.
    "GROUP BY ROLLUP lowers to GROUP BY a, b WITH ROLLUP on MySQL" in {
        val q = sales.groupByRollup(c => (c.s.region, c.s.product))
        val r = q.renderMysql
        assert(r.sql.contains("GROUP BY"))
        assert(r.sql.contains("WITH ROLLUP"))
        assert(r.sql.contains("`s`.`region`"))
        assert(r.sql.contains("`s`.`product`"))
        assert(!r.sql.contains("ROLLUP ("))
    }

    // ── #508: GROUP BY CUBE ──────────────────────────────────────────────────

    "GROUP BY CUBE renders as GROUP BY CUBE (a, b) on PG" in {
        val q = sales.groupByCube(c => (c.s.region, c.s.product))
        val r = q.renderPostgres
        assert(r.sql.contains("GROUP BY CUBE ("))
        assert(r.sql.contains(""""s"."region""""))
        assert(r.sql.contains(""""s"."product""""))
    }

    "GROUP BY CUBE single column renders as GROUP BY CUBE (a) on PG" in {
        val q = sales.groupByCube(c => c.s.region)
        val r = q.renderPostgres
        assert(r.sql.contains("GROUP BY CUBE ("))
        assert(r.sql.contains(""""s"."region""""))
    }

    "GROUP BY CUBE renders as GROUP BY CUBE (a, b) on MySQL (8.0+)" in {
        val q = sales.groupByCube(c => (c.s.region, c.s.product))
        val r = q.renderMysql
        assert(r.sql.contains("GROUP BY CUBE ("))
        assert(r.sql.contains("`s`.`region`"))
        assert(r.sql.contains("`s`.`product`"))
    }

    // ── #508: GROUP BY GROUPING SETS ─────────────────────────────────────────

    "GROUP BY GROUPING SETS with three sets renders correctly on PG" in {
        val q = sales.groupByGroupingSets(c =>
            Seq(
                Seq(c.s.region, c.s.product),
                Seq(c.s.region),
                Seq()
            )
        )
        val r = q.renderPostgres
        assert(r.sql.contains("GROUP BY GROUPING SETS ("))
        assert(r.sql.contains("""("s"."region", "s"."product")"""))
        assert(r.sql.contains("""("s"."region")"""))
        assert(r.sql.contains("()"))
    }

    "GROUP BY GROUPING SETS with single set renders as (a) on PG" in {
        val q = sales.groupByGroupingSets(c => Seq(Seq(c.s.region)))
        val r = q.renderPostgres
        assert(r.sql.contains("GROUP BY GROUPING SETS ("))
        assert(r.sql.contains("""("s"."region")"""))
    }

    "GROUP BY GROUPING SETS renders identically on MySQL (8.0+)" in {
        val q = sales.groupByGroupingSets(c =>
            Seq(
                Seq(c.s.region),
                Seq(c.s.product),
                Seq()
            )
        )
        val r = q.renderMysql
        assert(r.sql.contains("GROUP BY GROUPING SETS ("))
        assert(r.sql.contains("(`s`.`region`)"))
        assert(r.sql.contains("(`s`.`product`)"))
        assert(r.sql.contains("()"))
    }

    "GROUP BY GROUPING SETS empty-set-only emits just ()" in {
        val q = sales.groupByGroupingSets(_ => Seq(Seq()))
        val r = q.renderPostgres
        assert(r.sql.contains("GROUP BY GROUPING SETS (()"))
    }

end SqlRenderGroupByRollupTest
