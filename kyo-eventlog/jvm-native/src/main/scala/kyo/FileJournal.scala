package kyo

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import scala.annotation.tailrec

/** Durability and rotation knobs for a file-backed [[Journal.Backend]].
  *
  * A `Config` is passed to [[Journal.Backend.file]]. The defaults ([[Config.default]]) are the
  * production settings: every acknowledged append is flushed to stable storage, and segments
  * rotate at 64 MiB. Override `fsync` only in tests; see the field note.
  *
  * @param fsync
  *   flush each acknowledged append to stable storage before returning. Defaults to `true`.
  *   Setting it `false` trades durability for throughput and is TEST-ONLY: the crash-survival
  *   guarantee does not hold when it is `false`.
  * @param segmentSize
  *   soft rotation threshold in bytes; a new segment starts once the active segment reaches
  *   this size. Defaults to 64 MiB. A single record larger than the threshold still writes,
  *   into its own segment.
  * @see
  *   [[Journal.Backend.file]] for the constructor that consumes this config
  */
object FileJournal:

    final case class Config(
        fsync: Boolean = true,
        segmentSize: Long = 64L * 1024L * 1024L // 64 MiB = 67108864
    ) derives CanEqual

    object Config:
        val default: Config = Config()
    end Config

    /** Opens (or creates) a file-backed journal rooted at `dir`, acquiring the single-owner LOCK.
      * Internal; the public entry point is the `Journal.Backend.file` extension below.
      */
    private[kyo] def open(dir: Path, config: Config)(using
        frame: Frame
    )
        : Journal.Backend[Sync] < (Sync & Scope & Abort[JournalStorageError]) =
        Scope.acquireRelease(
            // Unsafe: creates the root, streams/ and LOCK via Path.Unsafe, opens the LOCK
            // FileChannel, and acquires the advisory FileLock; a raw FileChannel is the only
            // path to tryLock() (Path.Unsafe exposes no locking).
            Sync.Unsafe.defer(Abort.get(FileJournal.acquire(dir, config)))
        )(backend =>
            // Unsafe: releases the FileLock and closes every open segment channel then the LOCK
            // channel, on Scope finalization.
            Sync.Unsafe.defer(backend.release())
        )

    private def acquire(dir: Path, config: Config)(using
        frame: Frame,
        allow: AllowUnsafe
    )
        : Result[JournalStorageError, FileJournal] =
        try
            if dir.unsafe.exists() && !dir.unsafe.isDirectory() then
                Result.fail(JournalStorageError(s"Journal root '${dir.unsafe.show}' exists and is not a directory", Absent))
            else
                discard(dir.unsafe.mkDir())
                val streamsDir = dir / "streams"
                discard(streamsDir.unsafe.mkDir())
                val lockPath = dir / "LOCK"
                val lockChannel = FileChannel.open(
                    lockPath.toJava,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
                )
                val lock =
                    try lockChannel.tryLock()
                    catch
                        case e: OverlappingFileLockException =>
                            lockChannel.close()
                            return Result.fail(JournalStorageError(s"Journal root '${dir.unsafe.show}' is locked", Present(e)))
                        case e: IOException =>
                            lockChannel.close()
                            throw e // re-thrown; the outer catch handles it
                if lock == null then
                    lockChannel.close()
                    Result.fail(JournalStorageError(s"Journal root '${dir.unsafe.show}' is locked by another owner", Absent))
                else
                    Result.succeed(new FileJournal(streamsDir, config, lockChannel, lock))
                end if
            end if
        catch
            case e: IOException =>
                Result.fail(JournalStorageError(s"Failed to open journal root '${dir.unsafe.show}'", Present(e)))
    end acquire
end FileJournal

/** Discoverable constructor on the `Backend` companion, realized as an extension on `Journal.Backend.type`.
  * Available on JVM and Native only (jvm-native tree).
  *
  * @see [[FileJournal.Config]] for the durability and rotation knobs
  */
extension (backend: Journal.Backend.type)
    def file(dir: Path, config: FileJournal.Config = FileJournal.Config.default)(using
        Frame
    )
        : Journal.Backend[Sync] < (Sync & Scope & Abort[JournalStorageError]) =
        FileJournal.open(dir, config)
end extension

/** The durable backend. Never returned by name: callers see `Journal.Backend[Sync]`. The
  * critical section (offset check + frame + write + fsync + index publish) runs inside one
  * `Sync.Unsafe.defer` with no kyo suspension point, serialized across carrier threads by a CAS
  * claim in each stream's state. The `Frame` is captured once at construction.
  */
