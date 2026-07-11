package kyo.internal

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import scala.annotation.tailrec

// --- SegmentCodec trait -----------------------------------------------------------------------

/** Format-dispatch seam for segment encoding. Implementations encode and decode event records
  * to and from segment files; the orchestration layer ([[FileJournalCore]]) is format-agnostic.
  * Every operation that reads from a handle is polymorphic in the handle's effect `S`, so the
  * same codec instance drives both the synchronous and the asynchronous backend.
  *
  * Two implementations exist: [[BinarySegmentCodec]] (the original `KJN1` binary format) and
  * [[kyo.internal.JsonlSegmentCodec]] (the one-JSON-object-per-line JSONL format). Both are
  * `private[kyo]`; neither appears in public surface.
  */
private[kyo] trait SegmentCodec:

    // --- identity ---

    /** File extension for segment files, including the leading dot (e.g. `".seg"`, `".jsonl"`). */
    def segmentExtension: String

    /** The binary file header written at position 0 when a segment is first created. An empty
      * array means no header (JSONL segments carry no file header).
      */
    def header: Array[Byte]

    // --- header ---

    /** Returns `Absent` if the segment header at position 0 is valid, or `Present(detail)` if
      * it is missing, malformed, or from an unknown version. Called once per segment during
      * recovery before any record scan.
      */
    def validateHeader[S](h: StoreSeam.Handle[S])(using Frame): Maybe[String] < S

    // --- write ---

    /** Encodes `events` (starting at `firstOffset`) plus a batch-commit marker into a single
      * contiguous byte array suitable for one positional write to the segment handle. The caller
      * writes the returned bytes and then calls [[extractPositions]] to build the record index.
      */
    def frameBatch(firstOffset: Long, events: Chunk[EventEnvelope]): Array[Byte]

    /** Per-record byte size for the binary format; used by the default [[extractPositions]]
      * implementation. For binary segments this is computed analytically from the field lengths;
      * for JSONL, [[extractPositions]] is overridden to scan the batch bytes for newlines, so
      * this method is not used for position computation in JSONL mode.
      */
    def recordSize(env: EventEnvelope): Long

    /** Extracts the per-record byte-start positions from the bytes returned by
      * [[frameBatch]]. The default implementation uses [[recordSize]] per event. JSONL overrides
      * this to scan for newline boundaries instead, because the JSONL line length depends on the
      * encoded offset value and cannot be determined analytically without encoding.
      */
    def extractPositions(
        firstOffset: Long,
        events: Chunk[EventEnvelope],
        batchBytes: Array[Byte],
        startPos: Long
    ): Array[Long] =
        val positions = new Array[Long](events.length)
        var p         = startPos
        var i         = 0
        while i < events.length do
            positions(i) = p
            p += recordSize(events(i))
            i += 1
        end while
        positions
    end extractPositions

    // --- scan ---

    /** Walks a segment from the beginning, grouping records by batch-commit markers. Returns a
      * [[ScanResult]] describing the committed record positions, the committed-end byte offset,
      * and any trailing torn batch. Called once per segment during first-touch recovery.
      */
    def scan[S](h: StoreSeam.Handle[S], size: Long, isActive: Boolean)(using Frame): ScanResult < S

    /** Reads the record (or commit line for JSONL) at byte position `pos` and verifies its
      * integrity (CRC32). Returns `Result.fail(detail)` on corruption, `Result.succeed` on a
      * valid record.
      */
    def decodeRecordAt[S](h: StoreSeam.Handle[S], pos: Long)(using Frame): Result[String, DecodedRecord] < S

    /** Returns true if a valid batch-commit marker exists anywhere in the segment between byte
      * `from` (inclusive) and `size` (exclusive). Used to distinguish a torn tail from mid-file
      * damage during scan.
      */
    def hasCommitAfter[S](h: StoreSeam.Handle[S], from: Long, size: Long)(using Frame): Boolean < S

    // --- shared concrete helpers (override if different) ---

    /** Segment file name for the given base offset: a 20-digit zero-padded decimal stem plus
      * [[segmentExtension]]. Both binary and JSONL use the same stem format.
      */
    final def segmentName(baseOffset: Long): String = f"$baseOffset%020d$segmentExtension"

    /** Parses the base offset from a segment path by stripping the last
      * `segmentExtension.length` characters from the filename stem.
      */
    final def parseSegmentName(path: Path): Long =
        val name = path.parts.last
        name.substring(0, name.length - segmentExtension.length).toLong

end SegmentCodec

// --- Binary segment codec (the original KJN1 format) -----------------------------------------

/** Stateless binary codec: header, record frame (with CRC), batch-commit terminator, metadata (a
  * MsgPack map of tag-keyed `MetadataValue` nodes), and the injective streamId percent-encoding. No
  * open handles, no implicit position; all operations work on [[StoreSeam.Handle]] via explicit
  * byte positions or on in-memory arrays. `private[kyo]` so codec unit tests can drive it in
  * isolation; out of public surface.
  *
  * Uses the shared pure [[CRC32]] on every platform; `java.util.zip.CRC32` is not referenced here.
  */
