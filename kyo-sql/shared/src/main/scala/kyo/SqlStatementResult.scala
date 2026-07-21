package kyo

/** Per-statement outcome returned by [[SqlClient.pipeline]].
  *
  * Each element of the `Chunk[SqlStatementResult]` returned by `pipeline` corresponds to one statement registered on the
  * [[SqlClient.PipelineBuilder]], in submission order. A per-statement error is represented as [[SqlStatementResult.Failure]] and does NOT abort
  * subsequent statements.
  */
sealed abstract class SqlStatementResult derives CanEqual

object SqlStatementResult:

    /** The statement executed successfully.
      *
      * @param rows
      *   rows returned by the statement (empty for DML statements such as INSERT/UPDATE/DELETE)
      * @param affectedRowCount
      *   number of rows affected (0 for query statements that return rows; populated for DML)
      */
    final case class Success(rows: Chunk[SqlRow], affectedRowCount: Long) extends SqlStatementResult derives CanEqual

    /** The statement failed with a per-statement server error.
      *
      * Subsequent statements in the same pipeline are unaffected because each statement has its own `Sync` barrier.
      *
      * @param error
      *   the SQL error returned by the server
      */
    final case class Failure(error: SqlException) extends SqlStatementResult derives CanEqual
end SqlStatementResult
