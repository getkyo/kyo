package kyo

import kyo.Sql.render

/** Backend gate tests for [[SqlAst.WithRecursive]] rendering (G-Parity-4).
  *
  * Verifies that:
  *   - Postgres always emits the `WITH RECURSIVE` keyword.
  *   - MySQL 8.0.0 or newer emits the `WITH RECURSIVE` keyword.
  *   - MySQL older than 8.0.0 (e.g. MySQL 5.7.x) raises [[SqlException.Unsupported]] at render time instead of emitting invalid SQL.
  */
class SqlRenderRecursiveCteTest extends Test:

    case class Node(id: Long, parentId: Long) derives Schema

    private val nodes = Sql.from[Node]("n")
    private val cte   = Sql.commonTable("node_tree", nodes)

    // Leaf 1, Postgres always emits WITH RECURSIVE (no version gate).
    "WITH RECURSIVE on Postgres emits WITH RECURSIVE keyword" in {
        val q = Sql.commonTablesRecursive(cte)(nodes)
        val r = q.render(SqlBackend.Postgres)
        assert(r.sql.startsWith("WITH RECURSIVE "))
    }

    // Leaf 2, MySQL default (8.4.0) emits WITH RECURSIVE keyword (supportsRecursiveCte = true).
    "WITH RECURSIVE on MySQL 8.4.0 (default) emits WITH RECURSIVE keyword" in {
        val q = Sql.commonTablesRecursive(cte)(nodes)
        val r = q.render(SqlBackend.Mysql)
        assert(r.sql.startsWith("WITH RECURSIVE "))
    }

    // Leaf 3, MySQL 8.0.0 (the first version that supports WITH RECURSIVE) emits WITH RECURSIVE keyword.
    "WITH RECURSIVE on MySQL 8.0.0+ emits WITH RECURSIVE keyword" in {
        val q       = Sql.commonTablesRecursive(cte)(nodes)
        val backend = SqlBackend.Mysql.versioned((8, 0, 0))
        val r       = q.render(backend)
        assert(r.sql.startsWith("WITH RECURSIVE "))
    }

    // Leaf 4, MySQL 5.7.x raises Unsupported with the server version in the message.
    "WITH RECURSIVE on MySQL 5.7 raises SqlException.Unsupported" in {
        val q       = Sql.commonTablesRecursive(cte)(nodes)
        val backend = SqlBackend.Mysql.versioned((5, 7, 44))
        val ex = intercept[SqlException.Unsupported] {
            q.render(backend)
        }
        assert(ex.getMessage.contains("WITH RECURSIVE requires MySQL 8.0"))
        assert(ex.getMessage.contains("5.7.44"))
    }

    // Leaf 5, supportsRecursiveCte boundary: exactly (8, 0, 0) returns true.
    "SqlBackend.Mysql.versioned(8, 0, 0).supportsRecursiveCte is true" in {
        val backend = SqlBackend.Mysql.versioned((8, 0, 0))
        assert(backend.supportsRecursiveCte)
    }

    // Leaf 6, supportsRecursiveCte boundary: (5, 7, 44) returns false.
    "SqlBackend.Mysql.versioned(5, 7, 44).supportsRecursiveCte is false" in {
        val backend = SqlBackend.Mysql.versioned((5, 7, 44))
        assert(!backend.supportsRecursiveCte)
    }

    // Leaf 7, supportsRecursiveCte: the default Mysql singleton (8.4.0) returns true.
    "SqlBackend.Mysql.supportsRecursiveCte is true for the default singleton" in {
        assert(SqlBackend.Mysql.supportsRecursiveCte)
    }

    // Leaf 8, plain WITH (non-recursive) is unaffected by the gate on all backends.
    "plain WITH (non-recursive) on MySQL 5.7 does not raise Unsupported" in {
        val q       = Sql.commonTables(cte)(nodes)
        val backend = SqlBackend.Mysql.versioned((5, 7, 44))
        val r       = q.render(backend)
        assert(r.sql.startsWith("WITH "))
        assert(!r.sql.startsWith("WITH RECURSIVE"))
    }

end SqlRenderRecursiveCteTest
