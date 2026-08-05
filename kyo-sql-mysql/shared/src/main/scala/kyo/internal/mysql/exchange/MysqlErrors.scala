package kyo.internal.mysql.exchange

import kyo.Frame
import kyo.Maybe
import kyo.SqlServerException
import kyo.internal.mysql.ErrPacket

private[mysql] object MysqlErrors:
    val SqlTextMaxLen: Int       = 4096
    val TruncationSuffix: String = "... [truncated]"

    def truncateSqlText(sql: String): String =
        if sql.length <= SqlTextMaxLen then sql
        else sql.take(SqlTextMaxLen) + TruncationSuffix

    def mkServerError(
        err: ErrPacket,
        sqlText: Maybe[String],
        paramCount: Int,
        connectionId: Maybe[Long]
    )(using Frame): SqlServerException =
        val truncatedSql = sqlText.map(truncateSqlText)
        SqlServerException(
            err.sqlState,
            "ERROR",
            err.errorMessage,
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Map("code" -> err.errorCode.toString),
            truncatedSql,
            paramCount,
            connectionId
        )
    end mkServerError
end MysqlErrors
