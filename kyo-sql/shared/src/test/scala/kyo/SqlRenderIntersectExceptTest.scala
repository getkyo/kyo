package kyo

/** Backend gate tests for [[SqlAst.SetOp]] INTERSECT/EXCEPT rendering (G-Parity-5).
  *
  * Verifies that:
  *   - Postgres always emits `INTERSECT` and `EXCEPT` keywords.
  *   - MySQL 8.0.31 or newer emits `INTERSECT` and `EXCEPT` keywords.
  *   - MySQL older than 8.0.31 (e.g. 8.0.30, 5.7.x) raises [[SqlUnsupportedException]] at render time instead of emitting invalid SQL.
  *   - `UNION` / `UNION ALL` are unaffected by the gate on all backends.
  */
class SqlRenderIntersectExceptTest extends Test:

    case class Item(id: Long, name: String) derives Schema

    private val left  = Sql.from[Item]("a")
    private val right = Sql.from[Item]("b")

    // Leaf 1, INTERSECT renders on PG (no version gate).
    "INTERSECT renders on PG" in {
        val q = left.intersect(right)
        val r = q.renderPostgres
        assert(r.sql.contains("INTERSECT"))
        assert(!r.sql.contains("INTERSECT ALL"))
    }

    // Leaf 2, EXCEPT renders on PG (no version gate).
    "EXCEPT renders on PG" in {
        val q = left.except(right)
        val r = q.renderPostgres
        assert(r.sql.contains("EXCEPT"))
        assert(!r.sql.contains("EXCEPT ALL"))
    }

    // Leaf 3, INTERSECT renders on MySQL 8.0.31+.
    "INTERSECT renders on MySQL 8.0.31+" in {
        val q       = left.intersect(right)
        val backend = kyo.internal.SqlBackend.Mysql.versioned((8, 0, 31))
        val r       = kyo.internal.SqlRender.render(q, backend, summon[Frame])
        assert(r.sql.contains("INTERSECT"))
    }

    // Leaf 4, EXCEPT renders on MySQL 8.0.31+.
    "EXCEPT renders on MySQL 8.0.31+" in {
        val q       = left.except(right)
        val backend = kyo.internal.SqlBackend.Mysql.versioned((8, 0, 31))
        val r       = kyo.internal.SqlRender.render(q, backend, summon[Frame])
        assert(r.sql.contains("EXCEPT"))
    }

    // Leaf 5, INTERSECT on MySQL 8.0.30 raises Unsupported (one patch before support).
    "INTERSECT on MySQL 8.0.30 raises Unsupported" in {
        val q       = left.intersect(right)
        val backend = kyo.internal.SqlBackend.Mysql.versioned((8, 0, 30))
        val ex = intercept[SqlUnsupportedMysqlVersionFeatureException] {
            kyo.internal.SqlRender.render(q, backend, summon[Frame])
        }
        assert(ex.feature == "INTERSECT / EXCEPT", s"expected feature 'INTERSECT / EXCEPT', got: ${ex.feature}")
        assert(ex.requiredVersion == "8.0.31", s"expected requiredVersion '8.0.31', got: ${ex.requiredVersion}")
        assert(ex.serverVersion == "8.0.30", s"expected serverVersion '8.0.30', got: ${ex.serverVersion}")
    }

    // Leaf 6, EXCEPT on MySQL 5.7 raises Unsupported with the server version in the typed fields.
    "EXCEPT on MySQL 5.7 raises Unsupported" in {
        val q       = left.except(right)
        val backend = kyo.internal.SqlBackend.Mysql.versioned((5, 7, 44))
        val ex = intercept[SqlUnsupportedMysqlVersionFeatureException] {
            kyo.internal.SqlRender.render(q, backend, summon[Frame])
        }
        assert(ex.feature == "INTERSECT / EXCEPT", s"expected feature 'INTERSECT / EXCEPT', got: ${ex.feature}")
        assert(ex.requiredVersion == "8.0.31", s"expected requiredVersion '8.0.31', got: ${ex.requiredVersion}")
        assert(ex.serverVersion == "5.7.44", s"expected serverVersion '5.7.44', got: ${ex.serverVersion}")
    }

    // Leaf 7, INTERSECT ALL renders on PG (no version gate).
    "INTERSECT ALL renders on PG" in {
        val q = left.intersectAll(right)
        val r = q.renderPostgres
        assert(r.sql.contains("INTERSECT ALL"))
    }

    // Leaf 8, EXCEPT ALL renders on PG (no version gate).
    "EXCEPT ALL renders on PG" in {
        val q = left.exceptAll(right)
        val r = q.renderPostgres
        assert(r.sql.contains("EXCEPT ALL"))
    }

    // Leaf 9, INTERSECT ALL on MySQL 5.7 raises Unsupported.
    "INTERSECT ALL on MySQL 5.7 raises Unsupported" in {
        val q       = left.intersectAll(right)
        val backend = kyo.internal.SqlBackend.Mysql.versioned((5, 7, 44))
        val ex = intercept[SqlUnsupportedMysqlVersionFeatureException] {
            kyo.internal.SqlRender.render(q, backend, summon[Frame])
        }
        assert(ex.feature == "INTERSECT / EXCEPT", s"expected feature 'INTERSECT / EXCEPT', got: ${ex.feature}")
        assert(ex.requiredVersion == "8.0.31", s"expected requiredVersion '8.0.31', got: ${ex.requiredVersion}")
    }

    // Leaf 10, EXCEPT ALL on MySQL 8.0.30 raises Unsupported.
    "EXCEPT ALL on MySQL 8.0.30 raises Unsupported" in {
        val q       = left.exceptAll(right)
        val backend = kyo.internal.SqlBackend.Mysql.versioned((8, 0, 30))
        val ex = intercept[SqlUnsupportedMysqlVersionFeatureException] {
            kyo.internal.SqlRender.render(q, backend, summon[Frame])
        }
        assert(ex.feature == "INTERSECT / EXCEPT", s"expected feature 'INTERSECT / EXCEPT', got: ${ex.feature}")
        assert(ex.requiredVersion == "8.0.31", s"expected requiredVersion '8.0.31', got: ${ex.requiredVersion}")
    }

    // Leaf 11, UNION is unaffected by the gate on MySQL 5.7 (always supported).
    "UNION on MySQL 5.7 does not raise Unsupported" in {
        val q       = left.union(right)
        val backend = kyo.internal.SqlBackend.Mysql.versioned((5, 7, 44))
        val r       = kyo.internal.SqlRender.render(q, backend, summon[Frame])
        assert(r.sql.contains("UNION"))
        assert(!r.sql.contains("INTERSECT"))
        assert(!r.sql.contains("EXCEPT"))
    }

    // Leaf 12, supportsIntersectExcept boundary: exactly (8, 0, 31) returns true.
    "kyo.internal.SqlBackend.Mysql.versioned(8, 0, 31).supportsIntersectExcept is true" in {
        val backend = kyo.internal.SqlBackend.Mysql.versioned((8, 0, 31))
        assert(backend.supportsIntersectExcept)
    }

    // Leaf 13, supportsIntersectExcept boundary: (8, 0, 30) returns false.
    "kyo.internal.SqlBackend.Mysql.versioned(8, 0, 30).supportsIntersectExcept is false" in {
        val backend = kyo.internal.SqlBackend.Mysql.versioned((8, 0, 30))
        assert(!backend.supportsIntersectExcept)
    }

    // Leaf 14, supportsIntersectExcept: the default Mysql singleton (8.4.0) returns true.
    "kyo.internal.SqlBackend.Mysql.supportsIntersectExcept is true for the default singleton" in {
        assert(kyo.internal.SqlBackend.Mysql.supportsIntersectExcept)
    }

    // Leaf 15, MySQL 8.0.31 (default) emits INTERSECT keyword (supportsIntersectExcept = true).
    "INTERSECT on MySQL 8.4.0 (default) emits INTERSECT keyword" in {
        val q = left.intersect(right)
        val r = q.renderMysql
        assert(r.sql.contains("INTERSECT"))
    }

end SqlRenderIntersectExceptTest