private[kyo] object BinarySegmentCodec extends SegmentCodec:

    val Magic: Array[Byte]         = Array[Byte]('K', 'J', 'N', '1') // 0x4B 0x4A 0x4E 0x31
    val Version: Byte              = 0x01
    val HeaderSize: Int            = 5
    val SegmentHeader: Array[Byte] = Magic :+ Version
    val CommitMagic: Array[Byte]   = Array[Byte]('K', 'J', 'N', 'C')
    val TerminatorSize: Int        = 12                              // KJNC(4) + recordCount(4) + crc(4)
    val MetadataVersion: Byte      = 0x01                            // 0x01 = tagged map of MetadataValue

    private val Utf8 = StandardCharsets.UTF_8

    def segmentExtension: String = ".seg"
    def header: Array[Byte]      = SegmentHeader

    // --- header -------------------------------------------------------------------------------

    // Returns Present(detail) if the 5-byte header is missing/malformed/unknown-version, else Absent.
    def validateHeader[S](handle: StoreSeam.Handle[S])(using Frame): Maybe[String] < S =
        handle.readAt(0L, HeaderSize).map { arr =>
            if arr.length < HeaderSize then Present(s"segment header truncated (${arr.length} bytes)")
            else
                val m = java.util.Arrays.copyOfRange(arr, 0, 4)
                val v = arr(4)
                if !java.util.Arrays.equals(m, Magic) then Present("segment magic is not KJN1")
                else if v != Version then Present(s"unknown segment format version 0x${(v & 0xff).toHexString}")
                else Absent
        }
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
    def decodeRecordAt[S](handle: StoreSeam.Handle[S], pos: Long)(using Frame): Result[String, DecodedRecord] < S =
        handle.readAt(pos, 8).map { head =>
            if head.length < 8 then Result.fail("record header truncated")
            else
                val headBuf = ByteBuffer.wrap(head)
                val bodyLen = headBuf.getInt()
                val crcExp  = headBuf.getInt()
                handle.size().map { sz =>
                    // Bound the body length before allocating: a negative length (a flipped high bit) or
                    // one that runs past EOF is corruption, not a record. Without this guard a corrupt
                    // length drives ByteBuffer.allocate into IllegalArgumentException or
                    // OutOfMemoryError, which would escape the modeled JournalCorruptedError channel as
                    // an unhandled panic.
                    val maxBody = sz - pos - 8L
                    if bodyLen < 0 || bodyLen.toLong > maxBody then Result.fail(s"record length out of range at byte $pos")
                    else
                        handle.readAt(pos + 8L, bodyLen).map { bodyArr =>
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
                        }
                    end if
                }
        }
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
    def readTerminator[S](handle: StoreSeam.Handle[S], pos: Long)(using Frame): Maybe[Int] < S =
        handle.readAt(pos, TerminatorSize).map { arr =>
            if arr.length < TerminatorSize then Absent
            else
                val buf    = ByteBuffer.wrap(arr)
                val m      = new Array[Byte](4); buf.get(m)
                val count  = buf.getInt()
                val crcExp = buf.getInt()
                val crc    = new CRC32(); crc.update(CommitMagic); crc.update(intBytes(count))
                if java.util.Arrays.equals(m, CommitMagic) && (crc.value & 0xffffffffL).toInt == crcExp then Present(count)
                else Absent
        }
    end readTerminator

    // --- segment record scan ------------------------------------------------------------------

    def scan[S](handle: StoreSeam.Handle[S], size: Long, isActive: Boolean)(using Frame): ScanResult < S =
        Loop(HeaderSize.toLong, HeaderSize.toLong, Chunk.empty[Long], Chunk.empty[Long]) {
            (pos, committedEnd, pending, committed) =>
                if pos >= size then
                    if pending.isEmpty then Loop.done(ScanResult.Ok(committed, committedEnd, Absent))
                    else if isActive then Loop.done(ScanResult.Ok(committed, committedEnd, Present(pending.head)))
                    else Loop.done(ScanResult.Corrupt(s"unterminated batch at byte ${pending.head} in sealed segment"))
                else
                    readTerminator(handle, pos).map {
                        case Present(count) if count == pending.length =>
                            val newCommitted = committed ++ pending
                            val newPos       = pos + TerminatorSize.toLong
                            Loop.continue(newPos, newPos, Chunk.empty[Long], newCommitted)
                        case _ =>
                            decodeRecordAt(handle, pos).map {
                                case Result.Success(dec) =>
                                    Loop.continue(pos + 8L + dec.bodyLen.toLong, committedEnd, pending.append(pos), committed)
                                case Result.Failure(detail) =>
                                    // A decode failure in the active segment is a torn tail only when no
                                    // valid terminator follows the failure point. A terminator surviving
                                    // after the bad record means the damage is mid-file and must not be
                                    // silently truncated away.
                                    if isActive then
                                        hasCommitAfter(handle, pos + 1L, size).map { hasMore =>
                                            if !hasMore then
                                                Loop.done(ScanResult.Ok(committed, committedEnd, Present(pending.headMaybe.getOrElse(pos))))
                                            else Loop.done(ScanResult.Corrupt(detail))
                                        }
                                    else Loop.done(ScanResult.Corrupt(detail))
                            }
                    }
        }
    end scan

    def hasCommitAfter[S](handle: StoreSeam.Handle[S], from: Long, size: Long)(using Frame): Boolean < S =
        // Returns true if a valid 12-byte batch terminator exists at any byte offset from `from` to
        // the end of the segment. The region is read in overlapping buffered windows and scanned in
        // memory rather than issuing a 12-byte positional handle read per candidate offset. Windows
        // overlap by TerminatorSize-1 so a terminator straddling a boundary is still found.
        val window  = 1 << 16
        val overlap = TerminatorSize.toLong - 1L
        Loop(from) { winPos =>
            if winPos > size - TerminatorSize.toLong then Loop.done(false)
            else
                val len = math.min(window.toLong, size - winPos).toInt
                handle.readAt(winPos, len).map { arr =>
                    if scanWindowForTerminator(arr) then Loop.done(true)
                    else Loop.continue(winPos + (window.toLong - overlap))
                }
        }
    end hasCommitAfter

    @tailrec private def scanWindowForTerminator(arr: Array[Byte], i: Int = 0): Boolean =
        val last = arr.length - TerminatorSize
        if i > last then false
        else if terminatorAt(arr, i) then true
        else scanWindowForTerminator(arr, i + 1)
    end scanWindowForTerminator

    // --- batch framing ------------------------------------------------------------------------

    def frameBatch(firstOffset: Long, events: Chunk[EventEnvelope]): Array[Byte] =
        // Assembles N record frames followed by a batch-commit terminator into one contiguous byte array.
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

    // --- private byte helpers -----------------------------------------------------------------

    private def putLp(buf: ByteBuffer, bytes: Array[Byte]): Unit =
        buf.putInt(bytes.length); discard(buf.put(bytes))
    private def getLpBytes(buf: ByteBuffer): Array[Byte] =
        val n = buf.getInt(); val a = new Array[Byte](n); buf.get(a); a
    private def getLpStr(buf: ByteBuffer): String = new String(getLpBytes(buf), Utf8)
    private def intBytes(v: Int): Array[Byte]     = ByteBuffer.allocate(4).putInt(v).array()

end BinarySegmentCodec

// --- Data types -------------------------------------------------------------------------------

/** Immutable per-stream state cell contents. `indexed` gates lazy recovery; `writer` is the Sync
  * CAS claim flag (unused, always `false`, when the backend is Async). `lastOffset` is the
  * highest WRITTEN offset; `durableOffset` is the highest offset confirmed durable (fsynced). The
  * two coincide on the Sync backend (publish happens only after fsync); on the Async
  * backend `lastOffset` can briefly run ahead of `durableOffset` while a group-commit flush is in
  * flight. The read path clamps to `durableOffset` so a caller never observes a written-but-not-
  * yet-durable record. `lastOffset == -1` means the stream is absent.
  */
final private[kyo] case class StreamState(
    segments: Chunk[SegmentEntry],
    lastOffset: Long,
    durableOffset: Long,
    indexed: Boolean,
    writer: Boolean
)
private[kyo] object StreamState:
    val empty: StreamState        = StreamState(Chunk.empty, -1L, -1L, indexed = false, writer = false)
    val emptyIndexed: StreamState = StreamState(Chunk.empty, -1L, -1L, indexed = true, writer = false)

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

// --- Claim seam: per-stream write serialization, one implementation per effect -----------------

/** Serializes same-stream writes. The Sync implementation is a bounded CAS spin; the Async
  * implementation uses a parked per-stream permit. [[holdThroughFlush]]
  * tells [[FileJournalCore]] whether the claim must stay held until durability is confirmed (Sync:
  * yes, held for the full write-fsync-publish sequence) or may be released right after the
  * positional write (Async: yes to concurrency, no to durability ordering; group commit needs same-
  * stream writes to pipeline past each other while a flush is in flight).
  */
private[kyo] trait ClaimSeam[S]:
    def holdThroughFlush: Boolean
    def acquire(streamId: StreamId, ref: AtomicRef.Unsafe[StreamState])(using AllowUnsafe, Frame): StreamState < S
    def release(streamId: StreamId, ref: AtomicRef.Unsafe[StreamState])(using AllowUnsafe, Frame): Unit < Sync
end ClaimSeam

