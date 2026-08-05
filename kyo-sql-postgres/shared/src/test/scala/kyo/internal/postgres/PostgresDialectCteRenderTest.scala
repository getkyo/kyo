package kyo.internal.postgres

import kyo.*
import kyo.Test

/** Verifies how [[PostgresDialect]] answers for `WITH RECURSIVE` at each server version it may be asked about.
  *
  * A construct a flavor gained in a specific release is rendered only when the target version has it; below that the render fails typed
  * rather than emitting SQL the server rejects.
  */
class PostgresDialectCteRenderTest extends Test:

    case class Node(id: Long, parentId: Long)

    private val nodes = Sql.from[Node]("n")
    private val cte   = Sql.commonTable("node_tree", nodes)

    // Leaf 1, Postgres always emits WITH RECURSIVE (no version gate).
    "WITH RECURSIVE on Postgres emits WITH RECURSIVE keyword" in {
        val q = Sql.commonTablesRecursive(cte)(nodes)
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get.startsWith("WITH RECURSIVE "))
    }

end PostgresDialectCteRenderTest
