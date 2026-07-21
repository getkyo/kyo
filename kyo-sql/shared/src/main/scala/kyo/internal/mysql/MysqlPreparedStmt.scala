package kyo.internal.mysql

import kyo.Chunk

/** Cached metadata for a server-side MySQL prepared statement (from [[ComStmtPrepare]] / [[StmtPrepareOk]]).
  *
  * @param stmtId
  *   the server-assigned statement ID
  * @param paramCount
  *   number of `?` parameters in the SQL text
  * @param columnCount
  *   number of result columns (0 for DML statements)
  * @param paramTypes
  *   the raw column types of each parameter as reported by the server's param column-def packets (type byte from [[ColumnDefinition41]])
  * @param columnDefs
  *   the column definitions for result columns (empty for DML)
  */
final private[mysql] case class MysqlPreparedStmt(
    stmtId: Int,
    paramCount: Int,
    columnCount: Int,
    paramTypes: Chunk[Int],
    columnDefs: Chunk[ColumnDefinition41]
)