private[kyo] object ClaimSeam:

    /** A per-stream CAS flag with a bounded spin. A waiter yields the carrier on each spin so it
      * does not peg a core while the holder's blocking fsync runs. `acquire` returns the POST-claim
      * snapshot (`writer = true`) rather than the pre-claim one: every downstream `.copy` that republishes this
      * stream's state (the write's positional publish, first-touch recovery) derives from this
      * value, so returning the pre-claim snapshot would republish `writer = false` on every such
      * copy and release the mutex early, before the actual `release` call, letting a spinning
      * contender start writing to the same segment while this holder is still mid-flush.
      */
    val sync: ClaimSeam[Sync] = new ClaimSeam[Sync]:
        def holdThroughFlush: Boolean = true

        @tailrec def acquire(streamId: StreamId, ref: AtomicRef.Unsafe[StreamState])(using AllowUnsafe, Frame): StreamState < Sync =
            val s = ref.get()
            if s.writer then
                yieldCurrentThread()
                acquire(streamId, ref)
            else
                val claimed = s.copy(writer = true)
                if ref.compareAndSet(s, claimed) then claimed
                else acquire(streamId, ref)
            end if
        end acquire

        def release(streamId: StreamId, ref: AtomicRef.Unsafe[StreamState])(using AllowUnsafe, Frame): Unit < Sync =
            val cur = ref.get()
            if cur.writer then discard(ref.compareAndSet(cur, cur.copy(writer = false)))
        end release
    end sync

    /** A fresh per-instance async claim: a lazily created, per-stream `Channel[Unit]` of capacity
      * one used as a mutex (preloaded with one token, so the first `take` succeeds immediately). A
      * blocked appender parks on `take`, suspending the fiber and freeing the carrier rather than
      * spinning; FIFO fairness comes from the channel's own wait queue.
      */
    def async()(using AllowUnsafe): ClaimSeam[Async] = new ClaimSeam[Async]:
        def holdThroughFlush: Boolean = false

        // Unsafe: bootstraps an empty in-process map; never touches platform I/O and is always safe.
        private val permits: AtomicRef.Unsafe[Map[StreamId, Channel[Unit]]] = AtomicRef.Unsafe.init(Map.empty)

        @tailrec private def permitFor(streamId: StreamId)(using AllowUnsafe, Frame): Channel[Unit] =
            val m = permits.get()
            Maybe.fromOption(m.get(streamId)) match
                case Present(ch) => ch
                case Absent =>
                    val fresh = Channel.Unsafe.init[Unit](1)
                    discard(fresh.offer(()))
                    val ch = fresh.safe
                    if permits.compareAndSet(m, m.updated(streamId, ch)) then ch else permitFor(streamId)
            end match
        end permitFor

        def acquire(streamId: StreamId, ref: AtomicRef.Unsafe[StreamState])(using AllowUnsafe, Frame): StreamState < Async =
            val permit = permitFor(streamId)
            Abort.run[Closed](permit.take).map:
                case Result.Success(_) => ref.get()
                case Result.Failure(closed) =>
                    throw new IllegalStateException(s"stream permit channel for '${streamId.value}' closed unexpectedly: $closed")
                case Result.Panic(e) => throw e
        end acquire

        def release(streamId: StreamId, ref: AtomicRef.Unsafe[StreamState])(using AllowUnsafe, Frame): Unit < Sync =
            val permit = permitFor(streamId)
            Abort.run[Closed](permit.offerDiscard(())).map:
                case Result.Success(_) => ()
                case Result.Failure(closed) =>
                    throw new IllegalStateException(s"stream permit channel for '${streamId.value}' closed unexpectedly: $closed")
                case Result.Panic(e) => throw e
        end release
    end async

end ClaimSeam

// --- Group commit: coalesces durability flushes across concurrent appenders to one handle ------

/** Coalesces `handle.sync()` calls that target the same segment handle. The first caller to reach
  * [[requestFlush]] while idle becomes the leader and calls `handle.sync()` directly; any caller
  * that arrives while a flush is already in progress waits for the NEXT round rather than assuming
  * it is covered by the one already underway (safety over opportunism: a write that lands after
  * the in-flight `sync()` call has already started is not guaranteed to be covered by it). When a
  * round completes, the leader immediately runs another round for whatever arrived during the
  * previous one, and keeps doing so until a round finds nothing new, at which point it returns to
  * idle. There is no timer: the coalescing window is exactly "whatever accumulated during the
  * previous flush".
  *
  * One coordinator instance is shared by a [[FileJournalCore]], keyed by the same path-string key
  * as the handle registry, so distinct streams (distinct handles) coalesce independently.
  */
final private[kyo] class GroupCommitCoordinator(using allow: AllowUnsafe):
    // Unsafe: bootstraps an empty in-process map; never touches platform I/O and is always safe.
    import GroupCommitCoordinator.State
    private val coordinators: AtomicRef.Unsafe[Map[String, AtomicRef.Unsafe[State]]] = AtomicRef.Unsafe.init(Map.empty)

    @tailrec private def stateFor(key: String)(using AllowUnsafe): AtomicRef.Unsafe[State] =
        val m = coordinators.get()
        Maybe.fromOption(m.get(key)) match
            case Present(ref) => ref
            case Absent =>
                val fresh = AtomicRef.Unsafe.init[State](State.Idle)
                if coordinators.compareAndSet(m, m.updated(key, fresh)) then fresh else stateFor(key)
        end match
    end stateFor

    def requestFlush(key: String, handle: StoreSeam.Handle[Async])(using AllowUnsafe, Frame): Unit < Async =
        join(stateFor(key), handle)

    @tailrec private def join(ref: AtomicRef.Unsafe[State], handle: StoreSeam.Handle[Async])(using AllowUnsafe, Frame): Unit < Async =
        ref.get() match
            case State.Idle =>
                if ref.compareAndSet(State.Idle, State.Flushing(Nil)) then runRound(ref, handle)
                else join(ref, handle)
            case cur @ State.Flushing(waiters) =>
                val signal = Channel.Unsafe.init[Unit](0)
                if ref.compareAndSet(cur, State.Flushing(signal.safe :: waiters)) then
                    Abort.run[Closed](signal.safe.take).map:
                        case Result.Success(_) => ()
                        case Result.Failure(closed) =>
                            throw new IllegalStateException(s"group commit signal closed unexpectedly: $closed")
                        case Result.Panic(e) => throw e
                else join(ref, handle)
                end if
        end match
    end join

    private def runRound(ref: AtomicRef.Unsafe[State], handle: StoreSeam.Handle[Async])(using AllowUnsafe, Frame): Unit < Async =
        handle.sync().map(_ => afterRound(ref, handle))

    @tailrec private def afterRound(ref: AtomicRef.Unsafe[State], handle: StoreSeam.Handle[Async])(using
        AllowUnsafe,
        Frame
    ): Unit < Async =
        ref.get() match
            case cur @ State.Flushing(Nil) =>
                if ref.compareAndSet(cur, State.Idle) then () else afterRound(ref, handle)
            case cur @ State.Flushing(waiters) =>
                if ref.compareAndSet(cur, State.Flushing(Nil)) then runRound(ref, handle).map(_ => releaseAll(waiters))
                else afterRound(ref, handle)
            case State.Idle =>
                throw new IllegalStateException("group commit coordinator observed an idle state during an active flush")
        end match
    end afterRound

    private def releaseAll(waiters: List[Channel[Unit]])(using AllowUnsafe, Frame): Unit < Async =
        Kyo.foreachDiscard(waiters) { w =>
            Abort.run[Closed](w.offerDiscard(())).map:
                case Result.Success(_) => ()
                case Result.Failure(closed) =>
                    throw new IllegalStateException(s"group commit signal closed unexpectedly: $closed")
                case Result.Panic(e) => throw e
        }

