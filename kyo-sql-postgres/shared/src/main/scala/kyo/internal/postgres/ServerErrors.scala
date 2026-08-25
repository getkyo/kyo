package kyo.internal.postgres

import kyo.*
import kyo.SqlServerException

/** Builds a [[kyo.SqlServerException]] from a PostgreSQL `ErrorResponse` field list.
  *
  * Every exchange that can meet an `ErrorResponse` mid-stream routes through here, so the mapping from wire tags to exception fields is
  * stated once. The tag vocabulary is PostgreSQL's (§52.8 of the protocol documentation): 'C' SQLSTATE, 'V' and 'S' severity, 'M' the
  * primary message, 'D' detail, 'H' hint, 'P' the cursor position, and eight further tags carried through as an extra map so a caller can
  * read the schema, table, column and constraint a constraint violation names.
  *
  * Severity is read from 'V' in preference to 'S'. The two carry the same word and only 'V' is guaranteed non-localized, so a server running
  * under a non-English `lc_messages` reports 'S' as a translated string that no caller can match on.
  */
object ServerErrors:

    private val SqlTextMaxLen    = 4096
    private val TruncationSuffix = "... [truncated]"

    private def truncateSqlText(sql: String): String =
        if sql.length <= SqlTextMaxLen then sql
        else sql.take(SqlTextMaxLen) + TruncationSuffix

    /** Converts an `ErrorResponse` field list to a [[kyo.SqlServerException]].
      *
      * @param fields
      *   raw wire fields from the ErrorResponse message
      * @param sqlText
      *   optional SQL text; truncated to 4096 chars if provided
      * @param paramCount
      *   number of bound parameters (0 if not applicable)
      * @param connectionId
      *   optional connection/thread ID
      */
    def mkServerError(
        fields: Chunk[(Byte, String)],
        sqlText: Maybe[String],
        paramCount: Int,
        connectionId: Maybe[Long]
    )(using Frame): SqlServerException =
        // The var accumulators below are local to a single error-path parse and are never shared across fibers.
        var sqlState                 = "00000"
        var severityV: Maybe[String] = Absent // non-localized, preferred
        var severityS: Maybe[String] = Absent // localized fallback
        var message                  = "Unknown server error"
        var detail                   = Maybe.empty[String]
        var hint                     = Maybe.empty[String]
        var position                 = Maybe.empty[Int]
        val extra                    = scala.collection.mutable.Map.empty[String, String]

        fields.foreach { case (tag, value) =>
            if tag == 'C'.toByte then sqlState = value
            else if tag == 'V'.toByte then severityV = Present(value)
            else if tag == 'S'.toByte then severityS = Present(value)
            else if tag == 'M'.toByte then message = value
            else if tag == 'D'.toByte then detail = Present(value)
            else if tag == 'H'.toByte then hint = Present(value)
            else if tag == 'P'.toByte then value.toIntOption.foreach(p => position = Present(p))
            else if tag == 's'.toByte then extra("schema") = value
            else if tag == 't'.toByte then extra("table") = value
            else if tag == 'c'.toByte then extra("column") = value
            else if tag == 'd'.toByte then extra("datatype") = value
            else if tag == 'n'.toByte then extra("constraint") = value
            else if tag == 'F'.toByte then extra("file") = value
            else if tag == 'L'.toByte then extra("line") = value
            else if tag == 'R'.toByte then extra("routine") = value
        // else: ignore unknown fields
        }

        val truncatedSql = sqlText.map(truncateSqlText)
        val severity     = severityV.orElse(severityS).getOrElse("ERROR")
        SqlServerException(sqlState, severity, message, detail, hint, position, extra.toMap, truncatedSql, paramCount, connectionId)
    end mkServerError

end ServerErrors
