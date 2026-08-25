package kyo.internal.mysql

import kyo.*
import kyo.Sql.*
import kyo.Test
import kyo.db.Idiom

/** Verifies the SQL [[MysqlDialect]] renders for the grouping-set family: `ROLLUP`, which both flavors have and spell differently,
  * and `CUBE` / `GROUPING SETS`, which one flavor has and the other has never had at any release.
  */
class MysqlDialectRollupRenderTest extends Test:

    case class Sale(region: String, product: String, amount: Long)

    val sales = Sql.from[Sale]("s")

    given CanEqual[Any, Any] = CanEqual.derived

    // groupByRollup on MySQL emits GROUP BY a, b WITH ROLLUP.
    "GROUP BY ROLLUP lowers to GROUP BY a, b WITH ROLLUP on MySQL" in {
        val q = sales.groupByRollup(c => (c.s.region, c.s.product)).select(view => (view.region, view.product, view.amount.sum))
        val r = q.render(MysqlDialect)
        assert(r.onlySql.get.contains("GROUP BY"))
        assert(r.onlySql.get.contains("WITH ROLLUP"))
        assert(r.onlySql.get.contains("`s`.`region`"))
        assert(r.onlySql.get.contains("`s`.`product`"))
        assert(!r.onlySql.get.contains("ROLLUP ("))
    }

    // ── GROUP BY CUBE ────────────────────────────────────────────────────────

    // MySQL has no CUBE at any release. It gained `GROUP BY ... WITH ROLLUP` in 8.0 and nothing else from
    // SQL:1999's grouping-set family, so emitting `GROUP BY CUBE (...)` would be SQL the server rejects.
    "GROUP BY CUBE on MySQL raises Unsupported rather than emitting SQL MySQL cannot parse" in {
        val q = sales.groupByCube(c => (c.s.region, c.s.product)).select(view => (view.region, view.product, view.amount.sum))
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            q.render(MysqlDialect)
        }
        assert(ex.feature == "GROUP BY CUBE", s"expected feature 'GROUP BY CUBE', got: ${ex.feature}")
        assert(ex.dialect == Idiom.Id("mysql"), s"expected dialect 'mysql', got: ${ex.dialect.value}")
        assert(
            ex.requiredVersion == Maybe.Absent,
            s"no MySQL version supports CUBE, so requiredVersion must be Absent, got: ${ex.requiredVersion.map(_.show)}"
        )
    }

    // ── GROUP BY GROUPING SETS ───────────────────────────────────────────────

    "GROUP BY GROUPING SETS on MySQL raises Unsupported rather than emitting SQL MySQL cannot parse" in {
        val q = sales.groupByGroupingSets(c =>
            Chunk(
                Chunk(c.s.region),
                Chunk(c.s.product),
                Chunk.empty
            )
        ).select(view => view.s.region)
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            q.render(MysqlDialect)
        }
        assert(ex.feature == "GROUP BY GROUPING SETS", s"expected feature 'GROUP BY GROUPING SETS', got: ${ex.feature}")
        assert(ex.dialect == Idiom.Id("mysql"), s"expected dialect 'mysql', got: ${ex.dialect.value}")
        assert(ex.requiredVersion == Maybe.Absent, s"no MySQL version supports GROUPING SETS, got: ${ex.requiredVersion.map(_.show)}")
    }

end MysqlDialectRollupRenderTest