final private class FileJournal(
    streamsDir: Path,
    config: FileJournal.Config,
    lockChannel: FileChannel,
    lock: FileLock
)(using frame: Frame, allow: AllowUnsafe) extends Journal.Backend[Sync]:

    import FileJournal.*
    import SegmentCodec.*

    // Recovery and indexing fail only with JournalStorageError or JournalCorruptedError; both mix in
    // all three per-op failure traits, so this union narrows cleanly into any
    // Backend row (append/read/streamInfo) at each forward site, and never widens the declared E back
    // to the base JournalError.
    private type IndexFailure = JournalStorageError | JournalCorruptedError

    // Registry of open segment channels, keyed by absolute segment-file path. Channels open lazily
    // during recovery/rotation and close on release. The per-stream state below references segments
    // by path; this map owns the channel lifecycle.
    // `allow` is the class constructor's AllowUnsafe (threaded from FileJournal.acquire, which runs under
    // Sync.Unsafe.defer); both registry fields initialize under it, so no AllowUnsafe.embrace.danger appears.
    private val channels: AtomicRef.Unsafe[Map[String, FileChannel]] =
        AtomicRef.Unsafe.init(Map.empty)

    // StreamId -> its mutable state cell. get-or-create by CAS.
    private val streams: AtomicRef.Unsafe[Map[StreamId, AtomicRef.Unsafe[StreamState]]] =
        AtomicRef.Unsafe.init(Map.empty)

    def append(
        streamId: StreamId,
        expected: ExpectedOffset,
        events: Chunk[EventEnvelope]
    ): AppendResult < (Sync & Abort[JournalAppendFailure]) =
        if events.isEmpty then Abort.fail(JournalEmptyAppendError())
        else
            Log.use { log =>
                // Unsafe: whole append critical section (recover-if-needed, offset check, frame,
                // positional write, fsync, index publish) under one defer with no suspension, so a
                // carrier runs it to completion; cross-thread races serialize on the CAS claim.
                Sync.Unsafe.defer(appendUnsafe(streamId, expected, events, log.unsafe)).map(Abort.get)
            }

    def read(
        streamId: StreamId,
        from: StreamOffset,
        maxCount: Int
    ): Chunk[RecordedEvent] < (Sync & Abort[JournalReadFailure]) =
        Log.use { log =>
            // Unsafe: first-touch recovery may take the claim once; steady-state read is a lock-free
            // volatile read + positional channel reads.
            Sync.Unsafe.defer(readUnsafe(streamId, from, maxCount, log.unsafe)).map(Abort.get)
        }

    def streamInfo(streamId: StreamId): StreamInfo < (Sync & Abort[JournalStreamInfoFailure]) =
        Log.use { log =>
            // Unsafe: first-touch recovery may take the claim once; steady state reads published state.
            Sync.Unsafe.defer(streamInfoUnsafe(streamId, log.unsafe)).map(Abort.get)
        }

    private[kyo] def release()(using AllowUnsafe): Unit =
        discard(Result.catching[Throwable](lock.release()))
        channels.get().valuesIterator.foreach(ch => discard(Result.catching[Throwable](ch.close())))
        discard(Result.catching[Throwable](lockChannel.close()))
    end release

    // --- get-or-create stream cell ------------------------------------------------------------

    @tailrec
    private def cell(streamId: StreamId)(using AllowUnsafe): AtomicRef.Unsafe[StreamState] =
        val map = streams.get()
        map.get(streamId) match
            case Some(existing) => existing
            case None =>
                val fresh = AtomicRef.Unsafe.init(StreamState.empty)
                if streams.compareAndSet(map, map.updated(streamId, fresh)) then fresh
                else cell(streamId)
        end match
    end cell

    // --- claim spin ---------------------------------------------------------------------------

    @tailrec
    private def claim(ref: AtomicRef.Unsafe[StreamState])(using AllowUnsafe): StreamState =
        val s = ref.get()
        if s.writer then claim(ref)                                // spin: a holder is running its section
        else if ref.compareAndSet(s, s.copy(writer = true)) then s // won the claim; caller must publish/clear
        else claim(ref)
    end claim

    // --- append critical section --------------------------------------------------------------

    private def appendUnsafe(
        streamId: StreamId,
        expected: ExpectedOffset,
        events: Chunk[EventEnvelope],
        log: Log.Unsafe
    )(using AllowUnsafe): Result[JournalAppendFailure, AppendResult] =
        val ref             = cell(streamId)
        val claimed         = claim(ref) // may spin; holds the writer flag
        var alreadyReleased = false
        try
            ensureIndexed(streamId, ref, claimed, log) match
                case Result.Failure(err) => Result.fail(err)
                case Result.Success(indexed) =>
                    val info = infoOf(indexed)
                    if !matches(expected, info) then
                        Result.fail(JournalConflictError(streamId, expected, info))
                    else
                        writeBatch(streamId, ref, indexed, events) match
                            case r @ Result.Success(_) => alreadyReleased = true; r
                            case r                     => r
                    end if
        finally
            // On failure paths only: writeBatch already published the new state with writer=false
            // on success, so clearing the claim here would race against a concurrent thread that
            // won the CAS between writeBatch's ref.set and this finally's ref.get.
            if !alreadyReleased then
                val cur = ref.get()
                if cur.writer then discard(ref.compareAndSet(cur, cur.copy(writer = false)))
        end try
    end appendUnsafe

    private def writeBatch(
        streamId: StreamId,
        ref: AtomicRef.Unsafe[StreamState],
        s: StreamState,
        events: Chunk[EventEnvelope]
    )(using AllowUnsafe): Result[JournalAppendFailure, AppendResult] =
        try
            val firstOffset = s.lastOffset + 1L
            // priorSegments holds every segment that precedes the one being written. On a rotation it already
            // ends with the just-sealed segment, so publishing priorSegments :+ updatedActive never drops it;
            // with no rotation it is the in-place prior list.
            val (priorSegments, active) = rotateIfNeeded(streamId, s, firstOffset)
            val channel                 = channelFor(active.path)
            val startPos                = active.writePos
            val buffer                  = frameBatch(firstOffset, events) // N record frames + terminator
            val positions               = new Array[Long](events.length)
            var p                       = startPos
            var i                       = 0
            while i < events.length do
                positions(i) = p
                p += recordSize(events(i))
                i += 1
            end while
            discard(channel.write(buffer, startPos))
            if config.fsync then discard(channel.force(false)) // fdatasync (JVM) / full fsync (Native)
            val newWritePos = startPos + buffer.limit().toLong
            val lastOffset  = s.lastOffset + events.length.toLong
            val updatedActive = active.copy(
                writePos = newWritePos,
                recordPositions = active.recordPositions ++ Chunk.from(positions)
            )
            val published = s.copy(
                segments = priorSegments :+ updatedActive,
                lastOffset = lastOffset,
                writer = false
            )
            ref.set(published) // publish only after force
            Result.succeed(AppendResult(
                streamId = streamId,
                firstOffset = StreamOffset.fromUnchecked(firstOffset),
                lastOffset = StreamOffset.fromUnchecked(lastOffset),
                streamInfo =
                    StreamInfo.Existing(StreamVersion.after(StreamOffset.fromUnchecked(lastOffset)), StreamOffset.fromUnchecked(lastOffset))
            ))
        catch
            case e: IOException =>
                Result.fail(JournalStorageError(s"Append to stream '${streamId.value}' failed", Present(e)))
    end writeBatch

    // Seal the active segment and start a new one when it has reached segmentSize (soft threshold).
    // Creates the next segment file named by the next offset, writes its 5-byte header.
    // Returns (segmentsBeforeActive, activeEntry). No rotation: the active segment stays in place and the
    // prior list is everything before it. Rotation: the filled segment is sealed and appended to the prior
    // list, and a fresh active segment is created. writeBatch then publishes priorSegments :+ updatedActive,
    // so the sealed segment is never dropped from the index.
    private def rotateIfNeeded(streamId: StreamId, s: StreamState, nextOffset: Long)(using
        AllowUnsafe
    )
        : (Chunk[SegmentEntry], SegmentEntry) =
        s.segments.lastMaybe match
            case Present(active) if active.writePos < config.segmentSize =>
                (s.segments.dropRight(1), active)
            case Present(active) =>
                val sealed0 = active.copy(sealedSize = active.writePos)
                val fresh   = createSegment(streamId, nextOffset)
                (s.segments.dropRight(1) :+ sealed0, fresh)
            case Absent =>
                (Chunk.empty, createSegment(streamId, nextOffset))
    end rotateIfNeeded

    private def createSegment(streamId: StreamId, baseOffset: Long)(using AllowUnsafe): SegmentEntry =
        val dir = streamDir(streamId)
        discard(dir.unsafe.mkDir())
        val segPath = dir / segmentName(baseOffset)
        val channel = channelFor(segPath.toJava) // opens + registers
        val header  = ByteBuffer.wrap(SegmentHeader)
        discard(channel.write(header, 0L))
        SegmentEntry(baseOffset, segPath, Chunk.empty[Long], HeaderSize.toLong, HeaderSize.toLong)
    end createSegment

    // --- read path ---------------------------------------------------------------------------

    private def readUnsafe(
        streamId: StreamId,
        from: StreamOffset,
        maxCount: Int,
        log: Log.Unsafe
    )(using AllowUnsafe): Result[JournalReadFailure, Chunk[RecordedEvent]] =
        val ref = cell(streamId)
        ensureFirstTouch(streamId, ref, log) match
            case Result.Failure(err) => Result.fail(err)
            case Result.Success(s) =>
                if maxCount <= 0 || from.value > s.lastOffset then Result.succeed(Chunk.empty)
                else readRange(streamId, s, from.value, math.min(from.value + maxCount.toLong - 1L, s.lastOffset))
        end match
    end readUnsafe

    private def streamInfoUnsafe(streamId: StreamId, log: Log.Unsafe)(using
        AllowUnsafe
    )
        : Result[JournalStreamInfoFailure, StreamInfo] =
        val ref = cell(streamId)
        ensureFirstTouch(streamId, ref, log).map(infoOf)
    end streamInfoUnsafe

    // --- indexing / recovery entry points -----------------------------------------------------

    // Read path first-touch: recover under a one-shot claim if not yet indexed, then release it.
    private def ensureFirstTouch(streamId: StreamId, ref: AtomicRef.Unsafe[StreamState], log: Log.Unsafe)(using
        AllowUnsafe
    )
        : Result[IndexFailure, StreamState] =
        if ref.get().indexed then Result.succeed(ref.get())
        else
            val claimed = claim(ref)
            try ensureIndexed(streamId, ref, claimed, log)
            finally
                val cur = ref.get()
                if cur.writer then discard(ref.compareAndSet(cur, cur.copy(writer = false)))
            end try
    end ensureFirstTouch

    // Runs recovery once for a claimed cell; publishes the recovered state (indexed=true, writer=true
    // retained because the caller still holds the claim in the append path; ensureFirstTouch clears it).
    private def ensureIndexed(
        streamId: StreamId,
        ref: AtomicRef.Unsafe[StreamState],
        claimed: StreamState,
        log: Log.Unsafe
    )(using AllowUnsafe): Result[IndexFailure, StreamState] =
        if claimed.indexed then Result.succeed(claimed)
        else
            recover(streamId, log) match
                case Result.Failure(err) => Result.fail(err)
                case Result.Success(recovered) =>
                    val published = recovered.copy(writer = true) // keep the claim for the append caller
                    ref.set(published)
                    Result.succeed(published)
    end ensureIndexed

    // --- recovery --------------------------------------------------------------------------------

    private def recover(streamId: StreamId, log: Log.Unsafe)(using AllowUnsafe): Result[IndexFailure, StreamState] =
        val dir = streamDir(streamId)
        if !dir.unsafe.exists() then Result.succeed(StreamState.emptyIndexed)
        else
            val segFiles = dir.unsafe.list() match
                case Result.Success(paths) => paths.filter(_.unsafe.show.endsWith(".seg")).sortBy(_.unsafe.show)
                case Result.Failure(e) =>
                    return Result.fail(JournalStorageError(s"Cannot list segments for '${streamId.value}'", Present(e)))
            if segFiles.isEmpty then Result.succeed(StreamState.emptyIndexed)
            else scanSegments(streamId, segFiles, log)
        end if
    end recover

    // Walks segments in base-offset order, validating header + records, grouping by batch terminators.
    // The trailing torn batch (no valid terminator after it) in the LAST segment truncates and warns;
    // any other CRC/framing failure is fatal Corrupted; an unknown version is fatal. Returns the
    // recovered, indexed StreamState.
    private def scanSegments(streamId: StreamId, segFiles: Chunk[Path], log: Log.Unsafe)(using
        AllowUnsafe
    )
        : Result[IndexFailure, StreamState] =
        try
            var entries    = Chunk.empty[SegmentEntry]
            var lastOffset = -1L
            var idx        = 0
            while idx < segFiles.length do
                val segPath  = segFiles(idx)
                val isActive = idx == segFiles.length - 1
                val channel  = channelFor(segPath.toJava)
                val size     = channel.size()
                validateHeader(channel) match
                    case Present(detail) => return Result.fail(JournalCorruptedError(Absent, detail)) // header-level failure
                    case Absent          => ()
                val baseOffset = parseSegmentName(segPath)
                scanRecords(streamId, channel, size, isActive) match
                    case ScanResult.Corrupt(detail) =>
                        // `return` is required: without it, Result.fail(...) is a non-unit value
                        // discarded in a while-loop body, failing under -Wconf:msg=discarded.*value:error.
                        return Result.fail(JournalCorruptedError(Present(streamId), s"$detail in segment '${segPath.unsafe.show}'"))
                    case ScanResult.Ok(positions, committedEnd, tornAt) =>
                        tornAt match
                            case Present(from) if isActive =>
                                // truncate returns the channel; discard the result
                                discard(channel.truncate(committedEnd))
                                log.warn(
                                    s"FileJournal recovered stream '${streamId.value}': truncated torn tail of segment '${segPath.unsafe.show}' from byte $from to $committedEnd"
                                )(using frame, summon[AllowUnsafe])
                            case _ => ()
                        end match
                        entries =
                            entries :+ SegmentEntry(baseOffset, segPath, positions, if isActive then committedEnd else size, committedEnd)
                        lastOffset = baseOffset + positions.size.toLong - 1L
                        idx += 1
                end match
            end while
            Result.succeed(StreamState(entries, lastOffset, indexed = true, writer = false))
        catch
            case e: IOException =>
                Result.fail(JournalStorageError(s"Recovery of stream '${streamId.value}' failed", Present(e)))
    end scanSegments

    // --- shared helpers -----------------------------------------------------------------------

    private def readRange(streamId: StreamId, s: StreamState, fromOff: Long, toOff: Long)(using
        AllowUnsafe
    )
        : Result[JournalReadFailure, Chunk[RecordedEvent]] =
        try
            val out = Chunk.newBuilder[RecordedEvent]
            var off = fromOff
            while off <= toOff do
                val seg     = segmentFor(s, off)
                val channel = channelFor(seg.path.toJava)
                val pos     = seg.recordPositions((off - seg.baseOffset).toInt)
                decodeRecordAt(channel, pos) match
                    case Result.Failure(detail) =>
                        return Result.fail(JournalCorruptedError(Present(streamId), detail)) // mid-file corruption
                    case Result.Success(dec) =>
                        rebuild(streamId, dec) match
                            case Result.Failure(err) => return Result.fail(err)
                            case Result.Success(ev)  => discard(out += ev)
                end match
                off += 1L
            end while
            Result.succeed(Chunk.from(out.result()))
        catch
            case e: IOException =>
                Result.fail(JournalStorageError(s"Read of stream '${streamId.value}' failed", Present(e)))
    end readRange

    private def rebuild(streamId: StreamId, dec: DecodedRecord)(using AllowUnsafe): Result[JournalReadFailure, RecordedEvent] =
        val built =
            for
                eid <- EventId(dec.eventId)
                etp <- EventType(dec.eventType)
                md  <- decodeMetadata(dec.metadata)
            yield RecordedEvent(
                streamId = streamId,
                offset = StreamOffset.fromUnchecked(dec.offset),
                eventId = eid,
                eventType = etp,
                payload = Span.from(dec.payload),
                metadata = md
            )
        built match
            case Result.Success(ev) => Result.succeed(ev)
            case Result.Failure(_)  => Result.fail(JournalCorruptedError(Present(streamId), "record fields failed to reconstruct"))
    end rebuild

    private def channelFor(jpath: java.nio.file.Path)(using AllowUnsafe): FileChannel =
        val key = jpath.toAbsolutePath.toString
        channels.get().get(key) match
            case Some(ch) => ch
            case None =>
                val ch = FileChannel.open(jpath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
                @tailrec def register(): FileChannel =
                    val m = channels.get()
                    m.get(key) match
                        case Some(existing) => ch.close(); existing
                        case None           => if channels.compareAndSet(m, m.updated(key, ch)) then ch else register()
                end register
                register()
        end match
    end channelFor

    private def channelFor(path: Path)(using AllowUnsafe): FileChannel = channelFor(path.toJava)

    private def streamDir(streamId: StreamId): Path = streamsDir / encodeStreamId(streamId)

    private def segmentFor(s: StreamState, off: Long): SegmentEntry =
        // binary search the segment whose [base, base+len) contains off (segments offset-sorted)
        @tailrec def go(lo: Int, hi: Int): SegmentEntry =
            val mid = (lo + hi) >>> 1
            val e   = s.segments(mid)
            if off < e.baseOffset then go(lo, mid - 1)
            else if off >= e.baseOffset + e.recordPositions.size.toLong then go(mid + 1, hi)
            else e
            end if
        end go
        go(0, s.segments.length - 1)
    end segmentFor

    private def infoOf(s: StreamState): StreamInfo =
        if s.lastOffset < 0L then StreamInfo.Absent
        else
            val last = StreamOffset.fromUnchecked(s.lastOffset)
            StreamInfo.Existing(StreamVersion.after(last), last)

    private def matches(expected: ExpectedOffset, actual: StreamInfo): Boolean =
        expected match
            case ExpectedOffset.Any      => true
            case ExpectedOffset.NoStream => actual == StreamInfo.Absent
            case ExpectedOffset.Exact(offset) =>
                actual match
                    case StreamInfo.Existing(_, lastOffset) => lastOffset == offset
                    case StreamInfo.Absent                  => false

    // --- batch framing + per-segment record walk (class members: they thread config/channels) --------

    private def frameBatch(firstOffset: Long, events: Chunk[EventEnvelope])(using AllowUnsafe): ByteBuffer =
        val recs  = new Array[Array[Byte]](events.length)
        var total = 0
        var i     = 0
        while i < events.length do
            val e  = events(i)
            val md = SegmentCodec.encodeMetadata(e.metadata)
            val r  = SegmentCodec.encodeRecord(firstOffset + i, e.id.value, e.eventType.value, md, e.payload.toArray)
            recs(i) = r; total += r.length; i += 1
        end while
        val term = SegmentCodec.encodeTerminator(events.length)
        val buf  = ByteBuffer.allocate(total + term.length)
        i = 0;
        while i < recs.length do
            discard(buf.put(recs(i))); i += 1
        discard(buf.put(term)); discard(buf.flip()); buf
    end frameBatch

    // Walk records from HeaderSize, grouping by terminators; returns committed record byte-positions,
    // the byte after the last valid terminator, and (for the active segment) where a trailing torn
    // batch began. A CRC/framing failure that is NOT the trailing region of the active segment is
    // Corrupt (mid-file damage).
    private def scanRecords(streamId: StreamId, channel: FileChannel, size: Long, isActive: Boolean)(using AllowUnsafe): ScanResult =
        val committed    = Chunk.newBuilder[Long]
        var pos          = HeaderSize.toLong
        var committedEnd = HeaderSize.toLong
        var batchStart   = HeaderSize.toLong
        var pending      = List.empty[Long] // record positions in the current, not-yet-terminated batch
        // Returns true if a valid batch-commit terminator exists at any byte offset from `from`
        // to the end of the segment. Used to distinguish a torn tail (no terminator follows the
        // failure point) from mid-file damage (a committed terminator survives after the bad record).
        def hasTerminatorAfter(from: Long): Boolean =
            var p     = from
            var found = false
            while p <= size - TerminatorSize.toLong && !found do
                SegmentCodec.readTerminator(channel, p) match
                    case Present(_) => found = true
                    case Absent     => ()
                p += 1L
            end while
            found
        end hasTerminatorAfter
        @tailrec def loop(): ScanResult =
            if pos >= size then
                // reached EOF; any pending (unterminated) records are a torn tail
                if pending.isEmpty then ScanResult.Ok(committed.result(), committedEnd, Absent)
                else if isActive then ScanResult.Ok(committed.result(), committedEnd, Present(batchStart))
                else ScanResult.Corrupt(s"unterminated batch at byte $batchStart in sealed segment")
            else
                SegmentCodec.readTerminator(channel, pos) match
                    case Present(count) if count == pending.length =>
                        pending.reverse.foreach(p => discard(committed += p))
                        pos += SegmentCodec.TerminatorSize.toLong
                        committedEnd = pos
                        batchStart = pos
                        pending = Nil
                        loop()
                    case _ =>
                        SegmentCodec.decodeRecordAt(channel, pos) match
                            case Result.Success(dec) =>
                                pending = pos :: pending
                                pos += 8L + dec.bodyLen.toLong
                                loop()
                            case Result.Failure(detail) =>
                                // A decode failure in the active segment is a torn tail only when no valid
                                // terminator follows the failure point. A terminator surviving after the bad
                                // record means the damage is mid-file and must not be silently truncated away.
                                if isActive && !hasTerminatorAfter(pos + 1L) then
                                    ScanResult.Ok(committed.result(), committedEnd, Present(batchStart))
                                else ScanResult.Corrupt(detail)
        loop()
    end scanRecords
end FileJournal

/** Immutable per-stream state cell contents. `indexed` gates lazy recovery; `writer` is the CAS
  * claim. `lastOffset == -1` means the stream is absent.
  */
final private[kyo] case class StreamState(
    segments: Chunk[SegmentEntry],
    lastOffset: Long,
    indexed: Boolean,
    writer: Boolean
)
private[kyo] object StreamState:
    val empty: StreamState        = StreamState(Chunk.empty, -1L, indexed = false, writer = false)
    val emptyIndexed: StreamState = StreamState(Chunk.empty, -1L, indexed = true, writer = false)

/** One segment file's index entry. `recordPositions(i)` is the byte offset of the record for
  * `baseOffset + i`. Channels live in the FileJournal registry, keyed by path.
  * `writePos` is the append cursor for the active segment; `sealedSize` is the logical committed
  * size at the time the entry was last opened or sealed (equal to `writePos` at seal time; used as
  * the future stored-index anchor).
  */
final private[kyo] case class SegmentEntry(
    baseOffset: Long,
    path: Path,
    recordPositions: Chunk[Long],
    writePos: Long,
    sealedSize: Long
)

/** Stateless binary codec: header, record frame (with CRC), batch-commit terminator, metadata
  * (a MsgPack map of tag-keyed `MetadataValue` nodes), and the injective streamId
  * percent-encoding. No open handles, no implicit position; all channel operations use explicit
  * byte positions. `private[kyo]` so the codec unit tests can drive it in isolation; out of
  * public surface.
  */
private[kyo] object SegmentCodec:

    val Magic: Array[Byte]         = Array[Byte]('K', 'J', 'N', '1') // 0x4B 0x4A 0x4E 0x31
    val Version: Byte              = 0x01
    val HeaderSize: Int            = 5
    val SegmentHeader: Array[Byte] = Magic :+ Version
    val CommitMagic: Array[Byte]   = Array[Byte]('K', 'J', 'N', 'C')
    val TerminatorSize: Int        = 12                              // KJNC(4) + recordCount(4) + crc(4)
    val MetadataVersion: Byte      = 0x01                            // 0x01 = tagged map of MetadataValue

    private val Utf8 = StandardCharsets.UTF_8

    // --- header --------------------------------------------------------------------------------

    // Returns Some(detail) if the 5-byte header is missing/malformed/unknown-version, else None.
    def validateHeader(channel: FileChannel): Maybe[String] =
        val buf = ByteBuffer.allocate(HeaderSize)
        val n   = channel.read(buf, 0L)
        if n < HeaderSize then Present(s"segment header truncated ($n bytes)")
        else
            buf.flip()
            val m = new Array[Byte](4); buf.get(m)
            val v = buf.get()
            if !java.util.Arrays.equals(m, Magic) then Present("segment magic is not KJN1")
            else if v != Version then Present(s"unknown segment format version 0x${(v & 0xff).toHexString}")
            else Absent
        end if
    end validateHeader

    // --- record frame -------------------------------------------------------------------------

    // Body = StreamOffset(8) | lp(eventId) | lp(eventType) | lp(metadata) | lp(payload); lp = len(4)+bytes.
    // Frame = length(4, = body length) | crc32(4, over body) | body. Consumes 8 + length bytes.
    def encodeRecord(offset: Long, eventId: String, eventType: String, metadata: Array[Byte], payload: Array[Byte]): Array[Byte] =
        val idB     = eventId.getBytes(Utf8)
        val tpB     = eventType.getBytes(Utf8)
        val bodyLen = 8 + (4 + idB.length) + (4 + tpB.length) + (4 + metadata.length) + (4 + payload.length)
        val frame   = ByteBuffer.allocate(8 + bodyLen)
        frame.putInt(bodyLen) // length field
        val crcPos = frame.position()
        frame.putInt(0) // crc placeholder
        val bodyStart = frame.position()
        frame.putLong(offset)
        putLp(frame, idB); putLp(frame, tpB); putLp(frame, metadata); putLp(frame, payload)
        val arr = frame.array()
        val crc = new CRC32(); crc.update(arr, bodyStart, bodyLen)
        frame.putInt(crcPos, (crc.getValue & 0xffffffffL).toInt)
        arr
    end encodeRecord

    def recordSize(env: EventEnvelope): Long =
        val idB = env.id.value.getBytes(Utf8)
        val tpB = env.eventType.value.getBytes(Utf8)
        val md  = encodeMetadata(env.metadata)
        val pl  = env.payload.toArray
        // 8 = frame prefix (length(4) + crc(4)); 8 = StreamOffset; every variable field carries its own
        // 4-byte length prefix. Equals encodeRecord's bodyLen + 8 exactly.
        8L + 8 + (4 + idB.length) + (4 + tpB.length) + (4 + md.length) + (4 + pl.length)
    end recordSize

    // Reads one record at position pos; verifies CRC. Left = corruption detail, Right = decoded.
    def decodeRecordAt(channel: FileChannel, pos: Long): Result[String, DecodedRecord] =
        val head = ByteBuffer.allocate(8)
        if channel.read(head, pos) < 8 then Result.fail("record header truncated")
        else
            head.flip()
            val bodyLen = head.getInt()
            val crcExp  = head.getInt()
            val body    = ByteBuffer.allocate(bodyLen)
            if channel.read(body, pos + 8L) < bodyLen then Result.fail("record body truncated")
            else
                val bodyArr = body.array()
                val crc     = new CRC32(); crc.update(bodyArr, 0, bodyLen)
                if (crc.getValue & 0xffffffffL).toInt != crcExp then Result.fail(s"record CRC mismatch at byte $pos")
                else
                    body.flip()
                    val offset   = body.getLong()
                    val eventId  = getLpStr(body)
                    val eventTp  = getLpStr(body)
                    val metadata = getLpBytes(body)
                    val payload  = getLpBytes(body)
                    Result.succeed(DecodedRecord(offset, eventId, eventTp, metadata, payload, bodyLen))
                end if
            end if
        end if
    end decodeRecordAt

    // --- batch-commit terminator --------------------------------------------------------------

    def encodeTerminator(recordCount: Int): Array[Byte] =
        val buf = ByteBuffer.allocate(TerminatorSize)
        buf.put(CommitMagic); buf.putInt(recordCount)
        val crc = new CRC32(); crc.update(CommitMagic); crc.update(intBytes(recordCount))
        buf.putInt((crc.getValue & 0xffffffffL).toInt)
        buf.array()
    end encodeTerminator

    // Returns Some(recordCount) if a valid terminator sits at pos, else None (torn/absent).
    def readTerminator(channel: FileChannel, pos: Long): Maybe[Int] =
        val buf = ByteBuffer.allocate(TerminatorSize)
        if channel.read(buf, pos) < TerminatorSize then Absent
        else
            buf.flip()
            val m      = new Array[Byte](4); buf.get(m)
            val count  = buf.getInt()
            val crcExp = buf.getInt()
            val crc    = new CRC32(); crc.update(CommitMagic); crc.update(intBytes(count))
            if java.util.Arrays.equals(m, CommitMagic) && (crc.getValue & 0xffffffffL).toInt == crcExp then Present(count)
            else Absent
        end if
    end readTerminator

    // --- metadata ----------------------------------------------------------------------------

    def encodeMetadata(md: EventMetadata): Array[Byte] =
        val writer = MsgPack().newWriter()
        writer.mapStart(md.values.size)
        md.values.foreach((k, v) =>
            writer.field(k.value, 0); MetadataValue.write(writer, v)
        )
        writer.mapEnd()
        val body = writer.result().toArray
        val out  = new Array[Byte](1 + body.length)
        out(0) = MetadataVersion
        java.lang.System.arraycopy(body, 0, out, 1, body.length)
        out
    end encodeMetadata

    def decodeMetadata(bytes: Array[Byte])(using Frame): Result[JournalInvalidIdentifierError, EventMetadata] =
        if bytes.isEmpty then Result.succeed(EventMetadata.empty)
        else if bytes(0) != MetadataVersion then
            Result.fail(JournalInvalidIdentifierError("metadata encoding version", s"unknown byte 0x${(bytes(0) & 0xff).toHexString}"))
        else
            val payload = Span.from(java.util.Arrays.copyOfRange(bytes, 1, bytes.length))
            val reader  = MsgPack().newReader(payload)
            try
                discard(reader.objectStart())
                val rawPairs = Chunk.newBuilder[(String, MetadataValue)]
                while reader.hasNextField() do
                    val keyStr = reader.field()
                    val v      = MetadataValue.read(reader)
                    rawPairs += (keyStr -> v)
                end while
                reader.objectEnd()
                val pairs = rawPairs.result().map((k, v) => MetadataKey(k).map(mk => (mk, v)))
                Result.collect(pairs).map(ps => EventMetadata(ps.toMap))
            catch
                case e: DecodeException =>
                    Result.fail(JournalInvalidIdentifierError("metadata value tag", e.getMessage))
            end try
    end decodeMetadata

    // --- streamId percent-encoding (injective, filesystem-safe) ------------------------------

    def encodeStreamId(streamId: StreamId): String =
        val bytes = streamId.value.getBytes(Utf8)
        val sb    = new java.lang.StringBuilder(bytes.length)
        var i     = 0
        while i < bytes.length do
            val b = bytes(i) & 0xff
            val c = b.toChar
            if (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' then
                discard(sb.append(c))
            else
                discard(sb.append('%').append(f"$b%02X"))
            end if
            i += 1
        end while
        sb.toString
    end encodeStreamId

    def segmentName(baseOffset: Long): String = f"$baseOffset%020d.seg"

    def parseSegmentName(path: Path): Long =
        val name = path.parts.last
        name.substring(0, name.length - 4).toLong // strip ".seg"

    // --- private byte helpers ------------------------------------------------------------------

    private def putLp(buf: ByteBuffer, bytes: Array[Byte]): Unit =
        buf.putInt(bytes.length); discard(buf.put(bytes))
    private def getLpBytes(buf: ByteBuffer): Array[Byte] =
        val n = buf.getInt(); val a = new Array[Byte](n); buf.get(a); a
    private def getLpStr(buf: ByteBuffer): String = new String(getLpBytes(buf), Utf8)
    private def intBytes(v: Int): Array[Byte]     = ByteBuffer.allocate(4).putInt(v).array()
end SegmentCodec

/** Decoded record fields (still raw strings/bytes; the backend rebuilds the wire types). */
final private[kyo] case class DecodedRecord(
    offset: Long,
    eventId: String,
    eventType: String,
    metadata: Array[Byte],
    payload: Array[Byte],
    bodyLen: Int
)

/** Result of scanning one segment's records. `committedEnd` is the byte after the last valid
  * terminator; `tornAt` is the byte where the trailing torn batch began (if any).
  */
private[kyo] enum ScanResult:
    case Ok(positions: Chunk[Long], committedEnd: Long, tornAt: Maybe[Long])
    case Corrupt(detail: String)
