package kyo.internal

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import scala.annotation.tailrec

// --- Stateless binary codec -----------------------------------------------------------------

/** Stateless binary codec: header, record frame (with CRC), batch-commit terminator, metadata (a
  * MsgPack map of tag-keyed `MetadataValue` nodes), and the injective streamId percent-encoding. No
  * open handles, no implicit position; all operations work on `SegmentStore.Handle` via explicit
  * byte positions or on in-memory arrays. `private[kyo]` so codec unit tests can drive it in
  * isolation; out of public surface.
  *
  * Uses the shared pure [[CRC32]] on every platform; `java.util.zip.CRC32` is not referenced here.
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

    // --- header -------------------------------------------------------------------------------

    // Returns Present(detail) if the 5-byte header is missing/malformed/unknown-version, else Absent.
    def validateHeader(handle: SegmentStore.Handle)(using AllowUnsafe): Maybe[String] =
        val arr = handle.readAt(0L, HeaderSize)
        if arr.length < HeaderSize then Present(s"segment header truncated (${arr.length} bytes)")
        else
            val m = java.util.Arrays.copyOfRange(arr, 0, 4)
            val v = arr(4)
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
        frame.putInt(crcPos, (crc.value & 0xffffffffL).toInt)
        arr
    end encodeRecord

    def recordSize(env: EventEnvelope): Long =
        val idB = env.id.value.getBytes(Utf8)
        val tpB = env.eventType.value.getBytes(Utf8)
        val md  = encodeMetadata(env.metadata)
        val pl  = env.payload.toArray
        // 8 = frame prefix (length(4) + crc(4)); 8 = StreamOffset; every variable field carries its
        // own 4-byte length prefix. Equals encodeRecord's bodyLen + 8 exactly.
        8L + 8 + (4 + idB.length) + (4 + tpB.length) + (4 + md.length) + (4 + pl.length)
    end recordSize

    // Reads one record at position pos; verifies CRC. Result.Failure carries the corruption detail,
    // Result.Success the decoded record.
    def decodeRecordAt(handle: SegmentStore.Handle, pos: Long)(using AllowUnsafe): Result[String, DecodedRecord] =
        val head = handle.readAt(pos, 8)
        if head.length < 8 then Result.fail("record header truncated")
        else
            val headBuf = ByteBuffer.wrap(head)
            val bodyLen = headBuf.getInt()
            val crcExp  = headBuf.getInt()
            // Bound the body length before allocating: a negative length (a flipped high bit) or one
            // that runs past EOF is corruption, not a record. Without this guard a corrupt length
            // drives ByteBuffer.allocate into IllegalArgumentException or OutOfMemoryError, which would
            // escape the modeled JournalCorruptedError channel as an unhandled panic.
            val maxBody = handle.size() - pos - 8L
            if bodyLen < 0 || bodyLen.toLong > maxBody then Result.fail(s"record length out of range at byte $pos")
            else
                val bodyArr = handle.readAt(pos + 8L, bodyLen)
                if bodyArr.length < bodyLen then Result.fail("record body truncated")
                else
                    val crc = new CRC32(); crc.update(bodyArr, 0, bodyLen)
                    if (crc.value & 0xffffffffL).toInt != crcExp then Result.fail(s"record CRC mismatch at byte $pos")
                    else
                        val body     = ByteBuffer.wrap(bodyArr)
                        val offset   = body.getLong()
                        val eventId  = getLpStr(body)
                        val eventTp  = getLpStr(body)
                        val metadata = getLpBytes(body)
                        val payload  = getLpBytes(body)
                        Result.succeed(DecodedRecord(offset, eventId, eventTp, metadata, payload, bodyLen))
                    end if
                end if
            end if
        end if
    end decodeRecordAt

    // --- batch-commit terminator --------------------------------------------------------------

    def encodeTerminator(recordCount: Int): Array[Byte] =
        val buf = ByteBuffer.allocate(TerminatorSize)
        buf.put(CommitMagic); buf.putInt(recordCount)
        val crc = new CRC32(); crc.update(CommitMagic); crc.update(intBytes(recordCount))
        buf.putInt((crc.value & 0xffffffffL).toInt)
        buf.array()
    end encodeTerminator

    // True if a valid 12-byte batch terminator sits at buf(off): mirrors readTerminator over an
    // in-memory slab so a windowed recovery scan avoids a positional handle read per candidate
    // offset. The magic bytes are checked first and short-circuit, so CRC32 is only computed at a
    // genuine KJNC hit.
    def terminatorAt(buf: Array[Byte], off: Int): Boolean =
        if off < 0 || off + TerminatorSize > buf.length then false
        else
            var i = 0
            while i < 4 && buf(off + i) == CommitMagic(i) do i += 1
            if i < 4 then false
            else
                val crc = new CRC32()
                crc.update(CommitMagic)
                crc.update(buf, off + 4, 4) // the 4 record-count bytes, byte-identical to intBytes(count)
                val crcExp =
                    ((buf(off + 8) & 0xff) << 24) | ((buf(off + 9) & 0xff) << 16) | ((buf(off + 10) & 0xff) << 8) | (buf(off + 11) & 0xff)
                (crc.value & 0xffffffffL).toInt == crcExp
            end if
    end terminatorAt

    // Returns Present(recordCount) if a valid terminator sits at pos, else Absent (torn/absent).
    def readTerminator(handle: SegmentStore.Handle, pos: Long)(using AllowUnsafe): Maybe[Int] =
        val arr = handle.readAt(pos, TerminatorSize)
        if arr.length < TerminatorSize then Absent
        else
            val buf    = ByteBuffer.wrap(arr)
            val m      = new Array[Byte](4); buf.get(m)
            val count  = buf.getInt()
            val crcExp = buf.getInt()
            val crc    = new CRC32(); crc.update(CommitMagic); crc.update(intBytes(count))
            if java.util.Arrays.equals(m, CommitMagic) && (crc.value & 0xffffffffL).toInt == crcExp then Present(count)
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

    // --- private byte helpers -----------------------------------------------------------------

    private def putLp(buf: ByteBuffer, bytes: Array[Byte]): Unit =
        buf.putInt(bytes.length); discard(buf.put(bytes))
    private def getLpBytes(buf: ByteBuffer): Array[Byte] =
        val n = buf.getInt(); val a = new Array[Byte](n); buf.get(a); a
    private def getLpStr(buf: ByteBuffer): String = new String(getLpBytes(buf), Utf8)
    private def intBytes(v: Int): Array[Byte]     = ByteBuffer.allocate(4).putInt(v).array()

end SegmentCodec

// --- Data types -------------------------------------------------------------------------------

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
  * `baseOffset + i`. Handles live in the FileJournalCore registry, keyed by path string. `writePos`
  * is the append cursor for the active segment; `sealedSize` is the logical committed size at the
  * time the entry was last opened or sealed (equal to `writePos` at seal time).
  */
