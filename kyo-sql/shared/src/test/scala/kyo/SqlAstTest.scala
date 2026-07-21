package kyo

import kyo.SqlAst.*
import kyo.Test

/** Structural equality tests for SqlAst node types.
  *
  * Covers: Limit, Lock, OrderBy, GroupedColumn.
  */
class SqlAstTest extends Test:

    // Minimal table type used to build real Query values for wrapper-node tests.
    case class Row(id: Long) derives Schema, CanEqual

    "two equal Limit nodes compare ==" in {
        val q1     = Sql.from[Row]("r")
        val q2     = Sql.from[Row]("r")
        val limit1 = Limit(q1, 10, 0)
        val limit2 = Limit(q2, 10, 0)
        assert(limit1 == limit2)
    }

    "two unequal Limit nodes compare !=" in {
        val q      = Sql.from[Row]("r")
        val limit1 = Limit(q, 10, 0)
        val limit2 = Limit(q, 20, 0)
        assert(limit1 != limit2)
    }

    "two equal Lock nodes compare ==" in {
        val q     = Sql.from[Row]("r")
        val lock1 = Lock(q, Lock.Mode.Update, Lock.Behavior.Wait, Chunk.empty)
        val lock2 = Lock(q, Lock.Mode.Update, Lock.Behavior.Wait, Chunk.empty)
        assert(lock1 == lock2)
    }

    "two equal OrderBy nodes compare ==" in {
        val col    = Column["id", Long]("r", "id", "id")
        val spec   = OrderSpec(col, OrderSpec.Direction.Asc, OrderSpec.Nulls.Default)
        val q      = Sql.from[Row]("r")
        val order1 = OrderBy(q, OrderingSpecs.Resolved(Chunk(spec)))
        val order2 = OrderBy(q, OrderingSpecs.Resolved(Chunk(spec)))
        assert(order1 == order2)
    }

    "two equal GroupedColumn nodes compare ==" in {
        val col1 = Column["id", Long]("r", "id", "id")
        val col2 = Column["id", Long]("r", "id", "id")
        val gc1  = GroupedColumn(col1)
        val gc2  = GroupedColumn(col2)
        assert(gc1 == gc2)
    }

end SqlAstTest
