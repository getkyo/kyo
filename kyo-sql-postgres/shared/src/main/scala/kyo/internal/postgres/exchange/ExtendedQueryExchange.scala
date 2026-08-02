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
                // Cache miss, parse + describe with a UNIQUE server-side statement name so a
                // re-Parse that follows an eviction (or expiry) can never collide with a still-live
                // `s_$key` on the server whose `Close 'S'` is queued in `pendingCloses` but not yet
                // flushed. The suffix comes from the per-connection monotonic counter.
                stmtCounter.incrementAndGet.flatMap { seq =>
                    val stmtName = s"s_${seq}_$key"
                    parseAndDescribe(channel, stmtName, sql, paramOids, pid, onParameterStatus, onNotification).flatMap { stmt =>
                        stmtCache.add(key, stmt).andThen(stmt)
                    }
                }
        }
    end prepareStmt

    /** Resolves the prepared statement (cache or wire) then binds and executes. */
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
        prepareStmt(channel, stmtCache, stmtCounter, sql, paramOidsOf(params), pid, onParameterStatus, onNotification).flatMap { stmt =>
            bindAndExecute(channel, stmt, params, pid, onParameterStatus, onNotification)
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
            _ <- channel.send(Parse(stmtName, sql, paramOids))(using parseM)
            _ <- channel.send(Describe('S'.toByte, stmtName))(using describeM)
            _ <- channel.send(kyo.internal.postgres.SyncMessage)(using syncM)
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
                        case Absent => Chunk.empty[Short]
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

    /** Sends Bind + Execute + Sync, reads responses, returns (rows, affectedCount). */
    private def bindAndExecute(
        channel: PostgresChannel,
        stmt: PreparedStmt,
        params: Chunk[BoundParam[?]],
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): (Chunk[SqlRow], Long) < (Async & Abort[SqlException]) =
        val paramFormats: Chunk[Short]            = params.map(_.encoder.format.code)
        val paramValues: Chunk[Maybe[Span[Byte]]] = params.map(_.encoded)
        val bindMsg = Bind(
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
    )(using Frame): (Chunk[SqlRow], Long) < (Async & Abort[SqlException]) =
        val fields: Chunk[FieldDescription] = stmt.rowDescription match
            case Absent      => Chunk.empty
            case Present(rd) => rd.fields

        def loop(acc: Chunk[SqlRow])(using Frame): (Chunk[SqlRow], Long) < (Async & Abort[SqlException]) =
            channel.receive.flatMap {
                case BindComplete =>
                    loop(acc)

                case DataRow(values) =>
                    val format = if stmt.resultFormats.nonEmpty then
                        Format.fromCode(stmt.resultFormats(0))
                    else Format.Text
                    loop(acc.appended(PostgresRowCodec.row(values, fields, format)))

                case CommandComplete(tag) =>
                    val affected = CommandTag.parseAffectedCount(tag)
                    // Consume the ReadyForQuery that follows CommandComplete in the extended protocol.
                    ReadyForQueryDrain.run(channel, onParameterStatus, onNotification).map(_ => (acc, affected))

                case EmptyQueryResponse =>
                    // Consume the ReadyForQuery that follows EmptyQueryResponse.
                    ReadyForQueryDrain.run(channel, onParameterStatus, onNotification).map(_ => (acc, 0L))

                case _: ReadyForQuery =>
                    // Should not arrive here if CommandComplete was handled properly; accept as end-of-cycle.
                    (acc, 0L)

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
                    )(loop(acc))
            }

        loop(Chunk.empty)
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
