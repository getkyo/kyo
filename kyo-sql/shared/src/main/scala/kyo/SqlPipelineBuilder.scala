package kyo

import kyo.internal.client.SqlClientBackend

/** Accumulates pipeline statements for execution via [[SqlClient.pipeline]].
  *
  * Obtain an instance via `client.pipeline { builder => ... }`. Call [[execute]] or [[query]] to register each statement; on body
  * completion the caller flushes all statements in one TCP write.
  *
  * Thread-safety: A [[SqlPipelineBuilder]] is NOT thread-safe. It is always consumed synchronously within the pipeline body before flush.
  */
final class SqlPipelineBuilder:
    private val _stmts = scala.collection.mutable.ArrayBuffer.empty[(String, Seq[BoundValue[?]])]

    /** Registers a DML statement for later batch execution. Returns `Unit` within the pipeline body; the actual row-count is returned by
      * the outer `pipeline` call as part of the result [[Chunk]].
      */
    def execute(sql: String): Unit =
        execute(sql, Seq.empty)

    def execute(sql: String, params: Seq[BoundValue[?]]): Unit =
        val _ = _stmts += ((sql, params))

    /** Registers a query statement for later batch execution. Returns `Unit` within the pipeline body; the rows are returned by the outer
      * `pipeline` call as part of the result [[Chunk]].
      */
    def query(sql: String): Unit =
        query(sql, Seq.empty)

    def query(sql: String, params: Seq[BoundValue[?]]): Unit =
        val _ = _stmts += ((sql, params))

    /** Returns the accumulated statements as an immutable [[Chunk]]. Called internally by [[SqlClient.pipeline]] after the body returns. */
    private[kyo] def drainStmts(): Chunk[(String, Seq[BoundValue[?]])] =
        Chunk.from(_stmts.toSeq)
end SqlPipelineBuilder