final private[kyo] case class SegmentEntry(
    baseOffset: Long,
    path: Path,
    recordPositions: Chunk[Long],
    writePos: Long,
    sealedSize: Long
)

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

// --- Shared orchestration class ---------------------------------------------------------------

/** The durable backend core. Never returned by name: callers see `Journal.Backend[Sync]`. The
  * critical section (offset check + frame + write + fsync + index publish) runs inside one
  * `Sync.Unsafe.defer` with no kyo suspension point, serialized across carrier threads by a CAS
  * claim in each stream's state. The `Frame` is captured once at construction.
  *
  * All platform I/O is delegated to the injected [[SegmentStore]]; no `FileChannel`, `toJava`, or
  * platform-specific types appear here. The [[CRC32]] used for every checksum is the shared pure
  * implementation, making segment bytes identical across JVM, Native, JS, and Wasm by construction.
  */
final private[kyo] class FileJournalCore(
    rootKey: String,
    streamsDir: Path,
    config: FileJournal.Config,
    store: SegmentStore,
    lock: SegmentStore.Lock
)(using frame: Frame, allow: AllowUnsafe) extends Journal.Backend[Sync]:

    import FileJournal.*
    import SegmentCodec.*

    // Recovery and indexing fail only with JournalStorageError or JournalCorruptedError; both mix
    // in all three per-op failure traits, so this union narrows cleanly into any Backend row
    // (append/read/streamInfo) at each forward site.
    private type IndexFailure = JournalStorageError | JournalCorruptedError

    // Registry of open segment handles, keyed by path string. Handles open lazily during
    // recovery/rotation and close on release. The per-stream state below references segments by
    // path; this map owns the handle lifecycle.
    private val handles: AtomicRef.Unsafe[Map[String, SegmentStore.Handle]] =
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
            // Unsafe: first-touch recovery may take the claim once; steady-state read is a
            // lock-free volatile read + positional handle reads.
            Sync.Unsafe.defer(readUnsafe(streamId, from, maxCount, log.unsafe)).map(Abort.get)
        }

    def streamInfo(streamId: StreamId): StreamInfo < (Sync & Abort[JournalStreamInfoFailure]) =
        Log.use { log =>
            // Unsafe: first-touch recovery may take the claim once; steady state reads published state.
            Sync.Unsafe.defer(streamInfoUnsafe(streamId, log.unsafe)).map(Abort.get)
        }

    private[kyo] def release()(using AllowUnsafe): Unit =
        discard(Result.catching[Throwable](lock.release()))
        handles.get().valuesIterator.foreach(h => discard(Result.catching[Throwable](h.close())))
        FileJournalCore.unregisterRoot(rootKey)
    end release

    // --- get-or-create stream cell ------------------------------------------------------------

    @tailrec
    private def cell(streamId: StreamId)(using AllowUnsafe): AtomicRef.Unsafe[StreamState] =
        val map = streams.get()
        Maybe.fromOption(map.get(streamId)) match
            case Present(existing) => existing
            case Absent =>
                val fresh = AtomicRef.Unsafe.init(StreamState.empty)
                if streams.compareAndSet(map, map.updated(streamId, fresh)) then fresh
                else cell(streamId)
        end match
    end cell

    // --- claim spin ---------------------------------------------------------------------------

    // Serializes same-stream appends: a waiter loops until the holder clears the writer flag. The
    // whole append critical section (write + fsync) runs inside one Sync.Unsafe.defer with no
    // suspension point, because the backend is a Journal.Backend[Sync] and its append row cannot
    // carry Async. A waiter yields the carrier on each spin so it does not peg a core while the
    // holder's blocking fsync runs.
    @tailrec
    private def claim(ref: AtomicRef.Unsafe[StreamState])(using AllowUnsafe): StreamState =
        val s = ref.get()
        if s.writer then
            yieldCurrentThread()
            claim(ref)                                             // a holder is running its section
        else if ref.compareAndSet(s, s.copy(writer = true)) then s // won the claim; caller must publish/clear
        else claim(ref)
        end if
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
            // priorSegments holds every segment that precedes the one being written. On a rotation
            // it already ends with the just-sealed segment; with no rotation it is the prior list.
            val (priorSegments, active) = rotateIfNeeded(streamId, s, firstOffset)
            val handle                  = handleFor(active.path)
            val startPos                = active.writePos
            val bytes                   = frameBatch(firstOffset, events) // N record frames + terminator
            val positions               = new Array[Long](events.length)
            var p                       = startPos
            var i                       = 0
            while i < events.length do
                positions(i) = p
                p += recordSize(events(i))
                i += 1
            end while
            handle.writeAt(startPos, bytes)
            if config.fsync == Fsync.Always then handle.sync() // fdatasync-or-stronger
            val newWritePos = startPos + bytes.length.toLong
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
            ref.set(published) // publish only after sync
            Result.succeed(AppendResult(
                streamId = streamId,
                firstOffset = StreamOffset.fromUnchecked(firstOffset),
                lastOffset = StreamOffset.fromUnchecked(lastOffset),
                streamInfo =
                    StreamInfo.Existing(StreamVersion.after(StreamOffset.fromUnchecked(lastOffset)), StreamOffset.fromUnchecked(lastOffset))
            ))
        catch
            case e: Exception =>
                Result.fail(JournalStorageError(s"Append to stream '${streamId.value}' failed", Present(e)))
    end writeBatch

    // Seal the active segment and start a new one when it has reached segmentSize (soft threshold).
    // Creates the next segment file named by the next offset, writes its 5-byte header.
    // Returns (segmentsBeforeActive, activeEntry). No rotation: the active segment stays in place
    // and the prior list is everything before it. Rotation: the filled segment is sealed and
    // appended to the prior list, and a fresh active segment is created.
    private def rotateIfNeeded(streamId: StreamId, s: StreamState, nextOffset: Long)(using
        AllowUnsafe
    )
        : (Chunk[SegmentEntry], SegmentEntry) =
        s.segments.lastMaybe match
            case Present(active) if active.writePos < config.segmentSize.toBytes =>
                (s.segments.dropRight(1), active)
            case Present(active) =>
                val sealed0 = active.copy(sealedSize = active.writePos)
                val fresh   = createSegment(streamId, nextOffset)
                (s.segments.dropRight(1) :+ sealed0, fresh)
            case Absent =>
                (Chunk.empty, createSegment(streamId, nextOffset))
    end rotateIfNeeded

    private def createSegment(streamId: StreamId, baseOffset: Long)(using AllowUnsafe): SegmentEntry =
        val dir     = streamDir(streamId)
        val existed = dir.unsafe.exists()
        discard(dir.unsafe.mkDir())
        // A newly created stream directory's entry is not durable until its parent (streams/) is
        // fsync'd. Sync it before the segment beneath it is acknowledged, on the fsync path only
        // and only when the directory did not already exist (a rotation reuses an existing, already-
        // synced stream directory).
        if config.fsync == Fsync.Always && !existed then store.syncDir(streamsDir)
        val segPath = dir / segmentName(baseOffset)
        val handle  = handleFor(segPath) // opens + registers
        handle.writeAt(0L, SegmentHeader)
        // store.syncDir after creating the segment so its directory link survives a crash; the
        // subsequent handle.sync() in writeBatch covers the segment's data.
        if config.fsync == Fsync.Always then store.syncDir(dir)
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

    // --- recovery ---------------------------------------------------------------------------------

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

    // Walks segments in base-offset order, validating header + records, grouping by batch
    // terminators. The trailing torn batch (no valid terminator after it) in the LAST segment
    // truncates and warns; any other CRC/framing failure is fatal Corrupted; an unknown version is
    // fatal. Returns the recovered, indexed StreamState.
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
                val handle   = handleFor(segPath)
                val size     = handle.size()
                validateHeader(handle) match
                    case Present(detail) => return Result.fail(JournalCorruptedError(Absent, detail))
                    case Absent          => ()
                val baseOffset = parseSegmentName(segPath)
                scanRecords(streamId, handle, size, isActive) match
                    case ScanResult.Corrupt(detail) =>
                        // `return` required: without it, Result.fail(...) is a non-unit value
                        // discarded in a while-loop body, failing under -Wconf:msg=discarded.*value:error.
                        return Result.fail(JournalCorruptedError(Present(streamId), s"$detail in segment '${segPath.unsafe.show}'"))
                    case ScanResult.Ok(positions, committedEnd, tornAt) =>
                        tornAt match
                            case Present(from) if isActive =>
                                handle.truncate(committedEnd)
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
            case e: Exception =>
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
                val seg    = segmentFor(s, off)
                val handle = handleFor(seg.path)
                val pos    = seg.recordPositions((off - seg.baseOffset).toInt)
                decodeRecordAt(handle, pos) match
                    case Result.Failure(detail) =>
                        return Result.fail(JournalCorruptedError(Present(streamId), detail))
                    case Result.Success(dec) =>
                        rebuild(streamId, dec) match
                            case Result.Failure(err) => return Result.fail(err)
                            case Result.Success(ev)  => discard(out += ev)
                end match
                off += 1L
            end while
            Result.succeed(Chunk.from(out.result()))
        catch
            case e: Exception =>
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

    // Opens a handle for `path` on first access, registering it in the handle map. On a CAS race,
    // closes the duplicate and returns the winning handle. Path string used as the registry key;
    // paths are derived from the root directory (which callers pass as absolute in practice).
    private def handleFor(path: Path)(using AllowUnsafe): SegmentStore.Handle =
        val key = path.unsafe.show
        Maybe.fromOption(handles.get().get(key)) match
            case Present(h) => h
            case Absent =>
                val h = store.open(path)
                @tailrec def register(): SegmentStore.Handle =
                    val m = handles.get()
                    Maybe.fromOption(m.get(key)) match
                        case Present(existing) => h.close(); existing
                        case Absent            => if handles.compareAndSet(m, m.updated(key, h)) then h else register()
                end register
                register()
        end match
    end handleFor

    private def streamDir(streamId: StreamId): Path = streamsDir / encodeStreamId(streamId)

    private def segmentFor(s: StreamState, off: Long): SegmentEntry =
        // Binary search the segment whose [base, base+len) contains off (segments offset-sorted).
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

    // Assembles N record frames followed by a batch-commit terminator into one contiguous byte array.
    private def frameBatch(firstOffset: Long, events: Chunk[EventEnvelope])(using AllowUnsafe): Array[Byte] =
        val recs  = new Array[Array[Byte]](events.length)
        var total = 0
        var i     = 0
        while i < events.length do
            val e  = events(i)
            val md = encodeMetadata(e.metadata)
            val r  = encodeRecord(firstOffset + i, e.id.value, e.eventType.value, md, e.payload.toArray)
            recs(i) = r; total += r.length; i += 1
        end while
        val term   = encodeTerminator(events.length)
        val result = new Array[Byte](total + term.length)
        var pos    = 0
        i = 0
        while i < recs.length do
            java.lang.System.arraycopy(recs(i), 0, result, pos, recs(i).length)
            pos += recs(i).length
            i += 1
        end while
        java.lang.System.arraycopy(term, 0, result, pos, term.length)
        result
    end frameBatch

    // Walk records from HeaderSize, grouping by terminators; returns committed record byte-
    // positions, the byte after the last valid terminator, and (for the active segment) where a
    // trailing torn batch began. A CRC/framing failure that is NOT the trailing region of the
    // active segment is Corrupt (mid-file damage).
    private def scanRecords(streamId: StreamId, handle: SegmentStore.Handle, size: Long, isActive: Boolean)(using AllowUnsafe): ScanResult =
        val committed    = Chunk.newBuilder[Long]
        var pos          = HeaderSize.toLong
        var committedEnd = HeaderSize.toLong
        var batchStart   = HeaderSize.toLong
        var pending      = Chunk.empty[Long] // record positions in the current, not-yet-terminated batch
        // Returns true if a valid batch-commit terminator exists at any byte offset from `from` to
        // the end of the segment. Used to distinguish a torn tail (no terminator follows the failure
        // point) from mid-file damage (a committed terminator survives after the bad record). The
        // region is read in overlapping buffered windows and scanned in memory rather than issuing a
        // 12-byte positional handle read per candidate offset. Windows overlap by TerminatorSize-1
        // so a terminator straddling a boundary is still found.
        def hasTerminatorAfter(from: Long): Boolean =
            val window  = 1 << 16
            val overlap = TerminatorSize.toLong - 1L
            var winPos  = from
            var found   = false
            while !found && winPos <= size - TerminatorSize.toLong do
                val len  = math.min(window.toLong, size - winPos).toInt
                val arr  = handle.readAt(winPos, len)
                val n    = arr.length
                var i    = 0
                val last = n - TerminatorSize
                while !found && i <= last do
                    if terminatorAt(arr, i) then found = true
                    i += 1
                end while
                winPos += (window.toLong - overlap)
            end while
            found
        end hasTerminatorAfter
        @tailrec def loop(): ScanResult =
            if pos >= size then
                // Reached EOF; any pending (unterminated) records are a torn tail.
                if pending.isEmpty then ScanResult.Ok(committed.result(), committedEnd, Absent)
                else if isActive then ScanResult.Ok(committed.result(), committedEnd, Present(batchStart))
                else ScanResult.Corrupt(s"unterminated batch at byte $batchStart in sealed segment")
            else
                readTerminator(handle, pos) match
                    case Present(count) if count == pending.length =>
                        pending.foreach(p => discard(committed += p))
                        pos += TerminatorSize.toLong
                        committedEnd = pos
                        batchStart = pos
                        pending = Chunk.empty
                        loop()
                    case _ =>
                        decodeRecordAt(handle, pos) match
                            case Result.Success(dec) =>
                                pending = pending.append(pos)
                                pos += 8L + dec.bodyLen.toLong
                                loop()
                            case Result.Failure(detail) =>
                                // A decode failure in the active segment is a torn tail only when no
                                // valid terminator follows the failure point. A terminator surviving
                                // after the bad record means the damage is mid-file and must not be
                                // silently truncated away.
                                if isActive && !hasTerminatorAfter(pos + 1L) then
                                    ScanResult.Ok(committed.result(), committedEnd, Present(batchStart))
                                else ScanResult.Corrupt(detail)
        loop()
    end scanRecords

