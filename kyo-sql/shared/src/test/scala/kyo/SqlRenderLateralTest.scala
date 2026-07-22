package kyo

/** Backend gate tests for [[SqlAst.Lateral]] rendering (G-Parity-3).
  *
  * Verifies that:
  *   - Postgres always emits the `LATERAL` keyword.
  *   - MySQL 8.0.14 or newer emits the `LATERAL` keyword.
  *   - MySQL older than 8.0.14 (e.g. 5.7.x) raises [[SqlUnsupportedException]] at render time instead of emitting invalid SQL.
  */
class SqlRenderLateralTest extends Test:

    case class Department(id: Long, name: String) derives Schema

    // Leaf 1, Postgres always emits LATERAL (no version gate).
    "LATERAL on Postgres emits LATERAL keyword" in {
        val q = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        val r = q.renderPostgres
        assert(r.sql.contains("LATERAL"))
    }

    // Leaf 2, MySQL default (8.4.0) emits LATERAL keyword (supportsLateral = true).
    "LATERAL on MySQL 8.4.0 (default) emits LATERAL keyword" in {
        val q = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        val r = q.renderMysql
        assert(r.sql.contains("LATERAL"))
    }

    // Leaf 3, MySQL 8.0.14 (the first version that supports LATERAL) emits LATERAL keyword.
    "LATERAL on MySQL 8.0.14+ emits LATERAL keyword" in {
        val q       = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        val backend = kyo.internal.SqlBackend.Mysql.versioned((8, 0, 14))
        val r       = kyo.internal.SqlRender.render(q, backend, summon[Frame])
        assert(r.sql.contains("LATERAL"))
    }

    // Leaf 4, MySQL 8.0.13 (one patch before LATERAL support) raises Unsupported.
    "LATERAL on MySQL 8.0.13 raises SqlUnsupportedException" in {
        val q       = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        val backend = kyo.internal.SqlBackend.Mysql.versioned((8, 0, 13))
        val ex = intercept[SqlUnsupportedMysqlVersionFeatureException] {
            kyo.internal.SqlRender.render(q, backend, summon[Frame])
        }
        assert(ex.feature == "LATERAL", s"expected feature 'LATERAL', got: ${ex.feature}")
        assert(ex.requiredVersion == "8.0.14", s"expected requiredVersion '8.0.14', got: ${ex.requiredVersion}")
        assert(ex.serverVersion == "8.0.13", s"expected serverVersion '8.0.13', got: ${ex.serverVersion}")
    }

    // Leaf 5, MySQL 5.7.x raises Unsupported with the server version in the typed fields.
    "LATERAL on MySQL 5.7 raises SqlUnsupportedException" in {
        val q       = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        val backend = kyo.internal.SqlBackend.Mysql.versioned((5, 7, 44))
        val ex = intercept[SqlUnsupportedMysqlVersionFeatureException] {
            kyo.internal.SqlRender.render(q, backend, summon[Frame])
        }
        assert(ex.feature == "LATERAL", s"expected feature 'LATERAL', got: ${ex.feature}")
        assert(ex.requiredVersion == "8.0.14", s"expected requiredVersion '8.0.14', got: ${ex.requiredVersion}")
        assert(ex.serverVersion == "5.7.44", s"expected serverVersion '5.7.44', got: ${ex.serverVersion}")
    }

    // Leaf 6, supportsLateral boundary: exactly (8, 0, 14) returns true.
    "kyo.internal.SqlBackend.Mysql.versioned(8, 0, 14).supportsLateral is true" in {
        val backend = kyo.internal.SqlBackend.Mysql.versioned((8, 0, 14))
        assert(backend.supportsLateral)
    }

    // Leaf 7, supportsLateral boundary: (8, 0, 13) returns false.
    "kyo.internal.SqlBackend.Mysql.versioned(8, 0, 13).supportsLateral is false" in {
        val backend = kyo.internal.SqlBackend.Mysql.versioned((8, 0, 13))
        assert(!backend.supportsLateral)
    }

    // Leaf 8, supportsLateral: the default Mysql singleton (8.4.0) returns true.
    "kyo.internal.SqlBackend.Mysql.supportsLateral is true for the default singleton" in {
        assert(kyo.internal.SqlBackend.Mysql.supportsLateral)
    }

end SqlRenderLateralTest
