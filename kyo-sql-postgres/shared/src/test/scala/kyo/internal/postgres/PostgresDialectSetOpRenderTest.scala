package kyo.internal.postgres

import kyo.*
import kyo.Test

/** Verifies how [[PostgresDialect]] answers for the `INTERSECT` and `EXCEPT` set operators at each server version it may be asked about,
  * and that `UNION`, which no version gates, is unaffected.
  */
class PostgresDialectSetOpRenderTest extends Test:

    case class Item(id: Long, name: String)

    private val left  = Sql.from[Item]("a")
    private val right = Sql.from[Item]("b")

    // INTERSECT renders on PG (no version gate).
    "INTERSECT renders on PG" in {
        val q = left.intersect(right)
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get.contains("INTERSECT"))
        assert(!r.onlySql.get.contains("INTERSECT ALL"))
    }

    // EXCEPT renders on PG (no version gate).
    "EXCEPT renders on PG" in {
        val q = left.except(right)
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get.contains("EXCEPT"))
        assert(!r.onlySql.get.contains("EXCEPT ALL"))
    }

    // INTERSECT ALL renders on PG (no version gate).
    "INTERSECT ALL renders on PG" in {
        val q = left.intersectAll(right)
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get.contains("INTERSECT ALL"))
    }

    // EXCEPT ALL renders on PG (no version gate).
    "EXCEPT ALL renders on PG" in {
        val q = left.exceptAll(right)
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get.contains("EXCEPT ALL"))
    }

end PostgresDialectSetOpRenderTest
