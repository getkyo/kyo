package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlCodec.Format
import kyo.SqlException
import kyo.SqlRow
import kyo.internal.auth.PureHash
import kyo.internal.postgres.*

/** Implements the PostgreSQL extended-query protocol cycle (Parse/Bind/Execute).
  *
  * Sequence per query:
  *   1. Look up `sql` in the per-connection prepared-statement cache.
  *   2. On cache miss:
  *      - Send `Parse(stmtName, sql, paramOids=[])`, server infers parameter types.
  *      - Send `Describe('S', stmtName)` to learn `ParameterDescription` and `RowDescription`.
  *      - Send `Sync`.
  *      - Read: `ParseComplete`, `ParameterDescription`, `RowDescription | NoData`, `ReadyForQuery`.
  *      - Store the resulting [[PreparedStmt]] in the cache.
  *   3. With a [[PreparedStmt]] in hand:
  *      - Encode parameters via their [[BoundParam]] encoders.
  *      - Send `Bind("", stmtName, paramFormats, paramValues, resultFormats)`.
  *      - Send `Execute("", 0)` (unlimited rows; streaming uses a separate stream exchange).
  *      - Send `Sync`.
  *      - Read: `BindComplete`, `DataRow*`, `CommandComplete | NoData`, `ReadyForQuery`.
  *   4. Build and return the rows.
  *
  * Prepared-statement eviction: the cache has a bounded size (CLOCK eviction). When an entry is evicted, the connection's
  * [[PostgresConnection.drainPendingCloses]] sends `Close 'S' <name>` before the next request, releasing the server-side statement.
  */