end GroupCommitCoordinator

private[kyo] object GroupCommitCoordinator:
    private enum State derives CanEqual:
        case Idle
        case Flushing(waiters: List[Channel[Unit]])
end GroupCommitCoordinator

// --- Flush strategy: how a batch's durability is confirmed, one implementation per effect ------

/** Confirms that the bytes written by one append are durable, then advances `ref`'s
  * `durableOffset` to `targetOffset`. The Sync implementation flushes inline; the
  * Async implementation routes through the shared [[GroupCommitCoordinator]].
  */
private[kyo] trait FlushStrategy[S]:
    def confirmDurable(
        handle: StoreSeam.Handle[S],
        key: String,
        ref: AtomicRef.Unsafe[StreamState],
        targetOffset: Long
    )(using AllowUnsafe, Frame): Unit < S
end FlushStrategy

private[kyo] object FlushStrategy:

    @tailrec private[internal] def bumpDurable(ref: AtomicRef.Unsafe[StreamState], targetOffset: Long)(using AllowUnsafe): Unit =
        val cur = ref.get()
        if cur.durableOffset >= targetOffset then ()
        else if ref.compareAndSet(cur, cur.copy(durableOffset = targetOffset)) then ()
        else bumpDurable(ref, targetOffset)
    end bumpDurable

    def inline(fsync: FileJournal.Fsync): FlushStrategy[Sync] = new FlushStrategy[Sync]:
        def confirmDurable(
            handle: StoreSeam.Handle[Sync],
            key: String,
            ref: AtomicRef.Unsafe[StreamState],
            targetOffset: Long
        )(using AllowUnsafe, Frame): Unit < Sync =
            (if fsync == FileJournal.Fsync.Always then handle.sync() else ((): Unit < Sync)).map { _ =>
                bumpDurable(ref, targetOffset)
            }
    end inline

    def groupCommit(fsync: FileJournal.Fsync, coordinator: GroupCommitCoordinator): FlushStrategy[Async] = new FlushStrategy[Async]:
        def confirmDurable(
            handle: StoreSeam.Handle[Async],
            key: String,
            ref: AtomicRef.Unsafe[StreamState],
            targetOffset: Long
        )(using AllowUnsafe, Frame): Unit < Async =
            (if fsync == FileJournal.Fsync.Always then coordinator.requestFlush(key, handle) else ((): Unit < Async)).map { _ =>
                bumpDurable(ref, targetOffset)
            }
    end groupCommit

end FlushStrategy

// --- Shared orchestration class ---------------------------------------------------------------

/** The durable backend core, generalized over the store's effect `S`. Never returned by name:
  * callers see `Journal.Backend[S]`. Recovery, the offset check, framing, and rotation math are
  * single-sourced and shared by every `S`; same-stream write serialization ([[ClaimSeam]]) and
  * durability confirmation ([[FlushStrategy]]) are the two points where the Sync and Async
  * backends intentionally diverge (Sync: bounded CAS spin, inline fsync, claim held
  * throughout; Async: parked permit, group-commit-coalesced fsync, claim released right after the
  * positional write so same-stream appends can pipeline past an in-flight flush). The `Frame` is
  * captured once at construction.
  *
  * All platform I/O is delegated to the injected [[StoreSeam]]; no `FileChannel`, `toJava`, or
  * platform-specific types appear here. The [[CRC32]] used for every checksum is the shared pure
  * implementation, making segment bytes identical across JVM, Native, JS, and Wasm by construction.
  * The segment encoding is delegated to the injected [[SegmentCodec]], which is selected by the
  * FORMAT marker at open time.
  */
