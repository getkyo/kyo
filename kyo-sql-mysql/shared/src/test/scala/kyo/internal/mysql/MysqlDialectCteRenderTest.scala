package kyo.internal.mysql

import kyo.*
import kyo.Test
import kyo.db.Idiom

/** Verifies how [[MysqlDialect]] answers for `WITH RECURSIVE` at each server version it may be asked about.
  *
  * A construct a flavor gained in a specific release is rendered only when the target version has it; below that the render fails typed
  * rather than emitting SQL the server rejects.
  */
class MysqlDialectCteRenderTest extends Test:

    case class Node(id: Long, parentId: Long)

    private val nodes = Sql.from[Node]("n")
    private val cte   = Sql.commonTable("node_tree", nodes)

    // MySQL default (8.4.0) emits WITH RECURSIVE keyword (supportsRecursiveCte = true).
    "WITH RECURSIVE on MySQL 8.4.0 (default) emits WITH RECURSIVE keyword" in {
        val q = Sql.commonTablesRecursive(cte)(nodes)
        val r = q.render(MysqlDialect)
        assert(r.onlySql.get.startsWith("WITH RECURSIVE "))
    }

    // MySQL 8.0.0 (the first version that supports WITH RECURSIVE) emits WITH RECURSIVE keyword.
    "WITH RECURSIVE on MySQL 8.0.0+ emits WITH RECURSIVE keyword" in {
        val q       = Sql.commonTablesRecursive(cte)(nodes)
        val version = Present(Idiom.ServerVersion(8, 0, 0))
        val r       = q.render(MysqlDialect, version)
        assert(r.onlySql.get.startsWith("WITH RECURSIVE "))
    }

    // MySQL 5.7.x raises Unsupported with the server version in the typed fields.
    "WITH RECURSIVE on MySQL 5.7 raises SqlUnsupportedException" in {
        val q       = Sql.commonTablesRecursive(cte)(nodes)
        val version = Present(Idiom.ServerVersion(5, 7, 44))
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            q.render(MysqlDialect, version)
        }
        assert(ex.feature == "WITH RECURSIVE", s"expected feature 'WITH RECURSIVE', got: ${ex.feature}")
        assert(ex.dialect == Idiom.Id("mysql"), s"expected dialect 'mysql', got: ${ex.dialect.value}")
        assert(
            ex.requiredVersion == Present(Idiom.ServerVersion(8, 0, 0)),
            s"expected requiredVersion '8.0.0', got: ${ex.requiredVersion.map(_.show)}"
        )
        assert(
            ex.serverVersion == Present(Idiom.ServerVersion(5, 7, 44)),
            s"expected serverVersion '5.7.44', got: ${ex.serverVersion.map(_.show)}"
        )
    }

    // Plain WITH (non-recursive) is unaffected by the gate on all backends.
    "plain WITH (non-recursive) on MySQL 5.7 does not raise Unsupported" in {
        val q       = Sql.commonTables(cte)(nodes)
        val version = Present(Idiom.ServerVersion(5, 7, 44))
        val r       = q.render(MysqlDialect, version)
        assert(r.onlySql.get.startsWith("WITH "))
        assert(!r.onlySql.get.startsWith("WITH RECURSIVE"))
    }

end MysqlDialectCteRenderTest
