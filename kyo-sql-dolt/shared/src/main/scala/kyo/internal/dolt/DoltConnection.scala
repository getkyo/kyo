package kyo.internal.dolt

import kyo.*
import kyo.db.Connection
import kyo.db.Idiom
import kyo.internal.mysql.MysqlSqlConnection

/** One Dolt session: a MySQL session plus the revision it is currently pointed at.
  *
  * Everything about speaking to the server is delegated to [[MysqlSqlConnection]] unchanged. What this class adds is which revision this
  * session is on, and the rule that every statement reconciles it against [[DoltBranchScope]] first: if the ambient revision differs from
  * where this session is pointed, a `USE` moves it before the statement runs. The active branch is session state, measured, so a pooled
  * connection carrying a previous borrower's branch would otherwise silently run the next caller's statements against the wrong data.
  *
  * Reconciliation deliberately does NOT happen on the reclaim and lifecycle paths. Those run after an interrupt, when the pool is deciding
  * whether the session can be reused, and a statement there would turn a cleanup into a round trip that can itself fail.
  */
final private[kyo] class DoltConnection(
    private[kyo] val underlying: MysqlSqlConnection,
    database: String,
    initialRevision: Maybe[String],
    private val pointedAt: AtomicRef.Unsafe[Maybe[String]]
) extends Connection:

    def id: Long = underlying.id

    /** The revision this session is pointed at right now, for the tests that assert the reconciliation actually happened. */
    private[kyo] def currentRevision(using Frame): Maybe[String] < Sync = Sync.defer(pointedAt.safe.get)

    /** Moves this session onto the revision the caller is asking for, when it is not already there.
      *
      * The target is the ambient revision, and where there is none it is the one the URL named, falling back to the database's own default
      * branch when the URL named none either. Reconciling on the way OUT of a branch scope is as necessary as on the way in: treating no
      * ambient revision as "leave the session wherever it is" leaves a connection on the scope's branch after the scope has ended.
      */
    private def reconciled[A, S](body: A < S)(using Frame): A < (S & Async & Abort[SqlException]) =
        classifying(moved(body))

    /** The reconciliation alone, for the one caller that must not have the error recover wrapped around it. See [[streamQuery]]. */
    private def moved[A, S](body: A < S)(using Frame): A < (S & Async & Abort[SqlException]) =
        locally {
            DoltBranchScope.current.map { ambient =>
                val wanted = if ambient.isDefined then ambient else initialRevision
                pointedAt.safe.get.map { current =>
                    if current == wanted then body
                    else
                        val statement = wanted match
                            case Present(revision) => DoltDatabase.useStatement(database, revision)
                            case Absent            => DoltDatabase.useDefaultStatement(database)
                        underlying.simpleExecute(statement).andThen {
                            pointedAt.safe.set(wanted).andThen(body)
                        }
                    end if
                }
            }
        }

    /** Puts a server failure in the family its error number names, which this engine's SQLSTATE cannot. Wrapped here rather than in the
      * MySQL layer beneath, which reports real states. See [[DoltErrors]].
      */
    private def classifying[A, S](body: A < S)(using Frame): A < (S & Abort[SqlException]) =
        Abort.recover[SqlException](e => Abort.fail(DoltErrors.reclassify(e)))(body)

    // --- Statements, each reconciled before it runs, and each with its temporal binds rewritten as text ---
    //
    // The rewrite is not an optimisation: this engine mis-stores the binary sub-second field, silently. See DoltTemporalBinds.

    def extendedQuery(sql: String, params: Chunk[Sql.BoundValue[?]])(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        reconciled(underlying.extendedQuery(sql, DoltTemporalBinds.rewrite(params)))

    def extendedExecute(sql: String, params: Chunk[Sql.BoundValue[?]])(using Frame): Long < (Async & Abort[SqlException]) =
        reconciled(underlying.extendedExecute(sql, DoltTemporalBinds.rewrite(params)))

    def extendedExecuteInsert(sql: String, params: Chunk[Sql.BoundValue[?]])(using
        Frame
    ): SqlClient.InsertOutcome < (Async & Abort[SqlException]) =
        reconciled(underlying.extendedExecuteInsert(sql, DoltTemporalBinds.rewrite(params)))

    def simpleQuery(sql: String)(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        reconciled(underlying.simpleQuery(sql))

    def simpleExecute(sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
        reconciled(underlying.simpleExecute(sql))

    /** A stream reconciles when it is BUILT rather than per element: its rows come from one server-side cursor opened once, so a revision
      * that changed underneath it could not retroactively move the cursor.
      *
      * The one statement path that reconciles WITHOUT [[classifying]] around it, deliberately: wrapping a streaming emit in the error
      * recover breaks the interrupt path, and the pool then discards the cancelled session instead of reclaiming it, measured. The cost is
      * narrow, since the reclassified families come from writes and a streaming SELECT does not produce them.
      */
    def streamQuery(sql: String, params: Chunk[Sql.BoundValue[?]], batchSize: Int)(using
        Frame
    ): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        Stream(moved(underlying.streamQuery(sql, DoltTemporalBinds.rewrite(params), batchSize).emit))

    /** Reclassifies the per-statement results as well as the call itself: a pipeline reports each statement's failure INSIDE its own
      * [[kyo.Result]] rather than on the abort channel, so the recover that classifies every other path never sees them.
      */
    def pipelined(stmts: Chunk[(String, Chunk[Sql.BoundValue[?]])])(using
        Frame
    ): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) =
        reconciled(underlying.pipelined(stmts.map((sql, params) => (sql, DoltTemporalBinds.rewrite(params))))).map { results =>
            results.map {
                case Result.Failure(e) => Result.Failure(DoltErrors.reclassify(e))
                case other             => other
            }
        }

    // --- Transactions. BEGIN reconciles, because it is what fixes the revision for everything inside ---

    /** Refuses a level this engine accepts but does not deliver, then begins the transaction. See
      * [[kyo.DoltIsolationLevelUnsupportedException]]. An unnamed level is left alone, which is the caller not asking for a guarantee
      * rather than asking for one this engine cannot keep.
      */
    def beginTransaction(isolation: Maybe[SqlClient.IsolationLevel], readOnly: Boolean)(using Frame): Unit < (Async & Abort[SqlException]) =
        isolation match
            case Present(level) if level != DoltConnection.DeliveredIsolation =>
                Abort.fail(DoltIsolationLevelUnsupportedException(level, DoltConnection.DeliveredIsolation))
            case _ => reconciled(underlying.beginTransaction(isolation, readOnly))

    def commitTransaction(using Frame): Unit < (Async & Abort[SqlException])   = underlying.commitTransaction
    def rollbackTransaction(using Frame): Unit < (Async & Abort[SqlException]) = underlying.rollbackTransaction

    def savepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException])           = underlying.savepoint(name)
    def releaseSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException])    = underlying.releaseSavepoint(name)
    def rollbackToSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException]) = underlying.rollbackToSavepoint(name)

    // --- Session ---

    def serverVersion(using Frame): Idiom.ServerVersion < (Async & Abort[SqlException]) = underlying.serverVersion

    def ping(using Frame): Unit < (Async & Abort[SqlException]) = underlying.ping

    /** A reset returns the session to the database the handshake put it on, which is the revision the URL named, so the recorded revision
      * goes back to that rather than staying wherever a branch scope moved it.
      */
    def resetSession(using Frame): Unit < (Async & Abort[SqlException]) =
        underlying.resetSession.andThen(pointedAt.safe.set(initialRevision))

    def acquireAdvisoryLock(key: Long, timeout: Maybe[Duration])(using Frame): Unit < (Async & Abort[SqlException]) =
        underlying.acquireAdvisoryLock(key, timeout)

    def releaseAdvisoryLock(key: Long)(using Frame): Unit < (Async & Abort[SqlException]) =
        underlying.releaseAdvisoryLock(key)

    // --- Reclaim and lifecycle, delegated without reconciling ---

    def inFlight(using AllowUnsafe): Boolean          = underlying.inFlight
    def inOpenTransaction(using AllowUnsafe): Boolean = underlying.inOpenTransaction

    def cancelInFlight(using Frame): Unit < (Async & Abort[SqlException])            = underlying.cancelInFlight
    def rollbackIfOpenTransaction(using Frame): Unit < (Async & Abort[SqlException]) = underlying.rollbackIfOpenTransaction
    def drainToIdle(using Frame): Boolean < (Async & Abort[SqlException])            = underlying.drainToIdle

    def isOpen(using Frame): Boolean < Sync      = underlying.isOpen
    def close(using Frame): Unit < Async         = underlying.close
    def closeNow(using Frame, AllowUnsafe): Unit = underlying.closeNow

end DoltConnection

private[kyo] object DoltConnection:

    /** The one level this engine actually runs at, whatever a transaction names. Measured: a re-read inside a READ COMMITTED transaction
      * does not see another transaction's committed write, and two concurrent increments under SERIALIZABLE lost one.
      */
    val DeliveredIsolation: SqlClient.IsolationLevel = SqlClient.IsolationLevel.RepeatableRead

end DoltConnection
