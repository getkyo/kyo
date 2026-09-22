package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlException
import kyo.SqlServerException
import kyo.internal.postgres.PostgresChannel

/** Recognises the two ways PostgreSQL reports that a cached prepared statement can no longer be bound.
  *
  * Both are repaired by parsing the SQL again, and both are raised before the portal exists, so re-parsing and binding a second time
  * repeats nothing the server ran or the caller saw. That is a property of the RAISE SITE rather than of the SQLSTATE, which is why the
  * routine name is half the match:
  *
  *   - `26000` from `FetchPreparedStatement`: the name is gone, raised by `exec_bind_message` looking it up.
  *   - `0A000` from `RevalidateCachedQuery`: the name lives but its plan no longer describes the same result rowtype, typically after DDL.
  *     Raised from `GetCachedPlan` in the same Bind path, and only for a `fixed_result` plansource, which here means a protocol-level
  *     prepared statement.
  *
  * `42804` from `transformAssignedExpr` is deliberately absent: it is raised during re-analysis, which runs for the plansources SPI creates
  * without `fixed_result` too, so an `UPDATE` cached inside a PL/pgSQL function can raise it after rows have gone to the client.
  *
  * The routine names are C function names off the wire, not a documented interface. A rename in a future major costs the retry and nothing
  * else, and `StalePreparedStatementIntegrationTest` pins the observed strings against a real server.
  */
private[postgres] object StalePreparedStatement:

    private val qualifying = Map(
        "26000" -> "FetchPreparedStatement",
        "0A000" -> "RevalidateCachedQuery"
    )

    /** True when `error` is the server refusing a Bind for a statement this driver cached. */
    def matches(error: SqlException): Boolean =
        error match
            case e: SqlServerException =>
                qualifying.get(e.sqlState).exists(routine => e.extra.get("routine").contains(routine))
            case _ => false

    /** Records one re-prepare on `channel`'s counter and names it in the log.
      *
      * A burst of these says a deployment's `preparedStatementTtl` outlives its own DDL. Uncounted, the only symptom is the extra round
      * trips.
      */
    def record(channel: PostgresChannel, sql: String, error: SqlException)(using Frame): Unit < Sync =
        val state = error match
            case e: SqlServerException => e.sqlState
            case _                     => "unknown"
        channel.countReprepare.andThen(
            Log.debug(s"[kyo-sql] re-preparing a statement the server no longer holds, sqlState=$state: $sql")
        )
    end record

end StalePreparedStatement
