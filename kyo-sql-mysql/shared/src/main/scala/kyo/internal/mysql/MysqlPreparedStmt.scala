package kyo.internal.mysql

/** Cached metadata for a server-side MySQL prepared statement (from [[ComStmtPrepare]] / [[StmtPrepareOk]]).
  *
  * The statement id is all of it. The column definitions PREPARE reports are deliberately not kept: the server resends them with every
  * EXECUTE, and those are the ones that describe the rows that execute carries. Holding the prepare-time set invited reading a row against
  * a description that was not its own, which is what a re-executed `SELECT *` after an `ALTER TABLE ADD COLUMN` made visible.
  *
  * @param stmtId
  *   the server-assigned statement ID
  */
final private[mysql] case class MysqlPreparedStmt(
    stmtId: Int
)
