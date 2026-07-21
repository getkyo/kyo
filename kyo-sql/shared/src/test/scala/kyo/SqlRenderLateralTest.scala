package kyo

/** Backend gate tests for [[SqlAst.Lateral]] rendering (G-Parity-3).
  *
  * Verifies that:
  *   - Postgres always emits the `LATERAL` keyword.
  *   - MySQL 8.0.14 or newer emits the `LATERAL` keyword.
  *   - MySQL older than 8.0.14 (e.g. 5.7.x) raises [[SqlException.Unsupported]] at render time instead of emitting invalid SQL.
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
    "LATERAL on MySQL 8.0.13 raises SqlException.Unsupported" in {
        val q       = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        val backend = kyo.internal.SqlBackend.Mysql.versioned((8, 0, 13))
        val ex = intercept[SqlException.Unsupported] {
            kyo.internal.SqlRender.render(q, backend, summon[Frame])
        }
        assert(ex.getMessage.contains("LATERAL requires MySQL 8.0.14"))
    }

    // Leaf 5, MySQL 5.7.x raises Unsupported with the server version in the message.
    "LATERAL on MySQL 5.7 raises SqlException.Unsupported" in {
        val q       = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        val backend = kyo.internal.SqlBackend.Mysql.versioned((5, 7, 44))
        val ex = intercept[SqlException.Unsupported] {
            kyo.internal.SqlRender.render(q, backend, summon[Frame])
        }
        assert(ex.getMessage.contains("LATERAL requires MySQL 8.0.14"))
        assert(ex.getMessage.contains("5.7.44"))
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
