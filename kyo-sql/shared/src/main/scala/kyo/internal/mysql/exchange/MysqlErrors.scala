package kyo.internal.mysql.exchange

import kyo.Frame
import kyo.Maybe
import kyo.SqlException
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
    )(using frame: Frame): SqlException.Server =
        val truncatedSql = sqlText.map(truncateSqlText)
        SqlException.Server(
            err.sqlState,
            "ERROR",
            err.errorMessage,
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Map("code" -> err.errorCode.toString),
            truncatedSql,
            paramCount,
            connectionId,
            frame
        )
    end mkServerError
end MysqlErrors