final private[kyo] class FileJournalCore[S >: (Async & Abort[Throwable]) <: Sync](
    rootKey: String,
    streamsDir: Path,
    config: FileJournal.Config,
    seam: StoreSeam[S],
    lock: SegmentStore.Lock,
    codec: SegmentCodec,
    payloadCodec: EventPayloadCodec,
    claimSeam: ClaimSeam[S],
    flushStrategy: FlushStrategy[S]
)(using frame: Frame, allow: AllowUnsafe) extends Journal.Backend[S]:

    import FileJournal.*

    // Recovery and indexing fail only with JournalStorageError or JournalCorruptedError; both mix
    // in all three per-op failure traits, so this union narrows cleanly into any Backend row
    // (append/read/streamInfo) at each forward site.
    private type IndexFailure = JournalStorageError | JournalCorruptedError

    // Registry of open segment handles, keyed by path string. Handles open lazily during
    // recovery/rotation and close on release. The per-stream state below references segments by
    // path; this map owns the handle lifecycle.
    private val handles: AtomicRef.Unsafe[Map[String, StoreSeam.Handle[S]]] =
        AtomicRef.Unsafe.init(Map.empty)

    // StreamId -> its mutable state cell. get-or-create by CAS.
    private val streams: AtomicRef.Unsafe[Map[StreamId, AtomicRef.Unsafe[StreamState]]] =
        AtomicRef.Unsafe.init(Map.empty)

    def append(
        streamId: StreamId,
        expected: ExpectedOffset,
        events: Chunk[EventEnvelope]
    ): AppendResult < (S & Abort[JournalAppendFailure]) =
        if events.isEmpty then Abort.fail(JournalEmptyAppendError())
        else
            Log.use { log =>
                appendCriticalSection(streamId, expected, events, cell(streamId), log.unsafe).map(Abort.get)
            }

    def read(
        streamId: StreamId,
        from: StreamOffset,
        maxCount: Int
    ): Chunk[RecordedEvent] < (S & Abort[JournalReadFailure]) =
        Log.use { log =>
            readCriticalSection(streamId, from, maxCount, log.unsafe).map(Abort.get)
        }

    def streamInfo(streamId: StreamId): StreamInfo < (S & Abort[JournalStreamInfoFailure]) =
        Log.use { log =>
            streamInfoCriticalSection(streamId, log.unsafe).map(Abort.get)
        }

    private[kyo] def release()(using AllowUnsafe, Frame): Unit < S =
        discard(Result.catching[Throwable](lock.release()))
        Kyo.foreachDiscard(Chunk.from(handles.get().values)) { h =>
            Abort.run(Abort.catching[Throwable](h.close())).map(_ => ())
        }.map { _ =>
            FileJournalCore.unregisterRoot(rootKey)
        }
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

    // --- append critical section --------------------------------------------------------------

    private def appendCriticalSection(
        streamId: StreamId,
        expected: ExpectedOffset,
        events: Chunk[EventEnvelope],
        ref: AtomicRef.Unsafe[StreamState],
        log: Log.Unsafe
    )(using AllowUnsafe, Frame): Result[JournalAppendFailure, AppendResult] < S =
        claimSeam.acquire(streamId, ref).map { claimed =>
            if claimSeam.holdThroughFlush then appendHoldingClaim(streamId, expected, events, ref, claimed, log)
            else appendReleasingEarly(streamId, expected, events, ref, claimed, log)
        }

    // The Sync shape: the claim stays held until durability is confirmed (write, then fsync, then
    // publish, then release).
    private def appendHoldingClaim(
        streamId: StreamId,
        expected: ExpectedOffset,
        events: Chunk[EventEnvelope],
        ref: AtomicRef.Unsafe[StreamState],
        claimed: StreamState,
        log: Log.Unsafe
    )(using AllowUnsafe, Frame): Result[JournalAppendFailure, AppendResult] < S =
        Sync.ensure(claimSeam.release(streamId, ref)) {
            writeCriticalSection(streamId, expected, events, ref, claimed, log).map {
                case Result.Failure(err) => Result.fail(err)
                case Result.Success((handle, key, targetOffset, draft)) =>
                    flushStrategy.confirmDurable(handle, key, ref, targetOffset).map(_ => Result.succeed(draft))
                case Result.Panic(e) => throw e
            }
        }

    // The Async shape: the claim is released right after the positional write (before durability
    // is confirmed), so another same-stream appender can start its own write while this append's
    // flush is in flight, which is what gives group commit something to coalesce.
    private def appendReleasingEarly(
        streamId: StreamId,
        expected: ExpectedOffset,
        events: Chunk[EventEnvelope],
        ref: AtomicRef.Unsafe[StreamState],
        claimed: StreamState,
        log: Log.Unsafe
    )(using AllowUnsafe, Frame): Result[JournalAppendFailure, AppendResult] < S =
        Sync.ensure(claimSeam.release(streamId, ref)) {
            writeCriticalSection(streamId, expected, events, ref, claimed, log)
        }.map {
            case Result.Failure(err) => Result.fail(err)
            case Result.Success((handle, key, targetOffset, draft)) =>
                flushStrategy.confirmDurable(handle, key, ref, targetOffset).map(_ => Result.succeed(draft))
            case Result.Panic(e) => throw e
        }

    // Shared: recovery-if-needed, the expected-offset check, and the framed positional write.
    // Publishes the new WRITTEN state (lastOffset advanced) but leaves durableOffset untouched;
    // the caller's flush strategy advances durableOffset once the write is confirmed durable.
    private def writeCriticalSection(
        streamId: StreamId,
        expected: ExpectedOffset,
        events: Chunk[EventEnvelope],
        ref: AtomicRef.Unsafe[StreamState],
        claimed: StreamState,
        log: Log.Unsafe
    )(using AllowUnsafe, Frame): Result[JournalAppendFailure, (StoreSeam.Handle[S], String, Long, AppendResult)] < S =
        ensureIndexed(streamId, ref, claimed, log).map:
            case Result.Failure(err)     => Result.fail(err)
            case Result.Success(indexed) =>
                // The conflict check is atomic with the write because the per-stream claim (CAS spin
                // or parked permit) already serializes same-stream appenders: whichever appender holds
                // the claim always observes every prior appender's write. It must compare against
                // `lastOffset` (written), not `durableOffset` (fsynced): on the async backend the claim
                // is released right after the write, before its own durability is confirmed, so a
                // waiting appender can be granted the claim while the previous write is still flushing.
                val info = writtenInfoOf(indexed)
                if !matches(expected, info) then Result.fail(JournalConflictError(streamId, expected, info))
                else writeFramedBatch(streamId, ref, indexed, events)

    private def writeFramedBatch(
        streamId: StreamId,
        ref: AtomicRef.Unsafe[StreamState],
        s: StreamState,
        events: Chunk[EventEnvelope]
    )(using AllowUnsafe, Frame): Result[JournalAppendFailure, (StoreSeam.Handle[S], String, Long, AppendResult)] < S =
        catchStorageError[(StoreSeam.Handle[S], String, Long, AppendResult)](s"Append to stream '${streamId.value}' failed") {
            val firstOffset = s.lastOffset + 1L
            // priorSegments holds every segment that precedes the one being written. On a rotation
            // it already ends with the just-sealed segment; with no rotation it is the prior list.
            rotateIfNeeded(streamId, s, firstOffset).map {
                case (priorSegments, active) =>
                    val key = active.path.unsafe.show
                    handleFor(active.path).map { handle =>
                        val startPos  = active.writePos
                        val bytes     = codec.frameBatch(firstOffset, events)
                        val positions = codec.extractPositions(firstOffset, events, bytes, startPos)
                        handle.writeAt(startPos, bytes).map { _ =>
                            val newWritePos = startPos + bytes.length.toLong
                            val lastOffset  = s.lastOffset + events.length.toLong
                            val updatedActive = active.copy(
                                writePos = newWritePos,
                                recordPositions = active.recordPositions ++ Chunk.from(positions)
                            )
                            val published = s.copy(segments = priorSegments :+ updatedActive, lastOffset = lastOffset)
                            ref.set(published)
                            val result = AppendResult(
                                streamId = streamId,
                                firstOffset = StreamOffset.fromUnchecked(firstOffset),
                                lastOffset = StreamOffset.fromUnchecked(lastOffset),
                                streamInfo = StreamInfo.Existing(
                                    StreamVersion.after(StreamOffset.fromUnchecked(lastOffset)),
                                    StreamOffset.fromUnchecked(lastOffset)
                                )
                            )
                            (handle, key, lastOffset, result)
                        }
                    }
            }
        }
    end writeFramedBatch

    // Seal the active segment and start a new one when it has reached segmentSize (soft threshold).
    // Creates the next segment file named by the next offset, writes its header (if any).
    // Returns (segmentsBeforeActive, activeEntry). No rotation: the active segment stays in place
    // and the prior list is everything before it. Rotation: the filled segment is sealed and
    // appended to the prior list, and a fresh active segment is created.
    private def rotateIfNeeded(streamId: StreamId, s: StreamState, nextOffset: Long)(using
        AllowUnsafe,
        Frame
    ): (Chunk[SegmentEntry], SegmentEntry) < (S & Abort[JournalStorageError]) =
        s.segments.lastMaybe match
            case Present(active) if active.writePos < config.segmentSize.toBytes =>
                (s.segments.dropRight(1), active)
            case Present(active) =>
                val sealed0 = active.copy(sealedSize = active.writePos)
                createSegment(streamId, nextOffset).map(fresh => (s.segments.dropRight(1) :+ sealed0, fresh))
            case Absent =>
                createSegment(streamId, nextOffset).map(fresh => (Chunk.empty, fresh))
    end rotateIfNeeded

    // Directory operations (existence check, mkdir, listing) stay on the raw Path.unsafe API on
    // every platform: they run only at segment-creation/rotation time (not per record) and are not
    // part of the StoreSeam generalization.
    private def createSegment(streamId: StreamId, baseOffset: Long)(using
        AllowUnsafe,
        Frame
    ): SegmentEntry < (S & Abort[JournalStorageError]) =
        val dir     = streamDir(streamId)
        val existed = dir.unsafe.exists()
        discard(dir.unsafe.mkDir())
        val segPath   = dir / codec.segmentName(baseOffset)
        val headerLen = codec.header.length.toLong
        // A newly created stream directory's entry is not durable until its parent (streams/) is
        // fsync'd. Sync it before the segment beneath it is acknowledged, on the fsync path only
        // and only when the directory did not already exist (a rotation reuses an existing, already-
        // synced stream directory).
        val syncStreamsDir: Unit < S = if config.fsync == Fsync.Always && !existed then seam.syncDir(streamsDir) else ()
        syncStreamsDir.andThen(handleFor(segPath)).map { handle =>
            val writeHeader: Unit < S = if headerLen > 0L then handle.writeAt(0L, codec.header) else ()
            // seam.syncDir after creating the segment so its directory link survives a crash; the
            // subsequent handle.sync() (or group-commit flush) covers the segment's data.
            val syncSegmentDir: Unit < S = if config.fsync == Fsync.Always then seam.syncDir(dir) else ()
            writeHeader.andThen(syncSegmentDir).andThen(
                SegmentEntry(baseOffset, segPath, Chunk.empty[Long], headerLen, headerLen)
            )
        }
    end createSegment

    // --- read path ---------------------------------------------------------------------------

    private def readCriticalSection(
        streamId: StreamId,
        from: StreamOffset,
        maxCount: Int,
        log: Log.Unsafe
    )(using AllowUnsafe, Frame): Result[JournalReadFailure, Chunk[RecordedEvent]] < S =
        val ref = cell(streamId)
        ensureFirstTouch(streamId, ref, log).map:
            case Result.Failure(err) => Result.fail(err)
            case Result.Success(s) =>
                if maxCount <= 0 || from.value > s.durableOffset then Result.succeed(Chunk.empty)
                else readRange(streamId, s, from.value, math.min(from.value + maxCount.toLong - 1L, s.durableOffset))
    end readCriticalSection

    private def streamInfoCriticalSection(streamId: StreamId, log: Log.Unsafe)(using
        AllowUnsafe,
        Frame
    ): Result[JournalStreamInfoFailure, StreamInfo] < S =
        val ref = cell(streamId)
        ensureFirstTouch(streamId, ref, log).map(_.map(infoOf))
    end streamInfoCriticalSection

    // --- indexing / recovery entry points -----------------------------------------------------

    // Read path first-touch: recover under a one-shot claim if not yet indexed, then release it.
    private def ensureFirstTouch(streamId: StreamId, ref: AtomicRef.Unsafe[StreamState], log: Log.Unsafe)(using
        AllowUnsafe,
        Frame
    ): Result[IndexFailure, StreamState] < S =
        if ref.get().indexed then Result.succeed(ref.get())
        else
            claimSeam.acquire(streamId, ref).map { claimed =>
                Sync.ensure(claimSeam.release(streamId, ref)) {
                    ensureIndexed(streamId, ref, claimed, log)
                }
            }
    end ensureFirstTouch

    // Runs recovery once for a claimed cell; publishes the recovered state. Both lastOffset and
    // durableOffset come from recovery equally: everything scanned off disk is, by definition,
    // already durable (recovery only ever discovers previously-fsynced or torn-and-truncated data).
    private def ensureIndexed(
        streamId: StreamId,
        ref: AtomicRef.Unsafe[StreamState],
        claimed: StreamState,
        log: Log.Unsafe
    )(using AllowUnsafe, Frame): Result[IndexFailure, StreamState] < S =
        if claimed.indexed then Result.succeed(claimed)
        else
            recover(streamId, log).map:
                case Result.Failure(err)       => Result.fail(err)
                case Result.Success(recovered) =>
                    // Preserve the caller's claim flag: `recovered` is built from scratch by `recover`
                    // and always carries `writer = false`, which would republish a released mutex
                    // while the caller (append or first-touch) is still holding the claim.
                    val published = recovered.copy(writer = claimed.writer)
                    ref.set(published)
                    Result.succeed(published)
    end ensureIndexed

    // --- recovery ---------------------------------------------------------------------------------

    private def recover(streamId: StreamId, log: Log.Unsafe)(using AllowUnsafe, Frame): Result[IndexFailure, StreamState] < S =
        val dir = streamDir(streamId)
        if !dir.unsafe.exists() then Result.succeed(StreamState.emptyIndexed)
        else
            dir.unsafe.list() match
                // Extension-driven filtering: codec.segmentExtension selects which segment files
                // belong to this root, so JSONL roots recover .jsonl files and Binary roots recover .seg files.
                case Result.Success(paths) =>
                    val segFiles = paths.filter(_.unsafe.show.endsWith(codec.segmentExtension)).sortBy(_.unsafe.show)
                    if segFiles.isEmpty then Result.succeed(StreamState.emptyIndexed)
                    else scanSegments(streamId, segFiles, log)
                case Result.Failure(e) =>
                    Result.fail(JournalStorageError(s"Cannot list segments for '${streamId.value}'", Present(e)))
        end if
    end recover

    // Walks segments in base-offset order, validating header + records, grouping by batch
    // terminators. The trailing torn batch (no valid terminator after it) in the LAST segment
    // truncates and warns; any other CRC/framing failure is fatal Corrupted; an unknown version is
    // fatal. Returns the recovered, indexed StreamState with durableOffset == lastOffset (recovered
    // data is, by definition, already durable).
    private def scanSegments(streamId: StreamId, segFiles: Chunk[Path], log: Log.Unsafe)(using
        AllowUnsafe,
        Frame
    ): Result[IndexFailure, StreamState] < S =
        catchStorageError[Result[IndexFailure, StreamState]](s"Recovery of stream '${streamId.value}' failed") {
            Loop.indexed(Chunk.empty[SegmentEntry], -1L) { (idx, entries, lastOffset) =>
                if idx >= segFiles.length then
                    Loop.done[Chunk[SegmentEntry], Long, Result[IndexFailure, StreamState]](
                        Result.succeed(StreamState(entries, lastOffset, lastOffset, indexed = true, writer = false))
                    )
                else
                    val segPath  = segFiles(idx)
                    val isActive = idx == segFiles.length - 1
                    handleFor(segPath).map { handle =>
                        handle.size().map { size =>
                            codec.validateHeader(handle).map:
                                case Present(detail) =>
                                    Loop.done[Chunk[SegmentEntry], Long, Result[IndexFailure, StreamState]](
                                        Result.fail(JournalCorruptedError(Absent, detail))
                                    )
                                case Absent =>
                                    val baseOffset = codec.parseSegmentName(segPath)
                                    codec.scan(handle, size, isActive).map:
                                        case ScanResult.Corrupt(detail) =>
                                            Loop.done[Chunk[SegmentEntry], Long, Result[IndexFailure, StreamState]](Result.fail(
                                                JournalCorruptedError(Present(streamId), s"$detail in segment '${segPath.unsafe.show}'")
                                            ))
                                        case ScanResult.Ok(positions, committedEnd, tornAt) =>
                                            (tornAt match
                                                case Present(from) if isActive =>
                                                    handle.truncate(committedEnd).map { _ =>
                                                        log.warn(
                                                            s"FileJournal recovered stream '${streamId.value}': truncated torn tail of segment '${segPath.unsafe.show}' from byte $from to $committedEnd"
                                                        )(using frame, summon[AllowUnsafe])
                                                    }
                                                case _ => (()): Unit < S
                                            ).map { _ =>
                                                val entry = SegmentEntry(
                                                    baseOffset,
                                                    segPath,
                                                    positions,
                                                    if isActive then committedEnd else size,
                                                    committedEnd
                                                )
                                                Loop.continue(entries :+ entry, baseOffset + positions.size.toLong - 1L)
                                            }
                        }
                    }
            }
        }.map {
            case Result.Success(inner) => inner
            case failure               => failure.asInstanceOf[Result[IndexFailure, StreamState]]
        }
    end scanSegments

    // --- shared helpers -----------------------------------------------------------------------

    private def readRange(streamId: StreamId, s: StreamState, fromOff: Long, toOff: Long)(using
        AllowUnsafe,
        Frame
    ): Result[JournalReadFailure, Chunk[RecordedEvent]] < S =
        catchStorageError[Result[JournalReadFailure, Chunk[RecordedEvent]]](s"Read of stream '${streamId.value}' failed") {
            Loop.indexed(Chunk.empty[RecordedEvent], fromOff) { (_, out, off) =>
                if off > toOff then
                    Loop.done[Chunk[RecordedEvent], Long, Result[JournalReadFailure, Chunk[RecordedEvent]]](Result.succeed(out))
                else
                    val seg    = segmentFor(s, off)
                    val handle = handleFor(seg.path)
                    handle.map { h =>
                        val pos = seg.recordPositions((off - seg.baseOffset).toInt)
                        codec.decodeRecordAt(h, pos).map:
                            case Result.Failure(detail) =>
                                Loop.done[Chunk[RecordedEvent], Long, Result[JournalReadFailure, Chunk[RecordedEvent]]](
                                    Result.fail(JournalCorruptedError(Present(streamId), detail))
                                )
                            case Result.Success(dec) =>
                                rebuild(streamId, dec) match
                                    case Result.Failure(err) =>
                                        Loop.done[Chunk[RecordedEvent], Long, Result[JournalReadFailure, Chunk[RecordedEvent]]](
                                            Result.fail(err)
                                        )
                                    case Result.Success(ev) => Loop.continue(out :+ ev, off + 1L)
                    }
            }
        }.map {
            case Result.Success(inner) => inner
            case failure               => failure.asInstanceOf[Result[JournalReadFailure, Chunk[RecordedEvent]]]
        }
    end readRange

    private def rebuild(streamId: StreamId, dec: DecodedRecord)(using AllowUnsafe): Result[JournalReadFailure, RecordedEvent] =
        val built =
            for
                eid <- EventId(dec.eventId)
                etp <- EventType(dec.eventType)
                // decodeMetadata expects MsgPack bytes; both BinarySegmentCodec.decodeRecordAt and
                // JsonlSegmentCodec.decodeRecordAt store metadata in MsgPack form in DecodedRecord.
                md <- BinarySegmentCodec.decodeMetadata(dec.metadata)
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

    // Opens a handle for `path` on first access, registering it in the handle map. On a race,
    // closes the duplicate and returns the winning handle. Path string used as the registry key;
    // paths are derived from the root directory (which callers pass as absolute in practice).
    private def handleFor(path: Path)(using AllowUnsafe, Frame): StoreSeam.Handle[S] < (S & Abort[JournalStorageError]) =
        val key = path.unsafe.show
        Maybe.fromOption(handles.get().get(key)) match
            case Present(h) => h
            case Absent     => seam.open(path).map(h => registerHandle(key, h))
        end match
    end handleFor

    private def registerHandle(key: String, h: StoreSeam.Handle[S])(using AllowUnsafe, Frame): StoreSeam.Handle[S] < S =
        val m = handles.get()
        Maybe.fromOption(m.get(key)) match
            case Present(existing) => h.close().map(_ => existing)
            case Absent =>
                if handles.compareAndSet(m, m.updated(key, h)) then h
                else registerHandle(key, h)
        end match
    end registerHandle

    private def streamDir(streamId: StreamId): Path = streamsDir / BinarySegmentCodec.encodeStreamId(streamId)

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

    // Durable-view: what a caller of `read`/`streamInfo` may observe. Never runs ahead of the last
    // fsynced offset, so a written-but-not-yet-durable record is invisible outside the stream's own
    // claim holder.
    private def infoOf(s: StreamState): StreamInfo =
        if s.durableOffset < 0L then StreamInfo.Absent
        else
            val last = StreamOffset.fromUnchecked(s.durableOffset)
            StreamInfo.Existing(StreamVersion.after(last), last)

    // Written-view: what the append conflict check compares `expected` against. Same-stream appends
    // are already serialized by the claim (CAS spin or parked permit), so this always reflects every
    // prior appender's write by the time the next one is granted the claim, even if that prior
    // append's own durability flush is still in flight.
    private def writtenInfoOf(s: StreamState): StreamInfo =
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

    // Wraps `v` so a thrown Exception becomes a typed JournalStorageError instead of an untyped
    // panic, and materializes the result as a plain Result value (this class threads Result, not
    // Abort, internally; the public append/read/streamInfo methods are the only places that cross
    // into Abort, via Abort.get). Works identically whether S is Sync or Async: Abort.catching
    // catches exceptions raised at any point in the wrapped `< S` computation, including inside a
    // suspended Async continuation.
    private def catchStorageError[A](msg: String)(v: => A < (S & Abort[JournalStorageError]))(using
        Frame
    ): Result[JournalStorageError, A] < S =
        Abort.run(Abort.catching[Exception](e => JournalStorageError(msg, Present(e)))(v)).map {
            case Result.Success(a)   => Result.succeed(a)
            case Result.Failure(err) => Result.fail(err)
            case Result.Panic(e)     => throw e
        }

