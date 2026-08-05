package kyo.internal.mysql

import kyo.*
import kyo.Test
import kyo.db.Idiom

/** Verifies how [[MysqlDialect]] answers for `LATERAL` at each server version it may be asked about.
  *
  * A construct a flavor gained in a specific release is rendered only when the target version has it; below that the render fails typed
  * rather than emitting SQL the server rejects.
  */
class MysqlDialectLateralGateTest extends Test:

    case class Department(id: Long, name: String)

    // MySQL default (8.4.0) emits LATERAL keyword (supportsLateral = true).
    "LATERAL on MySQL 8.4.0 (default) emits LATERAL keyword" in {
        val q = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        val r = q.render(MysqlDialect)
        assert(r.onlySql.get.contains("LATERAL"))
    }

    // MySQL 8.0.14 (the first version that supports LATERAL) emits LATERAL keyword.
    "LATERAL on MySQL 8.0.14+ emits LATERAL keyword" in {
        val q       = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        val version = Present(Idiom.ServerVersion(8, 0, 14))
        val r       = q.render(MysqlDialect, version)
        assert(r.onlySql.get.contains("LATERAL"))
    }

    // MySQL 8.0.13 (one patch before LATERAL support) raises Unsupported.
    "LATERAL on MySQL 8.0.13 raises SqlUnsupportedException" in {
        val q       = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        val version = Present(Idiom.ServerVersion(8, 0, 13))
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            q.render(MysqlDialect, version)
        }
        assert(ex.feature == "LATERAL", s"expected feature 'LATERAL', got: ${ex.feature}")
        assert(ex.dialect == Idiom.Id("mysql"), s"expected dialect 'mysql', got: ${ex.dialect.value}")
        assert(
            ex.requiredVersion == Present(Idiom.ServerVersion(8, 0, 14)),
            s"expected requiredVersion '8.0.14', got: ${ex.requiredVersion.map(_.show)}"
        )
        assert(
            ex.serverVersion == Present(Idiom.ServerVersion(8, 0, 13)),
            s"expected serverVersion '8.0.13', got: ${ex.serverVersion.map(_.show)}"
        )
    }

    // MySQL 5.7.x raises Unsupported with the server version in the typed fields.
    "LATERAL on MySQL 5.7 raises SqlUnsupportedException" in {
        val q       = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        val version = Present(Idiom.ServerVersion(5, 7, 44))
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            q.render(MysqlDialect, version)
        }
        assert(ex.feature == "LATERAL", s"expected feature 'LATERAL', got: ${ex.feature}")
        assert(ex.dialect == Idiom.Id("mysql"), s"expected dialect 'mysql', got: ${ex.dialect.value}")
        assert(
            ex.requiredVersion == Present(Idiom.ServerVersion(8, 0, 14)),
            s"expected requiredVersion '8.0.14', got: ${ex.requiredVersion.map(_.show)}"
        )
        assert(
            ex.serverVersion == Present(Idiom.ServerVersion(5, 7, 44)),
            s"expected serverVersion '5.7.44', got: ${ex.serverVersion.map(_.show)}"
        )
    }

end MysqlDialectLateralGateTest