object ExtendedQueryExchange:

    /** Runs an extended query and returns all rows. */
    def query(
        channel: PostgresChannel,
        stmtCache: Cache[String, PreparedStmt],
        stmtCounter: AtomicLong,
        sql: String,
        params: Chunk[BoundParam[?]],
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        prepareAndBind(channel, stmtCache, stmtCounter, sql, params, pid, onParameterStatus, onNotification)
            .map { case (rows, _) => rows }

    /** Runs an extended execute and returns the number of affected rows. */
    def execute(
        channel: PostgresChannel,
        stmtCache: Cache[String, PreparedStmt],
        stmtCounter: AtomicLong,
        sql: String,
        params: Chunk[BoundParam[?]],
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): Long < (Async & Abort[SqlException]) =
        prepareAndBind(channel, stmtCache, stmtCounter, sql, params, pid, onParameterStatus, onNotification)
            .map { case (_, affected) => affected }

    /** Computes the parameter OID list to send in Parse from the [[BoundParam]] sequence.
      *
      * Sending explicit OIDs lets the server choose the correct binary representation for parameters that have no contextual type inference
      * signal (e.g. `SELECT $1` standalone). Encoders that opt into "let server infer" (oid=0) are forwarded as-is.
      */
    private[exchange] def paramOidsOf(params: Chunk[BoundParam[?]]): Chunk[Int] =
        params.map(_.encoder.oid)

    // --- Internals ---

    /** Resolves or creates a prepared statement, returning it for use by Bind/Execute.
      *
      * Shared with [[StreamQueryExchange]]: both phases need the same Parse → cache → PreparedStmt logic. The optional `paramOids` Chunk
      * lets the caller declare parameter types up front; if non-empty, the server uses these instead of inferring from context.
      */
    private[exchange] def prepareStmt(
        channel: PostgresChannel,
        stmtCache: Cache[String, PreparedStmt],
        stmtCounter: AtomicLong,
        sql: String,
        paramOids: Chunk[Int],
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): PreparedStmt < (Async & Abort[SqlException]) =
        val key = cacheKey(sql, paramOids)
        stmtCache.get(key).flatMap {
            case Present(stmt) =>
                // Cache hit, return immediately.
                stmt
            case Absent =>
                parseAndCache(channel, stmtCache, stmtCounter, key, sql, paramOids, pid, onParameterStatus, onNotification)
        }
    end prepareStmt

    /** Parses and describes `sql` under a fresh server-side name, and caches the result under `key`.
      *
      * The name carries a per-connection sequence number so a re-Parse cannot land on a still-live `s_$key` whose `Close 'S'` is queued in
      * `pendingCloses` but not yet flushed. The server rejects a Parse onto a registered name with `42P05`.
      */
    private[exchange] def parseAndCache(
        channel: PostgresChannel,
        stmtCache: Cache[String, PreparedStmt],
        stmtCounter: AtomicLong,
        key: String,
        sql: String,
        paramOids: Chunk[Int],
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): PreparedStmt < (Async & Abort[SqlException]) =
        stmtCounter.incrementAndGet.flatMap { seq =>
            parseAndDescribe(channel, s"s_${seq}_$key", sql, paramOids, pid, onParameterStatus, onNotification).flatMap { stmt =>
                stmtCache.add(key, stmt).andThen(stmt)
            }
        }

    /** Resolves the prepared statement (cache or wire) then binds and executes, re-preparing once if the server refuses the Bind for a
      * statement it no longer holds.
      *
      * The entry survives the failure, so without the retry that SQL fails for the life of the connection rather than once.
      *
      * Each condition is load-bearing:
      *
      *   - Only on a cache HIT. On a miss the statement was just parsed, so the server calling it missing is an inconsistency to surface.
      *   - Only for [[StalePreparedStatement]].
      *   - Only while the portal is still unopened, which is the invariant the codes are chosen for and the only one that makes a second
      *     attempt free. `FetchPreparedStatement` is not exclusively the Bind-path lookup: it also raises for the name in a SQL-level
      *     `EXECUTE`, which reaches the server inside a portal this driver bound and opened successfully. The `BindComplete` that precedes
      *     the error says which of the two happened, where the routine name alone cannot.
      *   - Only while the session is idle. PostgreSQL aborts the whole block on a statement error, so a retry inside a failed transaction
      *     answers `25P02` and the caller reads that in place of what happened. The status byte is the server's own, because a caller who
      *     opened the block with a raw `BEGIN` never touched this adapter's transaction methods.
      *   - Once. A second failure is not a stale-cache symptom.
      *
      * The entry is dropped whether or not the retry runs, so a declined retry still heals the next statement. The SECOND error surfaces:
      * after a `DROP TABLE` the re-Parse answers `42P01`, and reporting the stale code would hide it.
      *
      * Repeating the Bind is safe because [[kyo.BoundParam.encoded]] builds a fresh buffer from the held value per call.
      */
    private def prepareAndBind(
        channel: PostgresChannel,
        stmtCache: Cache[String, PreparedStmt],
        stmtCounter: AtomicLong,
        sql: String,
        params: Chunk[BoundParam[?]],
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): (Chunk[SqlRow], Long) < (Async & Abort[SqlException]) =
        val paramOids = paramOidsOf(params)
        val key       = cacheKey(sql, paramOids)

        def rowsOf(outcome: ExecuteOutcome)(using Frame): (Chunk[SqlRow], Long) < Abort[SqlException] =
            outcome match
                case ExecuteOutcome.Rows(rows, affected) => (rows, affected)
                case ExecuteOutcome.Failed(error, _)     => Abort.fail(error)

        stmtCache.get(key).flatMap {
            case Present(stmt) =>
                bindAndExecute(channel, stmt, params, pid, onParameterStatus, onNotification).flatMap {
                    case ExecuteOutcome.Rows(rows, affected) => (rows, affected)
                    // Every other failure leaves the statement usable. Dropping it would empty the cache for any
                    // SQL that fails routinely, a constraint violation among them.
                    case ExecuteOutcome.Failed(error, portalOpened)
                        if portalOpened || !StalePreparedStatement.matches(error) => Abort.fail(error)
                    case ExecuteOutcome.Failed(error, _) =>
                        // Read before the retry touches the wire: its Parse produces a ReadyForQuery of its
                        // own, and only the most recent one is recorded.
                        channel.lastReadyForQueryStatus.flatMap { status =>
                            stmtCache.remove(key).andThen {
                                if status == ReadyForQuery.Idle then
                                    StalePreparedStatement.record(channel, sql, error).andThen(
                                        parseAndCache(
                                            channel,
                                            stmtCache,
                                            stmtCounter,
                                            key,
                                            sql,
                                            paramOids,
                                            pid,
                                            onParameterStatus,
                                            onNotification
                                        ).flatMap(fresh =>
                                            bindAndExecute(channel, fresh, params, pid, onParameterStatus, onNotification).flatMap(rowsOf)
                                        )
                                    )
                                else Abort.fail(error)
                            }
                        }
                }
            case Absent =>
                parseAndCache(channel, stmtCache, stmtCounter, key, sql, paramOids, pid, onParameterStatus, onNotification)
                    .flatMap(stmt => bindAndExecute(channel, stmt, params, pid, onParameterStatus, onNotification).flatMap(rowsOf))
        }
    end prepareAndBind

    /** Sends Parse + Describe + Sync, reads the responses, returns the [[PreparedStmt]]. */
    private def parseAndDescribe(
        channel: PostgresChannel,
        stmtName: String,
        sql: String,
        paramOids: Chunk[Int],
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): PreparedStmt < (Async & Abort[SqlException]) =
        val parseM    = channel.marshallers.parse
        val describeM = channel.marshallers.describe
        val syncM     = channel.marshallers.sync
        for
            _    <- channel.send(Parse(stmtName, sql, paramOids))(using parseM)
            _    <- channel.send(Describe('S'.toByte, stmtName))(using describeM)
            _    <- channel.send(kyo.internal.postgres.SyncMessage)(using syncM)
            stmt <- readParseDescribeResponses(
                channel,
                stmtName,
                sql,
                pid,
                onParameterStatus,
                onNotification
            )
        yield stmt
        end for
    end parseAndDescribe

    private def readParseDescribeResponses(
        channel: PostgresChannel,
        stmtName: String,
        sql: String,
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): PreparedStmt < (Async & Abort[SqlException]) =
        // State to collect: ParseComplete, ParameterDescription, RowDescription|NoData, ReadyForQuery
        def loop(
            parseDone: Boolean,
            paramOids: Maybe[Chunk[Int]],
            rowDesc: Maybe[Maybe[RowDescription]]
        ): PreparedStmt < (Async & Abort[SqlException]) =
            channel.receive.flatMap {
                case ParseComplete =>
                    loop(parseDone = true, paramOids, rowDesc)

                case ParameterDescription(types) =>
                    loop(parseDone, Present(types), rowDesc)

                case rd: RowDescription =>
                    loop(parseDone, paramOids, Present(Present(rd)))

                case NoData =>
                    loop(parseDone, paramOids, Present(Absent))

                case _: ReadyForQuery =>
                    // All expected messages have arrived; build the PreparedStmt.
                    val oids = paramOids.getOrElse(Chunk.empty)
                    // rowDesc is Maybe[Maybe[RowDescription]]:
                    //   Absent           = no RowDescription or NoData not yet received
                    //   Present(Absent)  = NoData received (DML / non-SELECT)
                    //   Present(Present(rd)) = RowDescription received
                    val rdMaybe: Maybe[RowDescription] = rowDesc match
                        case Absent               => Absent
                        case Present(Absent)      => Absent
                        case Present(Present(rd)) => Present(rd)
                    // Request binary format for all result columns, the registered decoders all support
                    // binary encoding, which is more compact and avoids text-parsing overhead.
                    val resultFmts = rdMaybe match
                        case Absent      => Chunk.empty[Short]
                        case Present(rd) =>
                            rd.fields.map(_ => Format.Binary.code)
                    PreparedStmt(stmtName, sql, oids, rdMaybe, resultFmts)

                case msg =>
                    ReadLoopSideband.handle(
                        msg,
                        channel,
                        Present(sql),
                        0,
                        pid,
                        "Parse/Describe",
                        "ParseComplete / ParameterDescription / RowDescription / NoData / ReadyForQuery",
                        onParameterStatus,
                        onNotification
                    )(loop(parseDone, paramOids, rowDesc))
            }

        loop(parseDone = false, Absent, Absent)
    end readParseDescribeResponses

    /** What one Bind/Execute cycle produced.
      *
      * A server error is a value here rather than an abort, because the only caller that can act on it needs to know whether the portal had
      * opened when it arrived, and an abort cannot carry that. Connection-level failures still abort.
      */
    private enum ExecuteOutcome:
        case Rows(rows: Chunk[SqlRow], affected: Long)
        case Failed(error: SqlException, portalOpened: Boolean)

    /** Sends Bind + Execute + Sync and reads the responses. */
    private def bindAndExecute(
        channel: PostgresChannel,
        stmt: PreparedStmt,
        params: Chunk[BoundParam[?]],
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): ExecuteOutcome < (Async & Abort[SqlException]) =
        val paramFormats: Chunk[Short]            = params.map(_.encoder.format.code)
        val paramValues: Chunk[Maybe[Span[Byte]]] = params.map(_.encoded)
        val bindMsg                               = Bind(
            portalName = "",
            stmtName = stmt.name,
            paramFormats = paramFormats,
            paramValues = paramValues,
            resultFormats = stmt.resultFormats
        )
        val bindM    = channel.marshallers.bind
        val executeM = channel.marshallers.execute
        val syncM    = channel.marshallers.sync
        for
            _      <- channel.send(bindMsg)(using bindM)
            _      <- channel.send(Execute("", 0))(using executeM)
            _      <- channel.send(kyo.internal.postgres.SyncMessage)(using syncM)
            result <- readExecuteResponses(channel, stmt, params.size, Present(stmt.sql), pid, onParameterStatus, onNotification)
        yield result
        end for
    end bindAndExecute

    private def readExecuteResponses(
        channel: PostgresChannel,
        stmt: PreparedStmt,
        paramCount: Int,
        sqlText: Maybe[String],
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): ExecuteOutcome < (Async & Abort[SqlException]) =
        val fields: Chunk[FieldDescription] = stmt.rowDescription match
            case Absent      => Chunk.empty
            case Present(rd) => rd.fields

        // `portalOpened` follows BindComplete down the loop rather than living in a cell, so what the caller
        // reads is the value this read produced.
        def loop(acc: Chunk[SqlRow], portalOpened: Boolean)(using Frame): ExecuteOutcome < (Async & Abort[SqlException]) =
            channel.receive.flatMap {
                case BindComplete =>
                    loop(acc, portalOpened = true)

                case DataRow(values) =>
                    val format = if stmt.resultFormats.nonEmpty then
                        Format.fromCode(stmt.resultFormats(0))
                    else Format.Text
                    loop(acc.appended(PostgresRowCodec.row(values, fields, format)), portalOpened)

                case CommandComplete(tag) =>
                    val affected = CommandTag.parseAffectedCount(tag)
                    // Consume the ReadyForQuery that follows CommandComplete in the extended protocol.
                    ReadyForQueryDrain.run(channel, onParameterStatus, onNotification).map(_ => ExecuteOutcome.Rows(acc, affected))

                case EmptyQueryResponse =>
                    // Consume the ReadyForQuery that follows EmptyQueryResponse.
                    ReadyForQueryDrain.run(channel, onParameterStatus, onNotification).map(_ => ExecuteOutcome.Rows(acc, 0L))

                case _: ReadyForQuery =>
                    // Should not arrive here if CommandComplete was handled properly; accept as end-of-cycle.
                    ExecuteOutcome.Rows(acc, 0L)

                case ErrorResponse(errorFields) =>
                    // Matched here rather than left to ReadLoopSideband, which always aborts: the caller needs
                    // this error paired with whether the portal had opened. The drain is the same one that
                    // branch would have run.
                    val ex = ServerErrors.mkServerError(errorFields, sqlText, paramCount, Present(pid))
                    ReadyForQueryDrain.run(channel, onParameterStatus, onNotification)
                        .map(_ => ExecuteOutcome.Failed(ex, portalOpened))

                case msg =>
                    ReadLoopSideband.handle(
                        msg,
                        channel,
                        sqlText,
                        paramCount,
                        pid,
                        "Execute",
                        "DataRow / CommandComplete / EmptyQueryResponse / PortalSuspended / ReadyForQuery",
                        onParameterStatus,
                        onNotification
                    )(loop(acc, portalOpened))
            }

        loop(Chunk.empty, portalOpened = false)
    end readExecuteResponses

    /** Deterministic cache key: first 16 hex chars of the SHA-256 hash of the SQL text plus the parameter OIDs.
      *
      * The OIDs are included because two queries with identical SQL but different param-OID declarations produce different prepared
      * statements at the server (the OIDs are baked into the Parse). Using a short prefix is safe for cache purposes (collisions map to a
      * wrong PreparedStmt being fetched, which is caught by the server at Bind time). 16 hex chars (8 bytes) provide ample collision
      * resistance for typical prepared-statement sets.
      */
    def cacheKey(sql: String, paramOids: Chunk[Int]): String =
        val sb = new StringBuilder
        val _  = sb.append(sql)
        if paramOids.nonEmpty then
            val _ = sb.append('|')
            val _ = sb.append(paramOids.mkString(","))
        val digest = PureHash.sha256(sb.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        digest.take(8).map(b => f"${b & 0xff}%02x").mkString
    end cacheKey

end ExtendedQueryExchange
