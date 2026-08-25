package kyo.internal.postgres

import kyo.*
import kyo.Test

/** Verifies how [[PostgresDialect]] answers for `LATERAL` at each server version it may be asked about.
  *
  * A construct a flavor gained in a specific release is rendered only when the target version has it; below that the render fails typed
  * rather than emitting SQL the server rejects.
  */
class PostgresDialectLateralGateTest extends Test:

    case class Department(id: Long, name: String)

    // Leaf 1, Postgres always emits LATERAL (no version gate).
    "LATERAL on Postgres emits LATERAL keyword" in {
        val q = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get.contains("LATERAL"))
    }

end PostgresDialectLateralGateTest
