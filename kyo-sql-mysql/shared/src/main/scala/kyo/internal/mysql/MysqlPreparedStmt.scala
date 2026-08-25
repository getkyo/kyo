package kyo.internal.mysql

import kyo.Chunk

/** Cached metadata for a server-side MySQL prepared statement (from [[ComStmtPrepare]] / [[StmtPrepareOk]]).
  *
  * @param stmtId
  *   the server-assigned statement ID
  * @param columnDefs
  *   the column definitions for result columns (empty for DML)
  */
final private[mysql] case class MysqlPreparedStmt(
    stmtId: Int,
    columnDefs: Chunk[ColumnDefinition41]
)
