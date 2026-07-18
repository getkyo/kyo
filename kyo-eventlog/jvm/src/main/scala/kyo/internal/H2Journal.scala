package kyo.internal

import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kyo.*

/** JVM-only [[Journal.Backend]] over a JDBC [[javax.sql.DataSource]] (H2 by default), implementing
  * the [[Journal]] SPI directly against one `events` table with no [[FileSystem]] involvement.
  * This is the db-shaped proof that [[Journal.Backend]] is general across file and database
  * storage: the four SPI methods each map to one SQL statement, and the optimistic-append
  * expected-version guard rides the table's own primary key rather than an in-process lock, so it
  * holds even across multiple journal instances or processes sharing the same database. Reached
  * publicly through the JVM-only `Journal.Backend.h2` extension method (`JournalBackendH2Support.scala`),
  * never directly.
  *
  * Schema, created if absent when [[open]] is called:
  * {{{
  * CREATE TABLE IF NOT EXISTS events (
  *   stream_id  VARCHAR NOT NULL,
  *   sequence   BIGINT NOT NULL,
  *   event_id   VARCHAR NOT NULL,
  *   event_type VARCHAR NOT NULL,
  *   metadata   VARBINARY NOT NULL,
  *   payload    VARBINARY NOT NULL,
  *   PRIMARY KEY (stream_id, sequence)
  * )
  * }}}
  *
  * `sequence` is the zero-based [[Event.StreamOffset]]; the primary key on `(stream_id, sequence)`
  * is what an [[ExpectedOffset.Exact]]/[[ExpectedOffset.NoStream]] conflict actually rides:
  * `append` first checks the expectation against a `SELECT MAX(sequence)` read, then batch-inserts
  * at the computed offsets, and if a concurrent writer already claimed one of those offsets the
  * insert fails with SQLState `23505` (unique violation), which is re-read as the authoritative
  * [[JournalConflictError]] instead of the precheck's now-stale view. Metadata is framed through
  * [[FileJournalCore.encodeMetadata]]/`decodeMetadata` (the identical version-byte framing the
  * file backends use) over an Ion Binary [[EventLogCodecs.MetadataCodec]], so the stored bytes
  * are self-describing independent of any caller schema.
  *
  * JDBC is a blocking API. Every operation borrows a fresh [[java.sql.Connection]] from
  * `dataSource` and returns it before the call completes: a [[javax.sql.DataSource]] is the JDBC
  * abstraction for exactly this per-unit-of-work borrow, so no connection is held (and no
  * in-process serialization is imposed) across operations, which is what lets the primary key
  * above be the real concurrency guard rather than an in-process lock. The borrow/use/return
  * sequence runs as one blocking unit bridged through [[Sync.Unsafe.defer]] wrapped in
  * [[Async.defer]], the identical pattern the jvm-native Async file store uses
  * (`kyo.offloadStore`/`offloadHandle` in `FileJournalBackend.scala`): `Async.defer` runs on the
  * current fiber's carrier thread, and the kyo scheduler's `BlockingMonitor` detects the blocked
  * carrier and compensates by growing the worker pool, so no dedicated thread pool or bounded
  * executor is needed.
  *
  * @see
  *   [[Journal.Backend]] for the SPI contract this implements directly
  * @see
  *   [[InMemoryJournal]] for the in-process, non-durable `Backend[Sync]` sibling
  */
private[kyo] object H2Journal:

    private val metadataCodec = EventLogCodecs.MetadataCodec(IonBinary())

    private val createTableSql =
        """CREATE TABLE IF NOT EXISTS events (
          |  stream_id  VARCHAR NOT NULL,
          |  sequence   BIGINT NOT NULL,
          |  event_id   VARCHAR NOT NULL,
          |  event_type VARCHAR NOT NULL,
          |  metadata   VARBINARY NOT NULL,
          |  payload    VARBINARY NOT NULL,
          |  PRIMARY KEY (stream_id, sequence)
          |)""".stripMargin

    /** Opens an H2-backed (or any JDBC [[DataSource]]-backed) journal, creating the `events` table
      * if it does not already exist. Scope-managed: a schema-bootstrap connection is borrowed to
      * run the create statement and validate `dataSource` is reachable, then released when the
      * enclosing Scope closes (for an H2 in-memory `DataSource`, keeping this one connection open
      * for the backend's lifetime is also what keeps the in-memory database alive without relying
      * on a `DB_CLOSE_DELAY` connection-string tweak); every append/read/streamInfo call borrows
      * and returns its own connection independently.
      */
    def open(dataSource: DataSource)(using Frame): Journal.Backend[Async] < (Async & Scope & Abort[JournalStorageError]) =
        Scope.acquireRelease(
            // Unsafe: borrows a JDBC connection and runs the schema bootstrap DDL on it; a JDBC
            // failure is converted to a typed JournalStorageError rather than escaping as a panic.
            Async.defer(Sync.Unsafe.defer(Abort.get(bootstrapBlocking(dataSource))))
        )(conn =>
            // Unsafe: closes the schema-bootstrap connection at Scope exit, best-effort.
            Async.defer(Sync.Unsafe.defer(discard(Result.catching[SQLException](conn.close()))))
        ).map(_ => new H2Journal(dataSource))

    private def bootstrapBlocking(dataSource: DataSource)(using Frame): Result[JournalStorageError, Connection] =
        var conn: Connection = null
        try
            conn = dataSource.getConnection()
            val stmt = conn.createStatement()
            try discard(stmt.execute(createTableSql))
            finally stmt.close()
            Result.succeed(conn)
        catch
            case e: SQLException =>
                if conn != null then discard(Result.catching[SQLException](conn.close()))
                Result.fail(JournalStorageError("Cannot open H2 journal", Present(e)))
        end try
    end bootstrapBlocking
