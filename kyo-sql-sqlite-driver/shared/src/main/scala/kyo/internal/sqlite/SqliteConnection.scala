package kyo.internal.sqlite

import kyo.*
import kyo.db.Connection
import kyo.db.Idiom
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** One SQLite connection: a `sqlite3*` handle and the statements run over it.
  *
  * A `sqlite3*` is safe to share between threads under `SQLITE_THREADSAFE=1`, but a statement is not, and callers assume a connection runs
  * one thing at a time, so every operation takes the connection's meter. [[cancelInFlight]] is the one call that deliberately runs while
  * another holds it.
  *
  * A native call that can take a file's write lock first takes that file's [[SqliteWriteGate]], and the gate is given back once the
  * connection holds no write lock, read from the engine rather than tracked: a COMMIT, a ROLLBACK, a failed statement SQLite rolled back and
  * an autocommit statement running to its end all leave it there.
  */
final private[kyo] class SqliteConnection(
    val id: Long,
    bindings: SqliteBindings,
    db: Ffi.Handle[SqliteDb],
    meter: Meter,
    lifetime: Meter,
    openFlag: AtomicBoolean,
    inFlightFlag: AtomicBoolean,
    inTransactionFlag: AtomicBoolean,
    private[sqlite] val writeGate: SqliteWriteGate.Hold,
    busyTimeout: Duration
) extends Connection:

    import SqliteConnection.*

    // --- Statements ---

    def extendedQuery(sql: String, params: Chunk[Sql.BoundValue[?]])(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        serialised(runQuery(sql, params))

    def simpleQuery(sql: String)(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        serialised(runQuery(sql, Chunk.empty))

    def extendedExecute(sql: String, params: Chunk[Sql.BoundValue[?]])(using Frame): Long < (Async & Abort[SqlException]) =
        serialised(runExecute(sql, params).map(_._1))

    def simpleExecute(sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
        serialised(runExecute(sql, Chunk.empty).map(_._1))

    def extendedExecuteInsert(sql: String, params: Chunk[Sql.BoundValue[?]])(using
        Frame
    ): SqlClient.InsertOutcome < (Async & Abort[SqlException]) =
        serialised {
            runExecute(sql, params).map { (affected, rows) =>
                // RETURNING is preferred over last_insert_rowid, which answers 0 for a WITHOUT ROWID table: zero is
                // neither an error nor a rowid, so reading it would report a key that does not exist.
                val fromReturning: Maybe[Long] =
                    if rows.isEmpty || rows.head.size == 0 then Absent
                    else
                        rows.head.column(0) match
                            case Present(bytes) => Maybe.fromOption(SqliteText.utf8(bytes).toLongOption)
                            case Absent         => Absent
                val key: SqlClient.InsertOutcome.GeneratedKey < Sync =
                    if rows.isEmpty then
                        // The renderer emits RETURNING whenever the table has a key column, so no rows at all means
                        // there was no such column. Reading last_insert_rowid here would report the implicit rowid
                        // every ordinary table carries, a number the caller never declared.
                        SqlClient.InsertOutcome.GeneratedKey.NoAutoKey
                    else
                        fromReturning match
                            case Present(k) => SqlClient.InsertOutcome.GeneratedKey.Value(k)
                            case Absent     =>
                                Sync.Unsafe.defer(bindings.lastInsertRowid(db)).map { rowid =>
                                    // Unavailable rather than NoAutoKey: a zero rowid covers both a table with no such
                                    // column and one whose value the caller supplied, and nothing here tells them apart.
                                    if rowid != 0L then SqlClient.InsertOutcome.GeneratedKey.Value(rowid)
                                    else SqlClient.InsertOutcome.GeneratedKey.Unavailable
                                }
                key.map(SqlClient.InsertOutcome(affected, _))
            }
        }

    /** Streams by stepping lazily: a statement holds its own cursor and produces one row per step.
      *
      * `batchSize` is accepted and not used. It bounds a network round trip, and there is none here.
      */
    def streamQuery(sql: String, params: Chunk[Sql.BoundValue[?]], batchSize: Int)(using
        Frame
    ): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        Stream {
            // The in-flight flag is held for the whole stream rather than per step: between two steps the cursor is open even though
            // nothing is executing, so a stream interrupted while parked would otherwise look idle at the lease exit and the pool would
            // discard the connection instead of cancelling and reclaiming it.
            Scope.acquireRelease(
                serialised(prepare(sql, params)).map(stmt => inFlightFlag.set(true).andThen(stmt))
            )(stmt => finalizeStatement(stmt)).map { prepared =>
                Sync.Unsafe.defer {
                    val declarations = readDeclarations(prepared)
                    (new SqliteRowCodec(declarations), readColumns(prepared, declarations))
                }.map { (codec, columns) =>
                    def loop: Unit < (Async & Abort[SqlException] & Emit[Chunk[SqlRow]]) =
                        serialised(admitWrite(prepared, sql).andThen(step(prepared))).map { rc =>
                            if rc == Done then
                                // Lowered here rather than in the scope's release, which runs on every exit including an interrupted
                                // one and runs before the lease decides what to do with the session. Any way out other than Done leaves
                                // the flag up, which is the pool's signal to cancel and reclaim the session rather than discard it.
                                inFlightFlag.set(false)
                            else
                                // Raised again after every row, because each step runs through `serialised`, whose exit lowers the flag
                                // for the step it wrapped. The cursor is still open, so the session stays in-flight until Done.
                                inFlightFlag.set(true)
                                    .andThen(Sync.Unsafe.defer(readRow(prepared, columns, codec)))
                                    .map(row => Emit.value(Chunk(row)))
                                    .andThen(loop)
                        }
                    loop
                }
            }
        }
    end streamQuery

    /** Runs each statement in turn: an embedded engine has no round trip to batch, and the contract is per statement either way. */
    def pipelined(stmts: Chunk[(String, Chunk[Sql.BoundValue[?]])])(using
        Frame
    ): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) =
        serialised {
            Kyo.foreach(stmts) { (sql, params) =>
                Abort.run[SqlException] {
                    runQueryOrExecute(sql, params).map { (rows, affected) =>
                        SqlClient.PipelineBuilder.Outcome(rows, affected)
                    }
                }
            }
        }

    // --- Transactions ---

    def beginTransaction(isolation: Maybe[SqlClient.IsolationLevel], readOnly: Boolean)(using Frame): Unit < (Async & Abort[SqlException]) =
        isolation match
            case Present(level) if !SqliteConnection.honours(level) =>
                Abort.fail(SqliteIsolationLevelUnsupportedException(level, SqliteConnection.EngineLevel))
            case _ =>
                serialised {
                    // IMMEDIATE takes the write lock up front. A DEFERRED transaction that later writes can fail to
                    // upgrade against a concurrent reader, surfacing at COMMIT rather than at the statement that caused it.
                    val begin = if readOnly then "BEGIN" else "BEGIN IMMEDIATE"
                    // The flag goes up BEFORE BEGIN is sent. An interrupt landing while BEGIN waits for another connection's
                    // write lock abandons this continuation, and the native call still takes the lock once it frees: raised
                    // after BEGIN returns, the flag would stay down, the reclaim would skip its rollback, and the pool would
                    // hand out a session holding the database's write lock. The reclaim's ROLLBACK queues behind the native
                    // BEGIN on the handle's mutex, so it runs once BEGIN has resolved, and a BEGIN that failed leaves it
                    // nothing to undo.
                    inTransactionFlag.set(true).andThen {
                        val gated = if readOnly then exec(begin) else takeGate(begin).andThen(exec(begin))
                        Abort.run[SqlException](gated).map {
                            case Result.Success(_) => if readOnly then exec("PRAGMA query_only = ON") else Kyo.unit
                            case Result.Failure(e) => clearTransaction.andThen(Abort.fail(e))
                            case Result.Panic(t)   => Abort.panic(t)
                        }
                    }
                }

    def commitTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
        serialised {
            // A failed COMMIT leaves the transaction open holding a PENDING lock, which blocks new readers, so a failure
            // rolls back rather than leaving the connection blocked for the next lease.
            Abort.run[SqlException](exec("COMMIT")).map {
                case Result.Success(_) => clearTransaction.andThen(exec("PRAGMA query_only = OFF"))
                case Result.Failure(e) =>
                    Abort.run[SqlException](exec("ROLLBACK")).andThen(clearTransaction).andThen(Abort.fail(e))
                case Result.Panic(t) =>
                    Abort.run[SqlException](exec("ROLLBACK")).andThen(clearTransaction).andThen(Abort.panic(t))
            }
        }

    def rollbackTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
        serialised {
            Abort.run[SqlException](exec("ROLLBACK")).map { result =>
                // A ROLLBACK refused because no transaction is open still ends with none open, as when an interrupt lands while BEGIN
                // waits for the write gate and BEGIN never runs. Asking the engine lowers the flag on that path too, which otherwise
                // stays up and sends every later lease of the connection through a reclaim.
                Sync.Unsafe.defer(bindings.getAutocommit(db) != 0).map { ended =>
                    if ended then clearTransaction.andThen(exec("PRAGMA query_only = OFF")).andThen(Abort.get(result))
                    else Abort.get(result)
                }
            }
        }

    def savepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        serialised(exec(s"SAVEPOINT ${quote(name)}"))

    def releaseSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        serialised(exec(s"RELEASE ${quote(name)}"))

    def rollbackToSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        serialised(exec(s"ROLLBACK TO ${quote(name)}"))

    // --- Session ---

    def serverVersion(using Frame): Idiom.ServerVersion < (Async & Abort[SqlException]) =
        Sync.Unsafe.defer(bindings.libversionNumber()).map(n => Idiom.ServerVersion(n / 1000000, (n / 1000) % 1000, n % 1000))

    def ping(using Frame): Unit < (Async & Abort[SqlException]) =
        serialised(exec("SELECT 1"))

    def resetSession(using Frame): Unit < (Async & Abort[SqlException]) =
        rollbackIfOpenTransaction

    def acquireAdvisoryLock(key: Long, timeout: Maybe[Duration])(using Frame): Unit < (Async & Abort[SqlException]) =
        Abort.fail(SqliteAdvisoryLockUnsupportedException(key))

    def releaseAdvisoryLock(key: Long)(using Frame): Unit < (Async & Abort[SqlException]) =
        Abort.fail(SqliteAdvisoryLockUnsupportedException(key))

    // --- Reclaim ---

    def inFlight(using AllowUnsafe): Boolean = inFlightFlag.unsafe.get()

    def inOpenTransaction(using AllowUnsafe): Boolean = inTransactionFlag.unsafe.get()

    /** Interrupts whatever is running, from whichever fiber asked.
      *
      * The one call that deliberately runs while another holds the statement meter: an FFI call cannot be cancelled mid-flight, so taking
      * that meter first would wait for the very thing being interrupted. It takes the lifetime meter instead, which guards the handle's
      * existence: a close landing between the open-flag read and the interrupt would hand `sqlite3_interrupt` a freed handle. `close` takes
      * that same meter, and only after the statement meter, so this can never wait on the statement it was asked to stop.
      *
      * The transaction flag is deliberately left alone. Interrupting a write inside an explicit transaction rolls the whole transaction
      * back while an interrupted SELECT ends no transaction, and the two are not distinguishable from here, so the reclaim attempts the
      * rollback and tolerates the failure.
      */
    def cancelInFlight(using Frame): Unit < (Async & Abort[SqlException]) =
        Abort.run[Closed] {
            lifetime.run {
                openFlag.get.map(open => if open then Sync.Unsafe.defer(bindings.interrupt(db)) else Kyo.unit)
            }
        }.unit

    def rollbackIfOpenTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
        inTransactionFlag.get.map { open =>
            if open then Abort.run[SqlException](rollbackTransaction).unit else Kyo.unit
        }

    /** Nothing is buffered, so a connection with no open transaction is idle once any transaction is rolled back.
      *
      * Lowering the in-flight flag is the point. The flag stays up when an exchange was interrupted, which is what routes the session here;
      * leaving it up after the drain would mean the session never looks reusable again.
      */
    def drainToIdle(using Frame): Boolean < (Async & Abort[SqlException]) =
        rollbackIfOpenTransaction
            // The gate an interrupted statement left held, which the statement path does not give back while a native call it
            // abandoned may still be running.
            .andThen(releaseGateIfUnlocked)
            .andThen(inFlightFlag.set(false))
            .andThen(true)

    // --- Lifecycle ---

    def isOpen(using Frame): Boolean < Sync =
        openFlag.get

    /** Closes the handle, after everything that could still touch it has let go.
      *
      * The order is forced. The flag goes down first, so an interrupt that has not started yet sees a closed connection. The statement
      * meter comes next, because closing a connection another fiber is stepping is undefined. The lifetime meter comes last, to exclude an
      * interrupt already past the flag read. Taking the lifetime meter before the statement meter would deadlock: this would hold it while
      * waiting for a statement that only ends when something interrupts it, and the interrupt needs the meter being held.
      */
    def close(using Frame): Unit < Async =
        openFlag.compareAndSet(true, false).map { wasOpen =>
            if !wasOpen then Kyo.unit
            else
                Abort.run[Closed] {
                    meter.run {
                        lifetime.run {
                            Sync.Unsafe.defer(bindings.closeV2(db)).map(_.safe.get).unit
                        }
                    }
                }.andThen(writeGate.close)
        }

    def closeNow(using Frame, AllowUnsafe): Unit =
        if openFlag.unsafe.compareAndSet(true, false) then
            discard(bindings.closeV2(db))
            writeGate.unsafe.close()

    // --- Internals ---

    /** Runs `op` holding the connection's meter, with the in-flight flag raised for the duration.
      *
      * The pool reads the flag to decide whether an interrupted lease left the session usable, so it comes down on every exit including a
      * failure.
      */
    private def serialised[A](op: => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        // The meter closes with the connection, so a Closed here is a closed connection. Mapping it keeps every caller
        // on the SqlException channel the SPI declares.
        Abort.run[Closed] {
            meter.run {
                inFlightFlag.set(true).andThen {
                    // The body has to complete rather than short-circuit: a failure leaving `meter.run` skips the finalizer that
                    // returns the permit, and the next statement on this connection then waits for a permit nothing will hand back.
                    // Running the failure to a `Result` makes every outcome a value, and `Abort.get` re-raises it below once the
                    // permit is back.
                    Sync.ensure(err =>
                        // The interrupt path, which arrives here rather than as a value. An error that did not leave the session
                        // idle keeps the flag up, so the pool cancels and reclaims the session instead of discarding it.
                        if Connection.leftSessionIdle(err) then inFlightFlag.set(false) else Kyo.unit
                    ) {
                        Abort.run[SqlException](op).map { result =>
                            // The value path asks the same question of the outcome it just captured: the finalizer above sees
                            // `Absent` for a failure the body no longer short-circuits with.
                            val lowered = if Connection.leftSessionIdle(result.error) then inFlightFlag.set(false) else Kyo.unit
                            // Only here, where every native call the body made has returned. An interrupted body may have abandoned
                            // one still running under the lock, and releasing then would send the next writer to park on a native
                            // thread behind it; drainToIdle gives the gate back on that path.
                            lowered.andThen(releaseGateIfUnlocked).andThen(result)
                        }
                    }
                }
            }
        }.map {
            case Result.Success(inner) => Abort.get(inner)
            case Result.Failure(_)     => Abort.fail(SqlConnectionClosedException("statement"))
            case Result.Panic(t)       => Abort.panic(t)
        }
    end serialised

    private def releaseGateIfUnlocked(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            if writeGate.unsafe.held() && bindings.txnState(db) != WriteLocked then writeGate.unsafe.release()
        }

    /** Takes the write gate before `stmt` first runs, when running it can take a write lock this connection does not already hold.
      *
      * A connection already holding a write lock without the gate, on a file attached by a statement rather than through
      * [[kyo.SqliteAttach]], proceeds without waiting: waiting for the gate while holding that lock would deadlock against a holder of
      * the gate parked behind it.
      */
    private def admitWrite(stmt: Ffi.Handle[SqliteStmt], sql: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        Sync.Unsafe.defer(bindings.stmtReadonly(stmt) == 0 && bindings.txnState(db) != WriteLocked).map { writes =>
            if writes then takeGate(sql) else Kyo.unit
        }

    /** Waits for the write gate as long as SQLite's busy handler would wait for the lock, and fails as SQLite does once that runs out. */
    private def takeGate(sql: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        writeGate.acquire(busyTimeout).map { taken =>
            if taken then ()
            else Abort.fail(SqliteErrors.toException(Busy, "database is locked", Present(sql), Present(id)))
        }

    private def clearTransaction(using Frame): Unit < Sync =
        inTransactionFlag.set(false)

    private def quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    private def exec(sql: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        Sync.Unsafe.defer(bindings.execSimple(db, sql)).map(_.safe.get).map { rc =>
            if rc == Ok then () else failureOf(sql).map(Abort.fail(_))
        }

    /** The connection's last error. Read in the step after the failing call, before anything else runs on the connection. */
    private def failureOf(sql: String)(using Frame): SqlException < Sync =
        Sync.Unsafe.defer(SqliteErrors.toException(bindings.extendedErrcode(db), bindings.errmsg(db).value, Present(sql), Present(id)))

    private def prepare(sql: String, params: Chunk[Sql.BoundValue[?]])(using
        Frame
    ): Ffi.Handle[SqliteStmt] < (Async & Abort[SqlException]) =
        Sync.Unsafe.defer(bindings.prepareOne(db, sql, -1)).map(_.safe.get).map {
            case Present(stmt) =>
                // A refused bind must not strand the statement: `prepareOne` already allocated it, and the caller never sees the handle
                // when the bind fails, so nothing else could finalize it.
                Abort.run[SqlException](bindParams(stmt, params)).map {
                    case Result.Success(_) => stmt
                    case Result.Failure(e) => finalizeStatement(stmt).andThen(Abort.fail(e))
                    case Result.Panic(t)   => finalizeStatement(stmt).andThen(Abort.panic(t))
                }
            case Absent =>
                Sync.Unsafe.defer {
                    val code = bindings.extendedErrcode(db)
                    // A successful prepare leaves the code at OK, so OK here means the shim refused a trailing statement
                    // rather than the SQL being wrong.
                    if code == Ok then SqliteMultipleStatementsException(sql)
                    else SqliteErrors.toException(code, bindings.errmsg(db).value, Present(sql), Present(id))
                }.map(Abort.fail(_))
        }

    /** Writes the bound values into `stmt`.
      *
      * `Abort.catching` because a codec refuses a value by throwing, which inside a `Sync.defer` would reach the caller as a panic rather
      * than as the typed failure the refusal is. Refusing NaN is the reachable case.
      */
    private def bindParams(stmt: Ffi.Handle[SqliteStmt], params: Chunk[Sql.BoundValue[?]])(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        Abort.catching[SqlException] {
            Sync.Unsafe.defer {
                val writer = new SqliteParamWriter(summon[Frame])
                params.foreach { case b: Sql.BoundValue[a] => b.schema.write(b.value, writer) }
                val collected = writer.params
                var i         = 0
                while i < collected.size do
                    // Parameter indices are ONE-based, unlike the zero-based column indices read back.
                    val slot = i + 1
                    collected(i) match
                        case SqliteParamWriter.Param.Null       => discard(bindings.bindNull(stmt, slot))
                        case SqliteParamWriter.Param.Integer(v) => discard(bindings.bindInt64(stmt, slot, v))
                        case SqliteParamWriter.Param.Real(v)    => discard(bindings.bindDouble(stmt, slot, v))
                        case SqliteParamWriter.Param.Text(v)    =>
                            val bytes = v.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            discard(Buffer.useArray(bytes)(buf => bindings.bindTextCopy(stmt, slot, buf, bytes.length)))
                        case SqliteParamWriter.Param.Blob(v) =>
                            val bytes = v.toArray
                            discard(Buffer.useArray(bytes)(buf => bindings.bindBlobCopy(stmt, slot, buf, bytes.length)))
                    end match
                    i += 1
                end while
            }
        }

    private def step(stmt: Ffi.Handle[SqliteStmt])(using Frame): Int < (Async & Abort[SqlException]) =
        Sync.Unsafe.defer(bindings.step(stmt)).map(_.safe.get).map { rc =>
            if rc == Row || rc == Done then rc else failureOf("").map(Abort.fail(_))
        }

    /** Sync rather than Async, with no I/O to wait on, so it can be the finalizer of a `Sync.ensure` around the stepping. */
    private def finalizeStatement(stmt: Ffi.Handle[SqliteStmt])(using Frame): Unit < Sync =
        Sync.Unsafe.defer(discard(bindings.finalizeStmt(stmt)))

    private def readDeclarations(stmt: Ffi.Handle[SqliteStmt])(using AllowUnsafe): Chunk[Maybe[String]] =
        val count = bindings.columnCount(stmt)
        Chunk.from((0 until count).map(i => SqliteConnection.readDecltype(bindings, stmt, i)))
    end readDeclarations

    private def readColumns(stmt: Ffi.Handle[SqliteStmt], declarations: Chunk[Maybe[String]])(using AllowUnsafe): Chunk[SqlRow.Column] =
        // The token is the column's index into the declarations, which is how the codec reaches its declared type.
        Chunk.from((0 until declarations.size).map(i => SqlRow.Column(bindings.columnName(stmt, i).value, i)))

    private def readRow(stmt: Ffi.Handle[SqliteStmt], columns: Chunk[SqlRow.Column], codec: SqliteRowCodec)(using AllowUnsafe): SqlRow =
        val values = Chunk.from((0 until columns.size).map { i =>
            if bindings.columnType(stmt, i) == NullClass then Absent
            else
                // A blob's bytes are the value; everything else is read as the text the renderer wrote. The storage
                // class decides which accessor, because the length has to be read through the same one.
                val blob = bindings.columnType(stmt, i) == BlobClass
                Present(SqliteConnection.readBytes(bindings, stmt, i, textual = !blob))
        })
        new SqlRow(values, columns, codec)
    end readRow

    private def runQuery(sql: String, params: Chunk[Sql.BoundValue[?]])(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        runQueryOrExecute(sql, params).map(_._1)

    private def runExecute(sql: String, params: Chunk[Sql.BoundValue[?]])(using
        Frame
    ): (Long, Chunk[SqlRow]) < (Async & Abort[SqlException]) =
        runQueryOrExecute(sql, params).map((rows, affected) => (affected, rows))

    /** Prepares, steps to exhaustion, and answers both the rows and the change count.
      *
      * One path for both: a statement with a RETURNING clause produces rows and changes rows at once.
      */
    private def runQueryOrExecute(sql: String, params: Chunk[Sql.BoundValue[?]])(using
        Frame
    ): (Chunk[SqlRow], Long) < (Async & Abort[SqlException]) =
        prepare(sql, params).map { stmt =>
            AtomicBoolean.init(false).map { finalized =>
                // Finalizing twice on one handle is a use-after-free, and this has to happen on three different exits, so the flag
                // makes it idempotent.
                def finalizeOnce(using Frame): Unit < Sync =
                    finalized.compareAndSet(false, true).map {
                        case true  => finalizeStatement(stmt)
                        case false => ()
                    }
                // A refused step must not skip the finalize: an unfinalized statement stays allocated and holds its locks. Running the
                // stepping to a `Result` puts the finalize on the value path, before `Abort.get` re-raises; the `Sync.ensure` covers
                // the interrupt, which arrives another way.
                Sync.ensure(finalizeOnce) {
                    admitWrite(stmt, sql).andThen {
                        Sync.Unsafe.defer {
                            val declarations = readDeclarations(stmt)
                            (new SqliteRowCodec(declarations), readColumns(stmt, declarations))
                        }.map { (codec, columns) =>
                            def loop(acc: Chunk[SqlRow]): Chunk[SqlRow] < (Async & Abort[SqlException]) =
                                step(stmt).map { rc =>
                                    if rc == Done then acc
                                    else Sync.Unsafe.defer(readRow(stmt, columns, codec)).map(row => loop(acc.append(row)))
                                }
                            Abort.run[SqlException] {
                                // Read AFTER the steps: changes64 reports the most recent statement, and reading it earlier would
                                // report whatever ran before this one.
                                loop(Chunk.empty).map(rows => Sync.Unsafe.defer(bindings.changes64(db)).map((rows, _)))
                            }.map(result => finalizeOnce.andThen(Abort.get(result)))
                        }
                    }
                }
            }
        }

end SqliteConnection

private[sqlite] object SqliteConnection:

    val Ok        = 0
    val Row       = 100
    val Done      = 101
    val Busy      = 5
    val NullClass = 5
    val BlobClass = 4

    /** `SQLITE_TXN_WRITE`, the transaction state of a connection holding a write lock. */
    val WriteLocked = 2

    /** The level SQLite actually runs at: a reader inside a transaction repeats its read under every journal mode and every BEGIN form. */
    val EngineLevel: SqlClient.IsolationLevel = SqlClient.IsolationLevel.RepeatableRead

    /** Whether SQLite can honour `level`.
      *
      * The two weaker levels are refused rather than accepted and ignored, since SQLite has no knob that produces them. Serializable is
      * accepted because the engine refuses a writer rather than losing an update, which is stronger than what was asked for.
      */
    def honours(level: SqlClient.IsolationLevel): Boolean =
        level match
            case SqlClient.IsolationLevel.ReadUncommitted | SqlClient.IsolationLevel.ReadCommitted => false
            case SqlClient.IsolationLevel.RepeatableRead | SqlClient.IsolationLevel.Serializable   => true

    /** Reads a column's bytes, sized by the length the probing call reports. */
    def readBytes(bindings: SqliteBindings, stmt: Ffi.Handle[SqliteStmt], idx: Int, textual: Boolean)(using AllowUnsafe): Span[Byte] =
        def read(dst: Buffer[Byte], cap: Int): Int =
            if textual then bindings.columnTextBytes(stmt, idx, dst, cap) else bindings.columnBlobBytes(stmt, idx, dst, cap)
        val size = Buffer.use[Byte, Int](0)(probe => read(probe, 0))
        if size <= 0 then Span.empty
        else
            Buffer.use[Byte, Span[Byte]](size) { buf =>
                discard(read(buf, size))
                Span.from(Array.tabulate(size)(i => buf.get(i)))
            }
        end if
    end readBytes

    /** The declared type, with absent distinguished from empty: -1 means the column has none, which is not a zero-length one. */
    def readDecltype(bindings: SqliteBindings, stmt: Ffi.Handle[SqliteStmt], idx: Int)(using AllowUnsafe): Maybe[String] =
        val size = Buffer.use[Byte, Int](0)(probe => bindings.columnDecltypeBytes(stmt, idx, probe, 0))
        if size < 0 then Absent
        else if size == 0 then Present("")
        else
            Present(Buffer.use[Byte, String](size) { buf =>
                discard(bindings.columnDecltypeBytes(stmt, idx, buf, size))
                new String(Array.tabulate(size)(i => buf.get(i)), java.nio.charset.StandardCharsets.UTF_8)
            })
        end if
    end readDecltype

end SqliteConnection
