package kyo.internal.doltlite

import kyo.*
import kyo.db.Connection
import kyo.db.Idiom
import kyo.internal.dolt.DoltBranchScope
import kyo.internal.sqlite.SqliteConnection

/** One DoltLite session: a [[SqliteConnection]] delegated to unchanged, plus the branch it is checked out on.
  *
  * The checked-out branch is per-CONNECTION, so a pooled connection carrying a previous borrower's branch would
  * silently run the next caller's statements against the wrong data. Every statement reconciles against
  * [[DoltBranchScope]] before it runs, and reconciles BACK when the scope ends.
  */
final private[kyo] class DoltLiteConnection(
    private[kyo] val underlying: SqliteConnection,
    initialRevision: Maybe[String],
    private val pointedAt: AtomicRef.Unsafe[Maybe[String]]
) extends Connection:

    def id: Long = underlying.id

    /** Moves this session onto the revision the caller is asking for, when it is not already there.
      *
      * The target is the ambient revision, falling back to whatever the connection opened on. That fallback is what
      * returns a connection to its own branch when a scope ends, so an absent target still issues a checkout rather
      * than leaving the session where the scope left it.
      */
    private def reconciled[A, S](body: A < S)(using Frame): A < (S & Async & Abort[SqlException]) =
        DoltBranchScope.current.map { ambient =>
            val wanted = if ambient.isDefined then ambient else initialRevision
            pointedAt.safe.get.map { current =>
                if current == wanted then body
                else
                    val statement = wanted match
                        case Present(revision) => s"SELECT dolt_checkout('${revision.replace("'", "''")}')"
                        case Absent            => "SELECT dolt_checkout(dolt_default_branch())"
                    underlying.simpleQuery(statement).andThen {
                        pointedAt.safe.set(wanted).andThen(body)
                    }
            }
        }

    // --- Statements ---

    def extendedQuery(sql: String, params: Chunk[Sql.BoundValue[?]])(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        reconciled(underlying.extendedQuery(sql, params))

    def extendedExecute(sql: String, params: Chunk[Sql.BoundValue[?]])(using Frame): Long < (Async & Abort[SqlException]) =
        reconciled(underlying.extendedExecute(sql, params))

    def extendedExecuteInsert(sql: String, params: Chunk[Sql.BoundValue[?]])(using
        Frame
    ): SqlClient.InsertOutcome < (Async & Abort[SqlException]) =
        reconciled(underlying.extendedExecuteInsert(sql, params))

    def simpleQuery(sql: String)(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        reconciled(underlying.simpleQuery(sql))

    def simpleExecute(sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
        reconciled(underlying.simpleExecute(sql))

    /** Reconciles when the stream is BUILT rather than per element: the rows come from one cursor opened once. */
    def streamQuery(sql: String, params: Chunk[Sql.BoundValue[?]], batchSize: Int)(using
        Frame
    ): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        Stream(reconciled(underlying.streamQuery(sql, params, batchSize).emit))

    def pipelined(stmts: Chunk[(String, Chunk[Sql.BoundValue[?]])])(using
        Frame
    ): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) =
        reconciled(underlying.pipelined(stmts))

    // --- Transactions. Only BEGIN reconciles; it fixes the branch for everything inside ---

    def beginTransaction(isolation: Maybe[SqlClient.IsolationLevel], readOnly: Boolean)(using Frame): Unit < (Async & Abort[SqlException]) =
        reconciled(underlying.beginTransaction(isolation, readOnly))

    def commitTransaction(using Frame): Unit < (Async & Abort[SqlException])   = underlying.commitTransaction
    def rollbackTransaction(using Frame): Unit < (Async & Abort[SqlException]) = underlying.rollbackTransaction

    def savepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException])           = underlying.savepoint(name)
    def releaseSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException])    = underlying.releaseSavepoint(name)
    def rollbackToSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException]) = underlying.rollbackToSavepoint(name)

    // --- Session ---

    def serverVersion(using Frame): Idiom.ServerVersion < (Async & Abort[SqlException]) = underlying.serverVersion
    def ping(using Frame): Unit < (Async & Abort[SqlException])                         = underlying.ping

    /** The recorded branch must go back with the reset, or it goes stale and suppresses the next reconciliation. */
    def resetSession(using Frame): Unit < (Async & Abort[SqlException]) =
        underlying.resetSession.andThen(pointedAt.safe.set(initialRevision))

    def acquireAdvisoryLock(key: Long, timeout: Maybe[Duration])(using Frame): Unit < (Async & Abort[SqlException]) =
        underlying.acquireAdvisoryLock(key, timeout)

    def releaseAdvisoryLock(key: Long)(using Frame): Unit < (Async & Abort[SqlException]) =
        underlying.releaseAdvisoryLock(key)

    // --- Reclaim and lifecycle, delegated without reconciling ---
    //
    // These run after an interrupt, with the caller gone and the pool deciding whether the session can be reused.
    // A checkout here would turn a cleanup into a round trip that can itself fail.

    def inFlight(using AllowUnsafe): Boolean          = underlying.inFlight
    def inOpenTransaction(using AllowUnsafe): Boolean = underlying.inOpenTransaction

    def cancelInFlight(using Frame): Unit < (Async & Abort[SqlException])            = underlying.cancelInFlight
    def rollbackIfOpenTransaction(using Frame): Unit < (Async & Abort[SqlException]) = underlying.rollbackIfOpenTransaction
    def drainToIdle(using Frame): Boolean < (Async & Abort[SqlException])            = underlying.drainToIdle

    def isOpen(using Frame): Boolean < Sync      = underlying.isOpen
    def close(using Frame): Unit < Async         = underlying.close
    def closeNow(using Frame, AllowUnsafe): Unit = underlying.closeNow

end DoltLiteConnection
