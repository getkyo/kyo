package kyo.internal.mysql.exchange

import kyo.Frame
import kyo.Maybe
import kyo.SqlServerConstraintViolationException
import kyo.SqlServerException
import kyo.internal.mysql.ErrPacket

private[mysql] object MysqlErrors:
    val SqlTextMaxLen: Int       = 4096
    val TruncationSuffix: String = "... [truncated]"

    def truncateSqlText(sql: String): String =
        if sql.length <= SqlTextMaxLen then sql
        else sql.take(SqlTextMaxLen) + TruncationSuffix

    /** Error numbers this server files under a SQLSTATE that hides the failure's class.
      *
      * The shared dispatcher classifies by SQLSTATE prefix, which is right for almost everything this server reports: a duplicate key and a
      * null into a NOT NULL column both carry `23000`, so both land in the constraint-violation leaf and the caller's
      * [[kyo.SqlIntegrityViolation]] recovery works. A violated CHECK constraint does not: it carries `HY000`, the general-error state, so
      * prefix dispatch drops it into the generic server error and a caller recovering on the marker handles the failure on one engine and
      * misses the identical failure here.
      *
      * The error number is the only thing that says what actually happened, and it belongs in this module rather than in the dispatcher,
      * which must not learn one engine's numbers. So this module builds the right leaf itself for the cases it knows, and it relays the
      * server's own SQLSTATE untouched rather than rewriting it to something the server never sent.
      */
    private val IntegrityViolationCodes: Set[Int] = Set(
        3819 // ER_CHECK_CONSTRAINT_VIOLATED
    )

    def mkServerError(
        err: ErrPacket,
        sqlText: Maybe[String],
        paramCount: Int,
        connectionId: Maybe[Long]
    )(using Frame): SqlServerException =
        val truncatedSql = sqlText.map(truncateSqlText)
        val extra        = Map("code" -> err.errorCode.toString)
        if IntegrityViolationCodes.contains(err.errorCode) then
            SqlServerConstraintViolationException(
                err.sqlState,
                "ERROR",
                err.errorMessage,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                extra,
                truncatedSql,
                paramCount,
                connectionId
            )
        else
            SqlServerException(
                err.sqlState,
                "ERROR",
                err.errorMessage,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                extra,
                truncatedSql,
                paramCount,
                connectionId
            )
        end if
    end mkServerError
end MysqlErrors