end FileJournalCore

// --- Companion (in-process registry + open factory) ------------------------------------------

private[kyo] object FileJournalCore:

    private val Utf8 = StandardCharsets.UTF_8

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
      * via the supplied [[StoreSeam]]. Internal; the public entry points are the platform-specific
      * `Journal.Backend.file` / `Journal.Backend.fileAsync` extensions.
      */
    private[kyo] def open[S >: (Async & Abort[Throwable]) <: Sync](
        dir: Path,
        config: FileJournal.Config,
        seam: StoreSeam[S],
        payloadCodec: EventPayloadCodec,
        claimSeam: ClaimSeam[S],
        flushStrategyFor: FileJournal.Fsync => FlushStrategy[S]
    )(using
        frame: Frame
    )
        : Journal.Backend[S] < (Sync & Scope & Abort[JournalStorageError]) =
        Scope.acquireRelease(
            // Unsafe: creates the root, streams/ via Path.Unsafe and acquires the platform lock via
            // the injected StoreSeam; raw platform I/O is the only path to locking.
            Sync.Unsafe.defer(FileJournalCore.acquire(dir, config, seam, payloadCodec, claimSeam, flushStrategyFor).map(Abort.get))
        )(backend =>
            // Unsafe: bridges the raw platform release calls (lock release, handle closes) into the
            // Sync tier for the Scope.acquireRelease finalization callback.
            Sync.Unsafe.defer(backend.release())
        )

    private def acquire[S >: (Async & Abort[Throwable]) <: Sync](
        dir: Path,
        config: FileJournal.Config,
        seam: StoreSeam[S],
        payloadCodec: EventPayloadCodec,
        claimSeam: ClaimSeam[S],
        flushStrategyFor: FileJournal.Fsync => FlushStrategy[S]
    )(using
        frame: Frame,
        allow: AllowUnsafe
    ): Result[JournalStorageError, FileJournalCore[S]] < Sync =
        // Use show as the canonical in-process key. Callers are expected to pass absolute paths
        // (Path.tempDir returns absolute; production usage is absolute); relative paths with the
        // same last component but different cwd would collide, which matches the platform-lock's
        // own cwd-relative limitation.
        val rootKey = dir.unsafe.show
        if dir.unsafe.exists() && !dir.unsafe.isDirectory() then
            Result.fail(JournalStorageError(s"Journal root '${dir.unsafe.show}' exists and is not a directory", Absent))
        else if !registerRoot(rootKey) then
            Result.fail(JournalStorageError(s"Journal root '${dir.unsafe.show}' is locked by this process", Absent))
        else
            discard(dir.unsafe.mkDir())
            val streamsDir = dir / "streams"
            discard(streamsDir.unsafe.mkDir())
            Abort.run(seam.acquireLock(dir)).map {
                case Result.Failure(err) =>
                    unregisterRoot(rootKey)
                    Result.fail(err)
                case Result.Panic(e) =>
                    unregisterRoot(rootKey)
                    Result.fail(JournalStorageError(s"Failed to open journal root '${dir.unsafe.show}'", Present(e)))
                case Result.Success(acquiredLock) =>
                    // FORMAT marker check/write runs after lock acquisition to prevent a TOCTOU
                    // race where two processes both see a fresh root and both write FORMAT.
                    checkOrWriteFormatMarker(dir, config.format) match
                        case Result.Failure(err) =>
                            // Unsafe: release the lock before propagating the format error.
                            discard(Result.catching[Throwable](acquiredLock.release()))
                            unregisterRoot(rootKey)
                            Result.fail(err)
                        case Result.Success(validatedFormat) =>
                            val codec = validatedFormat match
                                case FileJournal.SegmentFormat.Binary => BinarySegmentCodec
                                case FileJournal.SegmentFormat.Jsonl  => new JsonlSegmentCodec(payloadCodec)
                            Result.succeed(
                                new FileJournalCore[S](
                                    rootKey,
                                    streamsDir,
                                    config,
                                    seam,
                                    acquiredLock,
                                    codec,
                                    payloadCodec,
                                    claimSeam,
                                    flushStrategyFor(config.fsync)
                                )
                            )
                    end match
            }
        end if
    end acquire

    // Reads or writes the FORMAT marker file in `dir`. Must be called AFTER the process-level
    // lock is held to prevent TOCTOU races between concurrent opens of the same root.
    //
    // Rules:
    //   - No FORMAT file + no existing stream directories: write FORMAT from requestedFormat; proceed.
    //   - No FORMAT file + existing stream directories: infer Binary (legacy binary root created
    //     before the FORMAT marker existed). If requestedFormat != Binary: fail loud. If
    //     requestedFormat == Binary: proceed without writing FORMAT (backward compat; future opens
    //     infer Binary again).
    //   - FORMAT file with matching format: proceed.
    //   - FORMAT file with mismatched format: fail with typed JournalStorageError.
    //   - FORMAT file with unknown format value: fail with typed JournalStorageError.
    private def checkOrWriteFormatMarker(
        dir: Path,
        requestedFormat: FileJournal.SegmentFormat
    )(using frame: Frame, allow: AllowUnsafe): Result[JournalStorageError, FileJournal.SegmentFormat] =
        val formatFile = dir / "FORMAT"
        if formatFile.unsafe.exists() then
            formatFile.unsafe.readBytes() match
                case Result.Failure(e) =>
                    Result.fail(JournalStorageError(s"Cannot read FORMAT file in '${dir.unsafe.show}'", Present(e)))
                case Result.Success(bytes) =>
                    parseFormatMarker(dir, bytes, requestedFormat)
        else
            // No FORMAT file. Check whether the streams/ directory has any entries, which would
            // indicate a legacy binary root created before the FORMAT marker existed.
            val streamsDir = dir / "streams"
            val hasStreams = streamsDir.unsafe.exists() && (streamsDir.unsafe.list() match
                case Result.Success(paths) => paths.nonEmpty
                case Result.Failure(_)     => false)
            if hasStreams && requestedFormat != FileJournal.SegmentFormat.Binary then
                Result.fail(JournalStorageError(
                    s"Journal root '${dir.unsafe.show}' has existing segments but no FORMAT marker; " +
                        "inferred Binary; Config requests Jsonl",
                    Absent
                ))
            else if hasStreams then
                // Backward-compat: pre-existing Binary root with no FORMAT file.
                // Do not write FORMAT; future opens will infer Binary from segment presence.
                Result.succeed(FileJournal.SegmentFormat.Binary)
            else
                val content = requestedFormat match
                    case FileJournal.SegmentFormat.Binary => "format: binary\nversion: 1\n"
                    case FileJournal.SegmentFormat.Jsonl  => "format: jsonl\nversion: 1\n"
                formatFile.unsafe.writeBytes(Span.from(content.getBytes(Utf8))) match
                    case Result.Failure(e) =>
                        Result.fail(JournalStorageError(
                            s"Cannot write FORMAT file in '${dir.unsafe.show}'",
                            Present(e)
                        ))
                    case Result.Success(_) =>
                        Result.succeed(requestedFormat)
                end match
            end if
        end if
    end checkOrWriteFormatMarker

    private def parseFormatMarker(
        dir: Path,
        bytes: Span[Byte],
        requestedFormat: FileJournal.SegmentFormat
    )(using Frame): Result[JournalStorageError, FileJournal.SegmentFormat] =
        val content = new String(bytes.toArray, Utf8)
        val pairs = content.split("\n").flatMap { line =>
            val idx = line.indexOf(": ")
            if idx < 0 then Array.empty[(String, String)]
            else Array((line.substring(0, idx).trim, line.substring(idx + 2).trim))
        }.toMap
        pairs.get("format") match
            case None =>
                Result.fail(JournalStorageError(
                    s"FORMAT file in '${dir.unsafe.show}' has no 'format' key",
                    Absent
                ))
            case Some(diskFormatStr) =>
                val diskFormatResult = diskFormatStr match
                    case "binary" => Result.succeed(FileJournal.SegmentFormat.Binary)
                    case "jsonl"  => Result.succeed(FileJournal.SegmentFormat.Jsonl)
                    case other =>
                        Result.fail(JournalStorageError(
                            s"FORMAT file in '${dir.unsafe.show}' has unknown format value '$other'",
                            Absent
                        ))
                diskFormatResult.flatMap { df =>
                    // Validate version before the format-mismatch check so an unknown version
                    // is always loud, even when the format value happens to match.
                    val versionCheck = pairs.get("version") match
                        case Some("1") => Result.succeed(())
                        case Some(v) =>
                            Result.fail(JournalStorageError(
                                s"FORMAT file in '${dir.unsafe.show}' has version '$v'; supported: 1",
                                Absent
                            ))
                        case None =>
                            Result.fail(JournalStorageError(
                                s"FORMAT file in '${dir.unsafe.show}' has no 'version' key",
                                Absent
                            ))
                    versionCheck.flatMap { _ =>
                        if df != requestedFormat then
                            val reqStr = requestedFormat match
                                case FileJournal.SegmentFormat.Binary => "binary"
                                case FileJournal.SegmentFormat.Jsonl  => "jsonl"
                            Result.fail(JournalStorageError(
                                s"Journal root '${dir.unsafe.show}' was created as $diskFormatStr; Config requests $reqStr",
                                Absent
                            ))
                        else Result.succeed(df)
                    }
                }
        end match
    end parseFormatMarker

end FileJournalCore