end FileJournalCore

// --- Companion (in-process registry + open factory) ------------------------------------------

private[kyo] object FileJournalCore:

    // Process-wide registry of canonical root paths currently held open. POSIX advisory locks
    // (fcntl) do not exclude the owning process from reacquiring the same lock, so in-process
    // exclusion must be enforced separately. This registry provides that layer on all platforms;
    // the platform lock still provides cross-process exclusion.
    private val heldRoots: AtomicReference[Set[String]] =
        new AtomicReference(Set.empty[String])

    @tailrec private[kyo] def registerRoot(key: String): Boolean =
        val snap = heldRoots.get()
        if snap.contains(key) then false
        else if heldRoots.compareAndSet(snap, snap + key) then true
        else registerRoot(key)
    end registerRoot

    @tailrec private[kyo] def unregisterRoot(key: String): Unit =
        val snap = heldRoots.get()
        if !heldRoots.compareAndSet(snap, snap - key) then unregisterRoot(key)

    /** Opens (or creates) a file-backed journal rooted at `dir`, acquiring the single-owner lock
      * via the supplied `SegmentStore`. Internal; the public entry point is the platform-specific
      * `Journal.Backend.file` extension.
      */
    private[kyo] def open(dir: Path, config: FileJournal.Config, store: SegmentStore)(using
        frame: Frame
    )
        : Journal.Backend[Sync] < (Sync & Scope & Abort[JournalStorageError]) =
        Scope.acquireRelease(
            // Unsafe: creates the root, streams/ via Path.Unsafe and acquires the platform lock via
            // the injected SegmentStore; raw platform I/O is the only path to locking.
            Sync.Unsafe.defer(Abort.get(FileJournalCore.acquire(dir, config, store)))
        )(backend =>
            // Unsafe: bridges the raw platform release calls (lock release, handle closes) into the
            // Sync tier for the Scope.acquireRelease finalization callback.
            Sync.Unsafe.defer(backend.release())
        )

    private def acquire(dir: Path, config: FileJournal.Config, store: SegmentStore)(using
        frame: Frame,
        allow: AllowUnsafe
    )
        : Result[JournalStorageError, FileJournalCore] =
        // Use show as the canonical in-process key. Callers are expected to pass absolute paths
        // (Path.tempDir returns absolute; production usage is absolute); relative paths with the
        // same last component but different cwd would collide, which matches the platform-lock's
        // own cwd-relative limitation.
        val rootKey    = dir.unsafe.show
        var registered = false
        try
            if dir.unsafe.exists() && !dir.unsafe.isDirectory() then
                Result.fail(JournalStorageError(s"Journal root '${dir.unsafe.show}' exists and is not a directory", Absent))
            else
                if !registerRoot(rootKey) then
                    return Result.fail(JournalStorageError(s"Journal root '${dir.unsafe.show}' is locked by this process", Absent))
                registered = true
                discard(dir.unsafe.mkDir())
                val streamsDir = dir / "streams"
                discard(streamsDir.unsafe.mkDir())
                store.acquireLock(dir) match
                    case Result.Failure(err) =>
                        unregisterRoot(rootKey)
                        Result.fail(err)
                    case Result.Success(acquiredLock) =>
                        Result.succeed(new FileJournalCore(rootKey, streamsDir, config, store, acquiredLock))
                end match
            end if
        catch
            case e: Exception =>
                if registered then unregisterRoot(rootKey)
                Result.fail(JournalStorageError(s"Failed to open journal root '${dir.unsafe.show}'", Present(e)))
        end try
    end acquire

end FileJournalCore