end H2Journal

final private class H2Journal(dataSource: DataSource)(using Frame) extends Journal.Backend[Async]:
    import H2Journal.metadataCodec

    private val insertSql =
        "INSERT INTO events (stream_id, sequence, event_id, event_type, metadata, payload) VALUES (?, ?, ?, ?, ?, ?)"
    private val selectMaxSequenceSql = "SELECT MAX(sequence) FROM events WHERE stream_id = ?"
    private val selectRangeSql =
        "SELECT sequence, event_id, event_type, metadata, payload FROM events WHERE stream_id = ? AND sequence >= ? ORDER BY sequence LIMIT ?"

    def append(
        streamId: Event.StreamId,
        expected: ExpectedOffset,
        events: Chunk[Event.Pending]
    ): AppendResult < (Async & Abort[JournalAppendFailure]) =
        if events.isEmpty then Abort.fail(JournalEmptyAppendError())
        else
            // Unsafe: the whole append (borrow connection, transaction, batch insert or
            // constraint-violation recovery, release) is one blocking JDBC unit.
            Async.defer(Sync.Unsafe.defer(Abort.get(appendBlocking(streamId, expected, events))))

    def read(
        streamId: Event.StreamId,
        from: Event.StreamOffset,
        maxCount: Int
    ): Chunk[Event.Committed] < (Async & Abort[JournalReadFailure]) =
        if maxCount <= 0 then Chunk.empty
        else
            // Unsafe: the whole read (borrow connection, ranged select, release) is one blocking
            // JDBC unit.
            Async.defer(Sync.Unsafe.defer(Abort.get(readBlocking(streamId, from, maxCount))))

    def streamInfo(streamId: Event.StreamId): StreamInfo < (Async & Abort[JournalStreamInfoFailure]) =
        // Unsafe: the whole streamInfo lookup (borrow connection, aggregate select, release) is
        // one blocking JDBC unit.
        Async.defer(Sync.Unsafe.defer(Abort.get(streamInfoBlocking(streamId))))

    private def appendBlocking(
        streamId: Event.StreamId,
        expected: ExpectedOffset,
        events: Chunk[Event.Pending]
    ): Result[JournalAppendFailure, AppendResult] =
        var conn: Connection = null
        try
            conn = dataSource.getConnection()
            conn.setAutoCommit(false)
            insertIfExpected(conn, streamId, expected, events)
        catch
            case e: SQLException =>
                Result.fail(JournalStorageError(s"H2 append failed for stream '${streamId.value}'", Present(e)))
        finally
            if conn != null then discard(Result.catching[SQLException](conn.close()))
        end try
    end appendBlocking

    private def insertIfExpected(
        conn: Connection,
        streamId: Event.StreamId,
        expected: ExpectedOffset,
        events: Chunk[Event.Pending]
    ): Result[JournalAppendFailure, AppendResult] =
        val current = currentInfo(conn, streamId)
        if !matches(expected, current) then
            conn.rollback()
            Result.fail(JournalConflictError(streamId, expected, current))
        else
            val firstValue = nextSequence(current)
            try
                insertBatch(conn, streamId, firstValue, events)
                conn.commit()
                val firstOffset = Event.StreamOffset.fromUnchecked(firstValue)
                val lastOffset  = Event.StreamOffset.fromUnchecked(firstValue + events.length.toLong - 1L)
                Result.succeed(AppendResult(
                    streamId = streamId,
                    firstOffset = firstOffset,
                    lastOffset = lastOffset,
                    streamInfo = StreamInfo.Existing(Event.StreamVersion.after(lastOffset), lastOffset)
                ))
            catch
                case e: SQLException if isUniqueViolation(e) =>
                    conn.rollback()
                    Result.fail(JournalConflictError(streamId, expected, currentInfo(conn, streamId)))
            end try
        end if
    end insertIfExpected

    private def insertBatch(conn: Connection, streamId: Event.StreamId, firstValue: Long, events: Chunk[Event.Pending]): Unit =
        val ps = conn.prepareStatement(insertSql)
        try
            events.zipWithIndex.foreach { (event, index) =>
                ps.setString(1, streamId.value)
                ps.setLong(2, firstValue + index.toLong)
                ps.setString(3, event.id.value)
                ps.setString(4, event.eventType.value)
                ps.setBytes(5, FileJournalCore.encodeMetadata(metadataCodec, event.metadata))
                ps.setBytes(6, event.payload.toArray)
                ps.addBatch()
            }
            discard(ps.executeBatch())
        finally ps.close()
        end try
    end insertBatch

    private def readBlocking(
        streamId: Event.StreamId,
        from: Event.StreamOffset,
        maxCount: Int
    ): Result[JournalReadFailure, Chunk[Event.Committed]] =
        var conn: Connection = null
        try
            conn = dataSource.getConnection()
            selectRange(conn, streamId, from, maxCount)
        catch
            case e: SQLException =>
                Result.fail(JournalStorageError(s"H2 read failed for stream '${streamId.value}'", Present(e)))
        finally
            if conn != null then discard(Result.catching[SQLException](conn.close()))
        end try
    end readBlocking

    private def selectRange(
        conn: Connection,
        streamId: Event.StreamId,
        from: Event.StreamOffset,
        maxCount: Int
    ): Result[JournalReadFailure, Chunk[Event.Committed]] =
        val ps = conn.prepareStatement(selectRangeSql)
        try
            ps.setString(1, streamId.value)
            ps.setLong(2, from.value)
            ps.setInt(3, maxCount)
            val rs = ps.executeQuery()
            try
                val builder                            = Chunk.newBuilder[Event.Committed]
                var failure: Maybe[JournalReadFailure] = Absent
                while failure.isEmpty && rs.next() do
                    val sequence      = rs.getLong(1)
                    val eventId       = rs.getString(2)
                    val eventType     = rs.getString(3)
                    val metadataBytes = rs.getBytes(4)
                    val payloadBytes  = rs.getBytes(5)
                    FileJournalCore.decodeMetadata(metadataCodec, metadataBytes) match
                        case Result.Failure(err) =>
                            failure = Present(JournalCorruptedError(Present(streamId), err.getMessage))
                        case Result.Success(metadata) =>
                            builder += Event.Committed(
                                streamId = streamId,
                                offset = Event.StreamOffset.fromUnchecked(sequence),
                                id = Event.Id.fromUnchecked(eventId),
                                eventType = Event.Type.fromUnchecked(eventType),
                                payload = Span.from(payloadBytes),
                                metadata = metadata
                            )
                    end match
                end while
                failure match
                    case Present(err) => Result.fail(err)
                    case Absent       => Result.succeed(builder.result())
            finally rs.close()
            end try
        finally ps.close()
        end try
    end selectRange

    private def streamInfoBlocking(streamId: Event.StreamId): Result[JournalStreamInfoFailure, StreamInfo] =
        var conn: Connection = null
        try
            conn = dataSource.getConnection()
            Result.succeed(currentInfo(conn, streamId))
        catch
            case e: SQLException =>
                Result.fail(JournalStorageError(s"H2 streamInfo failed for stream '${streamId.value}'", Present(e)))
        finally
            if conn != null then discard(Result.catching[SQLException](conn.close()))
        end try
    end streamInfoBlocking

    private def currentInfo(conn: Connection, streamId: Event.StreamId): StreamInfo =
        val ps = conn.prepareStatement(selectMaxSequenceSql)
        try
            ps.setString(1, streamId.value)
            val rs = ps.executeQuery()
            try
                if rs.next() then
                    val maxSequence = rs.getLong(1)
                    if rs.wasNull() then StreamInfo.Absent
                    else
                        val lastOffset = Event.StreamOffset.fromUnchecked(maxSequence)
                        StreamInfo.Existing(Event.StreamVersion.after(lastOffset), lastOffset)
                    end if
                else StreamInfo.Absent
            finally rs.close()
            end try
        finally ps.close()
        end try
    end currentInfo

    private def matches(expected: ExpectedOffset, actual: StreamInfo): Boolean =
        expected match
            case ExpectedOffset.Any      => true
            case ExpectedOffset.NoStream => actual == StreamInfo.Absent
            case ExpectedOffset.Exact(offset) =>
                actual match
                    case StreamInfo.Existing(_, lastOffset) => lastOffset == offset
                    case StreamInfo.Absent                  => false

    private def nextSequence(info: StreamInfo): Long =
        info match
            case StreamInfo.Absent                  => 0L
            case StreamInfo.Existing(_, lastOffset) => lastOffset.value + 1L

    // H2 reports SQLState "23505" for a unique-constraint violation, the sole signal
    // distinguishing a genuine optimistic-append conflict (a concurrent writer claimed the same
    // (stream_id, sequence) pair between this call's precheck and its insert) from any other
    // storage failure.
    private def isUniqueViolation(e: SQLException): Boolean = e.getSQLState == "23505"
end H2Journal
