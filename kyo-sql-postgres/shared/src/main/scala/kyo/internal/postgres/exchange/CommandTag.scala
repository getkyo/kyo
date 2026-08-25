package kyo.internal.postgres.exchange

/** Parses the affected-row count from a PostgreSQL `CommandComplete` command tag.
  *
  * Examples: "INSERT 0 1" → 1, "UPDATE 5" → 5, "DELETE 2" → 2, "SELECT 3" → 3, "CREATE TABLE" → 0.
  *
  * Shared by every exchange that reads a `CommandComplete` message: [[SimpleQueryExchange]], [[ExtendedQueryExchange]], and
  * [[PipelineExchange]] all parse the same tag shape.
  */
private[exchange] object CommandTag:

    def parseAffectedCount(tag: String): Long =
        val parts = tag.split(' ')
        if parts.length >= 2 then parts.last.toLongOption.getOrElse(0L)
        else 0L
    end parseAffectedCount

end CommandTag
