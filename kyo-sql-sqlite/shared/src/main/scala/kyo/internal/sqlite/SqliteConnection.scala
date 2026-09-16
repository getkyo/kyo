package kyo.internal.sqlite

import kyo.*
import kyo.db.Connection
import kyo.db.Idiom
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** One SQLite connection: a `sqlite3*` handle and the statements run over it.
  *
  * Almost nothing here resembles the network backends, because almost nothing is shared. There is no socket, no handshake, no negotiated
  * wire format, and no pipelining; a statement is prepared, stepped, and finalized against a library in this process. What the two DO share
  * is this trait, which is why the methods below read as ordinary work rather than as protocol exchanges.
  *
  * Serialisation is the one thing this class adds over the C API. A `sqlite3*` is safe to share between threads under
  * `SQLITE_THREADSAFE=1`, but a STATEMENT is not a transaction and the driver's callers assume a connection runs one thing at a time, so
  * every operation takes the connection's meter. That is also what makes [[cancelInFlight]] safe: it is the one call that deliberately runs
  * while another holds the meter.
  */
final private[kyo] class SqliteConnection(
    val id: Long,
    bindings: SqliteBindings,
    db: Ffi.Handle[SqliteDb],
    meter: Meter,
    lifetime: Meter,
    openFlag: AtomicBoolean,
    inFlightFlag: AtomicBoolean,
    inTransactionFlag: AtomicBoolean
) extends Connection:

    import SqliteConnection.*

    // --- Statements ---

    def extendedQuery(sql: String, params: Chunk[Sql.BoundValue[?]])(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        serialised(runQuery(sql, params))

    def simpleQuery(sql: String)(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        // SQLite has one statement API, so a simple query is an extended one with no parameters rather than a second
        // protocol. The two cannot diverge here the way they can on a network backend.
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
                given AllowUnsafe = AllowUnsafe.embrace.danger
                // A RETURNING clause is preferred over last_insert_rowid, which answers 0 for a WITHOUT ROWID table:
                // zero is neither an error nor a rowid, so reading it would report a key that does not exist.
                val fromReturning: Maybe[Long] =
                    if rows.isEmpty || rows.head.size == 0 then Absent
                    else
                        rows.head.column(0) match
                            case Present(bytes) => Maybe.fromOption(SqliteText.utf8(bytes).toLongOption)
                            case Absent         => Absent
                val key: SqlClient.InsertOutcome.GeneratedKey =
                    if rows.isEmpty then
                        // The renderer emits RETURNING whenever the table HAS a key column, so no rows at all is the
                        // clause having been omitted, which is itself the evidence there was no such column. Reading
                        // last_insert_rowid here instead would report the implicit rowid every ordinary table carries:
                        // a number the caller never declared, indistinguishable from a real key, and meaning something
                        // different from what the other engines answer for the same table.
                        SqlClient.InsertOutcome.GeneratedKey.NoAutoKey
                    else
                        fromReturning match
                            case Present(k) => SqlClient.InsertOutcome.GeneratedKey.Value(k)
                            case Absent =>
                                val rowid = bindings.lastInsertRowid(db)
                                // Unavailable rather than NoAutoKey: a zero rowid covers both a table with no such
                                // column and one whose value the caller supplied, and nothing here tells them apart.
                                if rowid != 0L then SqlClient.InsertOutcome.GeneratedKey.Value(rowid)
                                else SqlClient.InsertOutcome.GeneratedKey.Unavailable
                SqlClient.InsertOutcome(affected, key)
            }
        }

    /** Streams by stepping lazily, which is what SQLite does natively: a statement holds its own cursor and produces one row per step.
      *
      * `batchSize` is accepted and not used. It exists to bound a network round trip, and there is no round trip here; honouring it by
      * buffering would add latency for nothing.
      */
    def streamQuery(sql: String, params: Chunk[Sql.BoundValue[?]], batchSize: Int)(using
        Frame
    ): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        Stream {
            // The in-flight flag is held for the whole stream rather than per step. A prepared statement holds a CURSOR, so between two
            // steps the session is mid-exchange even though nothing is executing: a stream interrupted while parked would otherwise look
            // idle at the lease exit, and the pool would discard the connection instead of cancelling and reclaiming it.
            Scope.acquireRelease(
                serialised(prepare(sql, params)).map { stmt =>
                    inFlightFlag.unsafe.set(true)(using AllowUnsafe.embrace.danger)
                    stmt
                }
            )(stmt => finalizeStatement(stmt)).map { prepared =>
                val declarations = readDeclarations(prepared)
                val codec        = new SqliteRowCodec(declarations)
                val columns      = readColumns(prepared, declarations)
                def loop: Unit < (Async & Abort[SqlException] & Emit[Chunk[SqlRow]]) =
                    serialised(step(prepared)).map { rc =>
                        if rc == Done then
                            // Lowered HERE rather than in the scope's release, because the release runs on every exit including an
                            // interrupted one, and it runs BEFORE the lease decides what to do with the session. Reaching Done is what
                            // makes this exchange finished; any other way out leaves the flag up, which is the pool's signal to cancel
                            // and reclaim the session rather than discard it.
                            inFlightFlag.unsafe.set(false)(using AllowUnsafe.embrace.danger)
                        else
                            // Raised again after every row, because each step runs through `serialised`, whose own exit lowers the flag
                            // for the step it wrapped. The step ending is not the exchange ending: the cursor is still open with rows
                            // behind it, so the session stays in-flight until Done or until something interrupts it.
                            inFlightFlag.unsafe.set(true)(using AllowUnsafe.embrace.danger)
                            Emit.value(Chunk(readRow(prepared, columns, codec))).andThen(loop)
                    }
                loop
            }
        }
    end streamQuery

    /** Runs each statement in turn, which is what a pipeline means here.
      *
      * There is no batching to win: a pipeline exists to spend one round trip on several statements, and an embedded engine has none. The
      * shape is kept because the contract is per statement, and a caller composing one gets the same per-statement outcomes.
      */
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

    /** SQLite has no isolation vocabulary, so a level it cannot honour is REFUSED rather than silently ignored.
      *
      * Measured across every journal mode and BEGIN form: a reader inside a transaction repeats its read, so the engine is at least
      * REPEATABLE READ and never READ COMMITTED. Accepting `ReadCommitted` and running at snapshot would be a caller asking for one level
      * and silently getting another, which is the bug the isolation battery exists to catch.
      */
    def beginTransaction(isolation: Maybe[SqlClient.IsolationLevel], readOnly: Boolean)(using Frame): Unit < (Async & Abort[SqlException]) =
        isolation match
            case Present(level) if !SqliteConnection.honours(level) =>
                Abort.fail(SqliteIsolationLevelUnsupportedException(level, SqliteConnection.EngineLevel))
            case _ =>
                serialised {
                    // IMMEDIATE takes the write lock up front. A DEFERRED transaction that later writes can fail to
                    // upgrade against a concurrent reader, which surfaces as a failure at COMMIT rather than at the
                    // statement that caused it.
                    val begin = if readOnly then "BEGIN" else "BEGIN IMMEDIATE"
                    exec(begin).andThen {
                        if readOnly then exec("PRAGMA query_only = ON") else Kyo.unit
                    }.andThen(Sync.defer(inTransactionFlag.unsafe.set(true)(using AllowUnsafe.embrace.danger)))
                }

    def commitTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
        serialised {
            // A FAILED commit leaves the transaction open holding a PENDING lock, which then blocks new readers.
            // Measured. So a failure rolls back rather than leaving the connection blocked for the next lease.
            Abort.run[SqlException](exec("COMMIT")).map {
                case Result.Success(_) => clearTransaction.andThen(exec("PRAGMA query_only = OFF"))
                case Result.Failure(e) =>
                    Abort.run[SqlException](exec("ROLLBACK")).andThen(clearTransaction).andThen(Abort.fail(e))
                case Result.Panic(t) =>
                    Abort.run[SqlException](exec("ROLLBACK")).andThen(clearTransaction).andThen(Abort.panic(t))
            }
        }

    def rollbackTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
        serialised(exec("ROLLBACK").andThen(clearTransaction).andThen(exec("PRAGMA query_only = OFF")))

    def savepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        serialised(exec(s"SAVEPOINT ${quote(name)}"))

    def releaseSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        serialised(exec(s"RELEASE ${quote(name)}"))

    def rollbackToSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        serialised(exec(s"ROLLBACK TO ${quote(name)}"))

    // --- Session ---

    def serverVersion(using Frame): Idiom.ServerVersion < (Async & Abort[SqlException]) =
        Sync.defer {
            // Read from the library rather than assumed, so a build that linked a host library reports what it linked.
            val n = bindings.libversionNumber()(using AllowUnsafe.embrace.danger)
            Idiom.ServerVersion(n / 1000000, (n / 1000) % 1000, n % 1000)
        }

    def ping(using Frame): Unit < (Async & Abort[SqlException]) =
        serialised(exec("SELECT 1"))

    def resetSession(using Frame): Unit < (Async & Abort[SqlException]) =
        rollbackIfOpenTransaction

    /** SQLite has no advisory locks. Its concurrency is one writer over the whole database, so there is no per-key lock to take. */
    def acquireAdvisoryLock(key: Long, timeout: Maybe[Duration])(using Frame): Unit < (Async & Abort[SqlException]) =
        Abort.fail(SqliteAdvisoryLockUnsupportedException(key))

    def releaseAdvisoryLock(key: Long)(using Frame): Unit < (Async & Abort[SqlException]) =
        Abort.fail(SqliteAdvisoryLockUnsupportedException(key))

    // --- Reclaim ---

    def inFlight(using AllowUnsafe): Boolean = inFlightFlag.unsafe.get()

    def inOpenTransaction(using AllowUnsafe): Boolean = inTransactionFlag.unsafe.get()

    /** Interrupts whatever is running, from whichever fiber asked.
      *
      * The one call that deliberately runs while another holds the statement meter: an FFI call cannot be cancelled mid-flight, so stopping
      * a running step means telling SQLite to stop, and taking that meter first would wait for the very thing being interrupted.
      *
      * It takes the LIFETIME meter instead, which guards the handle's existence rather than what is running on it. Reading the open flag and
      * then interrupting is two steps, and a close landing between them would hand `sqlite3_interrupt` a freed handle, which is undefined
      * rather than merely racy. `close` takes the same meter, and only after the statement meter, so this can never wait on the statement it
      * was asked to stop.
      *
      * Interrupting a write inside an explicit transaction rolls the WHOLE transaction back, which the other two engines do not do. The
      * transaction flag is deliberately left alone: an interrupted SELECT ends no transaction, and the two cases are not distinguishable
      * from here, so the reclaim attempts the rollback and tolerates the failure rather than guessing.
      */
    def cancelInFlight(using Frame): Unit < (Async & Abort[SqlException]) =
        given AllowUnsafe = AllowUnsafe.embrace.danger
        Abort.run[Closed] {
            lifetime.run {
                Sync.defer {
                    if openFlag.unsafe.get() then bindings.interrupt(db)
                }
            }
        }.unit
    end cancelInFlight

    def rollbackIfOpenTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
        Sync.defer(inTransactionFlag.unsafe.get()(using AllowUnsafe.embrace.danger)).map { open =>
            if open then Abort.run[SqlException](rollbackTransaction).unit else Kyo.unit
        }

    /** Nothing is buffered, so a connection with no open transaction is already idle once any transaction is rolled back.
      *
      * Lowering the in-flight flag is the point of saying so. The flag stays UP when an exchange was interrupted, which is what routes the
      * session here instead of to the discard; leaving it up after the drain would mean the session never looks reusable again and the next
      * lease throws it away for a statement that is no longer running.
      */
    def drainToIdle(using Frame): Boolean < (Async & Abort[SqlException]) =
        rollbackIfOpenTransaction
            .andThen(Sync.defer(inFlightFlag.unsafe.set(false)(using AllowUnsafe.embrace.danger)))
            .andThen(true)

    // --- Lifecycle ---

    def isOpen(using Frame): Boolean < Sync =
        Sync.defer(openFlag.unsafe.get()(using AllowUnsafe.embrace.danger))

    /** Closes the handle, after everything that could still touch it has let go.
      *
      * The order is forced. The flag goes down FIRST, so an interrupt that has not started yet sees a closed connection and does nothing.
      * The statement meter comes next, because closing a connection another fiber is stepping is undefined rather than merely racy. The
      * lifetime meter comes LAST, to exclude an interrupt already past the flag read.
      *
      * Taking the lifetime meter before the statement meter would deadlock: this would hold it while waiting for a statement that is only
      * going to end when something interrupts it, and the interrupt needs the meter being held.
      */
    def close(using Frame): Unit < Async =
        Sync.defer(openFlag.unsafe.compareAndSet(true, false)(using AllowUnsafe.embrace.danger)).map { wasOpen =>
            if !wasOpen then Kyo.unit
            else
                Abort.run[Closed] {
                    meter.run {
                        lifetime.run {
                            Sync.Unsafe.defer(bindings.closeV2(db)).map(_.safe.get).unit
                        }
                    }
                }.unit
        }

    def closeNow(using Frame, AllowUnsafe): Unit =
        if openFlag.unsafe.compareAndSet(true, false) then discard(Sync.Unsafe.evalOrThrow(Sync.Unsafe.defer(bindings.closeV2(db))))

    // --- Internals ---

    /** Runs `op` holding the connection's meter, with the in-flight flag raised for the duration.
      *
      * The flag is what the pool reads to decide whether a lease that was interrupted left the session usable, so it comes down on every
      * exit including a failure.
      */
    private def serialised[A](op: => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        given AllowUnsafe = AllowUnsafe.embrace.danger
        // The meter closes with the connection, so a closed one is a closed connection rather than a distinct
        // condition. Mapping it here keeps every caller on the SqlException channel the SPI declares.
        Abort.run[Closed] {
            meter.run {
                Sync.defer(inFlightFlag.unsafe.set(true)).andThen {
                    // The body has to COMPLETE rather than short-circuit. A failure leaving `meter.run` skips the finalizer that
                    // returns the permit, so the next statement on this connection waits for a permit nothing will ever hand back:
                    // one refused write and the session is unusable for good. Running the failure to a `Result` makes every outcome a
                    // value, and `Abort.get` re-raises it below once the permit is back. Same shape `Scope.run` uses to make its
                    // finalizers run on a failing body.
                    Sync.ensure(err =>
                        // The interrupt path, which arrives here rather than as a value. A `leftSessionIdle` error leaves the flag
                        // UP so the pool cancels and reclaims the session instead of discarding it.
                        Sync.defer {
                            if Connection.leftSessionIdle(err) then inFlightFlag.unsafe.set(false)
                        }
                    ) {
                        Abort.run[SqlException](op).map { result =>
                            // The value path asks the same question of the outcome it just captured, since the finalizer above now
                            // sees `Absent` for a failure the body no longer short-circuits with.
                            if Connection.leftSessionIdle(result.error) then inFlightFlag.unsafe.set(false)
                            result
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

    private def clearTransaction(using Frame): Unit < Sync =
        Sync.defer(inTransactionFlag.unsafe.set(false)(using AllowUnsafe.embrace.danger))

    /** SQLite quotes identifiers the way PostgreSQL does. Savepoint names come from the driver rather than a caller, but quoting them keeps
      * a name that happens to be a keyword from changing what the statement means.
      */
    private def quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    private def exec(sql: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        Sync.Unsafe.defer(bindings.execSimple(db, sql)).map(_.safe.get).map { rc =>
            if rc == Ok then () else Abort.fail(failureOf(sql))
        }

    private def failureOf(sql: String)(using Frame): SqlException =
        given AllowUnsafe = AllowUnsafe.embrace.danger
        SqliteError.toException(bindings.extendedErrcode(db), bindings.errmsg(db).value, Present(sql), Present(id))
    end failureOf

    private def prepare(sql: String, params: Chunk[Sql.BoundValue[?]])(using
        Frame
    ): Ffi.Handle[SqliteStmt] < (Async & Abort[SqlException]) =
        Sync.Unsafe.defer(bindings.prepareOne(db, sql, -1)).map(_.safe.get).map {
            case Present(stmt) =>
                // A refused bind must not strand the statement. `prepareOne` already allocated it, and the caller never sees the handle
                // when the bind fails, so nothing else could finalize it.
                Abort.run[SqlException](bindParams(stmt, params)).map {
                    case Result.Success(_) => stmt
                    case Result.Failure(e) => finalizeStatement(stmt).andThen(Abort.fail(e))
                    case Result.Panic(t)   => finalizeStatement(stmt).andThen(Abort.panic(t))
                }
            case Absent =>
                given AllowUnsafe = AllowUnsafe.embrace.danger
                val code          = bindings.extendedErrcode(db)
                // A successful prepare leaves the code at OK, so OK here means the shim refused a trailing statement
                // rather than the SQL being wrong. The two need different messages to be actionable.
                if code == Ok then Abort.fail(SqliteMultipleStatementsException(sql))
                else Abort.fail(SqliteError.toException(code, bindings.errmsg(db).value, Present(sql), Present(id)))
        }

    /** Writes the bound values into `stmt`.
      *
      * `Abort.catching` because a codec refuses a value by THROWING, which inside a `Sync.defer` would reach the caller as a panic rather
      * than as the typed failure the refusal is. Refusing NaN is the reachable case: it is a request the backend declines, not a defect.
      */
    private def bindParams(stmt: Ffi.Handle[SqliteStmt], params: Chunk[Sql.BoundValue[?]])(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        Abort.catching[SqlException] {
            Sync.defer {
                given AllowUnsafe = AllowUnsafe.embrace.danger
                val writer        = new SqliteParamWriter(summon[Frame])
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
                        case SqliteParamWriter.Param.Text(v) =>
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
            if rc == Row || rc == Done then rc else Abort.fail(failureOf(""))
        }

    /** Sync rather than Async: finalizing is a plain call releasing the statement's own memory, with no I/O to wait on, which is what lets
      * it be the finalizer of a Sync.ensure around the stepping.
      */
    private def finalizeStatement(stmt: Ffi.Handle[SqliteStmt])(using Frame): Unit < Sync =
        Sync.defer(discard(bindings.finalizeStmt(stmt)(using AllowUnsafe.embrace.danger)))

    private def readDeclarations(stmt: Ffi.Handle[SqliteStmt])(using Frame): Chunk[Maybe[String]] =
        given AllowUnsafe = AllowUnsafe.embrace.danger
        val count         = bindings.columnCount(stmt)
        Chunk.from((0 until count).map(i => SqliteConnection.readDecltype(bindings, stmt, i)))
    end readDeclarations

    private def readColumns(stmt: Ffi.Handle[SqliteStmt], declarations: Chunk[Maybe[String]])(using Frame): Chunk[SqlRow.Column] =
        given AllowUnsafe = AllowUnsafe.embrace.danger
        // The token is the column's INDEX into the declarations, which is what lets the codec answer with a declared
        // type that is a string with parameters rather than anything an Int could pack.
        Chunk.from((0 until declarations.size).map(i => SqlRow.Column(bindings.columnName(stmt, i).value, i)))
    end readColumns

    private def readRow(stmt: Ffi.Handle[SqliteStmt], columns: Chunk[SqlRow.Column], codec: SqliteRowCodec)(using Frame): SqlRow =
        given AllowUnsafe = AllowUnsafe.embrace.danger
        val values = Chunk.from((0 until columns.size).map { i =>
            if bindings.columnType(stmt, i) == NullClass then Absent
            else
                // A blob's bytes ARE the value; everything else is read as the text the renderer wrote. The storage
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
      * One path for both, because SQLite makes no distinction: a statement with a RETURNING clause produces rows and changes rows at once,
      * and an engine that answered only one of them would drop half the outcome.
      */
    private def runQueryOrExecute(sql: String, params: Chunk[Sql.BoundValue[?]])(using
        Frame
    ): (Chunk[SqlRow], Long) < (Async & Abort[SqlException]) =
        prepare(sql, params).map { stmt =>
            AtomicBoolean.init(false).map { finalized =>
                // Finalizing twice on one handle is a use-after-free, and this has to happen on three different exits, so the flag makes
                // it idempotent rather than making each exit reason about the others.
                def finalizeOnce(using Frame): Unit < Sync =
                    finalized.compareAndSet(false, true).map {
                        case true  => finalizeStatement(stmt)
                        case false => ()
                    }
                // A REFUSED step is the reachable failure and the one that used to leak: the abort skipped past the finalizer, leaving
                // the statement allocated and holding its locks. Running the stepping to a `Result` means the finalize happens on the
                // value path, before `Abort.get` re-raises. The `Sync.ensure` stays for the interrupt, which arrives another way.
                Sync.ensure(finalizeOnce) {
                    val declarations = readDeclarations(stmt)
                    val codec        = new SqliteRowCodec(declarations)
                    val columns      = readColumns(stmt, declarations)
                    def loop(acc: Chunk[SqlRow]): Chunk[SqlRow] < (Async & Abort[SqlException]) =
                        step(stmt).map { rc =>
                            if rc == Done then acc else loop(acc.append(readRow(stmt, columns, codec)))
                        }
                    Abort.run[SqlException] {
                        loop(Chunk.empty).map { rows =>
                            // Read AFTER the steps: changes64 reports the most recent statement, and reading it earlier would
                            // report whatever ran before this one.
                            val affected = bindings.changes64(db)(using AllowUnsafe.embrace.danger)
                            (rows, affected)
                        }
                    }.map(result => finalizeOnce.andThen(Abort.get(result)))
                }
            }
        }

end SqliteConnection

private[sqlite] object SqliteConnection:

    val Ok        = 0
    val Row       = 100
    val Done      = 101
    val NullClass = 5
    val BlobClass = 4

    /** The level SQLite actually runs at, measured rather than declared: a reader inside a transaction repeats its read under every journal
      * mode and every BEGIN form, so it is at least REPEATABLE READ.
      */
    val EngineLevel: SqlClient.IsolationLevel = SqlClient.IsolationLevel.RepeatableRead

    /** Whether SQLite can honour `level`.
      *
      * The two weaker levels are refused rather than accepted and ignored. SQLite has no knob that produces them, so accepting one would be
      * a caller asking for READ COMMITTED and silently getting snapshot isolation.
      *
      * Serializable is accepted: the engine refuses a writer rather than losing an update, which is stronger than what was asked for, and a
      * caller asking for the strongest level is not harmed by getting it.
      */
    def honours(level: SqlClient.IsolationLevel): Boolean =
        level match
            case SqlClient.IsolationLevel.ReadUncommitted | SqlClient.IsolationLevel.ReadCommitted => false
            case SqlClient.IsolationLevel.RepeatableRead | SqlClient.IsolationLevel.Serializable   => true

    /** Reads a column's bytes, sized by the length the probing call reports rather than by a guess. */
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
