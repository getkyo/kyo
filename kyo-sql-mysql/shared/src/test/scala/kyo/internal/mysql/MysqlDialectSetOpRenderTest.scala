package kyo.internal.mysql

import kyo.*
import kyo.Test
import kyo.db.Idiom

/** Verifies how [[MysqlDialect]] answers for the `INTERSECT` and `EXCEPT` set operators at each server version it may be asked about,
  * and that `UNION`, which no version gates, is unaffected.
  */
class MysqlDialectSetOpRenderTest extends Test:

    case class Item(id: Long, name: String)

    private val left  = Sql.from[Item]("a")
    private val right = Sql.from[Item]("b")

    // INTERSECT renders on MySQL 8.0.31+.
    "INTERSECT renders on MySQL 8.0.31+" in {
        val q       = left.intersect(right)
        val version = Present(Idiom.ServerVersion(8, 0, 31))
        val r       = q.render(MysqlDialect, version)
        assert(r.onlySql.get.contains("INTERSECT"))
    }

    // EXCEPT renders on MySQL 8.0.31+.
    "EXCEPT renders on MySQL 8.0.31+" in {
        val q       = left.except(right)
        val version = Present(Idiom.ServerVersion(8, 0, 31))
        val r       = q.render(MysqlDialect, version)
        assert(r.onlySql.get.contains("EXCEPT"))
    }

    // INTERSECT on MySQL 8.0.30 raises Unsupported (one patch before support).
    "INTERSECT on MySQL 8.0.30 raises Unsupported" in {
        val q       = left.intersect(right)
        val version = Present(Idiom.ServerVersion(8, 0, 30))
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            q.render(MysqlDialect, version)
        }
        assert(ex.feature == "INTERSECT / EXCEPT", s"expected feature 'INTERSECT / EXCEPT', got: ${ex.feature}")
        assert(ex.dialect == Idiom.Id("mysql"), s"expected dialect 'mysql', got: ${ex.dialect.value}")
        assert(
            ex.requiredVersion == Present(Idiom.ServerVersion(8, 0, 31)),
            s"expected requiredVersion '8.0.31', got: ${ex.requiredVersion.map(_.show)}"
        )
        assert(
            ex.serverVersion == Present(Idiom.ServerVersion(8, 0, 30)),
            s"expected serverVersion '8.0.30', got: ${ex.serverVersion.map(_.show)}"
        )
    }

    // EXCEPT on MySQL 5.7 raises Unsupported with the server version in the typed fields.
    "EXCEPT on MySQL 5.7 raises Unsupported" in {
        val q       = left.except(right)
        val version = Present(Idiom.ServerVersion(5, 7, 44))
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            q.render(MysqlDialect, version)
        }
        assert(ex.feature == "INTERSECT / EXCEPT", s"expected feature 'INTERSECT / EXCEPT', got: ${ex.feature}")
        assert(ex.dialect == Idiom.Id("mysql"), s"expected dialect 'mysql', got: ${ex.dialect.value}")
        assert(
            ex.requiredVersion == Present(Idiom.ServerVersion(8, 0, 31)),
            s"expected requiredVersion '8.0.31', got: ${ex.requiredVersion.map(_.show)}"
        )
        assert(
            ex.serverVersion == Present(Idiom.ServerVersion(5, 7, 44)),
            s"expected serverVersion '5.7.44', got: ${ex.serverVersion.map(_.show)}"
        )
    }

    // INTERSECT ALL on MySQL 5.7 raises Unsupported.
    "INTERSECT ALL on MySQL 5.7 raises Unsupported" in {
        val q       = left.intersectAll(right)
        val version = Present(Idiom.ServerVersion(5, 7, 44))
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            q.render(MysqlDialect, version)
        }
        assert(ex.feature == "INTERSECT / EXCEPT", s"expected feature 'INTERSECT / EXCEPT', got: ${ex.feature}")
        assert(ex.dialect == Idiom.Id("mysql"), s"expected dialect 'mysql', got: ${ex.dialect.value}")
        assert(
            ex.requiredVersion == Present(Idiom.ServerVersion(8, 0, 31)),
            s"expected requiredVersion '8.0.31', got: ${ex.requiredVersion.map(_.show)}"
        )
    }

    // EXCEPT ALL on MySQL 8.0.30 raises Unsupported.
    "EXCEPT ALL on MySQL 8.0.30 raises Unsupported" in {
        val q       = left.exceptAll(right)
        val version = Present(Idiom.ServerVersion(8, 0, 30))
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            q.render(MysqlDialect, version)
        }
        assert(ex.feature == "INTERSECT / EXCEPT", s"expected feature 'INTERSECT / EXCEPT', got: ${ex.feature}")
        assert(ex.dialect == Idiom.Id("mysql"), s"expected dialect 'mysql', got: ${ex.dialect.value}")
        assert(
            ex.requiredVersion == Present(Idiom.ServerVersion(8, 0, 31)),
            s"expected requiredVersion '8.0.31', got: ${ex.requiredVersion.map(_.show)}"
        )
    }

    // UNION is unaffected by the gate on MySQL 5.7 (always supported).
    "UNION on MySQL 5.7 does not raise Unsupported" in {
        val q       = left.union(right)
        val version = Present(Idiom.ServerVersion(5, 7, 44))
        val r       = q.render(MysqlDialect, version)
        assert(r.onlySql.get.contains("UNION"))
        assert(!r.onlySql.get.contains("INTERSECT"))
        assert(!r.onlySql.get.contains("EXCEPT"))
    }

    // A render naming no version targets the dialect's capability floor, 8.0.31, which is exactly the
    // release that introduced the gate, so the INTERSECT keyword is emitted.
    "INTERSECT on MySQL at the capability floor emits the INTERSECT keyword" in {
        val q = left.intersect(right)
        val r = q.render(MysqlDialect)
        assert(r.onlySql.get.contains("INTERSECT"))
    }

end MysqlDialectSetOpRenderTest
