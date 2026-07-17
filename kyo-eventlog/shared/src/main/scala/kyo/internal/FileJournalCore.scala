package kyo.internal

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kyo.*
import scala.annotation.tailrec

// --- SegmentFormat trait -----------------------------------------------------------------------

/** Format-dispatch seam for segment encoding. Implementations encode and decode event records
  * to and from segment files; the orchestration layer ([[FileJournalCore]]) is format-agnostic.
  * Every operation that reads from a handle is polymorphic in the handle's effect `S`, so the
  * same codec instance drives both the synchronous and the asynchronous backend.
  *
  * Two implementations exist: [[BinarySegmentFormat]] (the original `KJN1` binary format, wired to
  * [[kyo.EventLogCodecs.MetadataCodec]]) and [[kyo.internal.JsonlSegmentFormat]] (the
  * one-JSON-object-per-line JSONL format). Both are `private[kyo]`; neither appears in public surface.
  */
private[kyo] trait SegmentFormat:

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
    def frameBatch(firstOffset: Long, events: Chunk[Event.Pending]): Array[Byte]

    /** Per-record byte size for the binary format; used by the default [[extractPositions]]
      * implementation. For binary segments this is computed analytically from the field lengths;
      * for JSONL, [[extractPositions]] is overridden to scan the batch bytes for newlines, so
      * this method is not used for position computation in JSONL mode.
      */
    def recordSize(env: Event.Pending): Long

    /** Extracts the per-record byte-start positions from the bytes returned by
      * [[frameBatch]]. The default implementation uses [[recordSize]] per event. JSONL overrides
      * this to scan for newline boundaries instead, because the JSONL line length depends on the
      * encoded offset value and cannot be determined analytically without encoding.
      */
    def extractPositions(
        firstOffset: Long,
        events: Chunk[Event.Pending],
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

end SegmentFormat

// --- Binary segment codec (the original KJN1 format) -----------------------------------------

/** Stateless binary codec helpers: header, record frame (with CRC), batch-commit terminator, and
  * the injective streamId percent-encoding. The [[BinarySegmentFormat]] class wires these helpers
  * to a configurable [[kyo.EventLogCodecs.MetadataCodec]] for segment encoding.
  *
  * Uses the shared pure [[CRC32]] on every platform; `java.util.zip.CRC32` is not referenced here.
  */
private[kyo] object BinarySegmentFormat:

    val Magic: Array[Byte]         = Array[Byte]('K', 'J', 'N', '1') // 0x4B 0x4A 0x4E 0x31
    val Version: Byte              = 0x01
    val HeaderSize: Int            = 5
    val SegmentHeader: Array[Byte] = Magic :+ Version
    val CommitMagic: Array[Byte]   = Array[Byte]('K', 'J', 'N', 'C')
    val TerminatorSize: Int        = 12                              // KJNC(4) + recordCount(4) + crc(4)
    val segmentExtension: String   = ".seg"

    private[kyo] val Utf8 = StandardCharsets.UTF_8

    def segmentName(baseOffset: Long): String = f"$baseOffset%020d$segmentExtension"

    // Body = Event.StreamOffset(8) | lp(eventId) | lp(eventType) | lp(metadata) | lp(payload); lp = len(4)+bytes.
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

    def encodeStreamId(streamId: Event.StreamId): String =
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

    private[kyo] def putLp(buf: ByteBuffer, bytes: Array[Byte]): Unit =
        buf.putInt(bytes.length); discard(buf.put(bytes))
    private[kyo] def getLpBytes(buf: ByteBuffer): Array[Byte] =
        val n = buf.getInt(); val a = new Array[Byte](n); buf.get(a); a
    private[kyo] def getLpStr(buf: ByteBuffer): String = new String(getLpBytes(buf), Utf8)
    private[kyo] def intBytes(v: Int): Array[Byte]     = ByteBuffer.allocate(4).putInt(v).array()

end BinarySegmentFormat

/** Binary segment codec wired to a configurable [[kyo.EventLogCodecs.MetadataCodec]]. */
final private[kyo] class BinarySegmentFormat(metadataCodec: EventLogCodecs.MetadataCodec) extends SegmentFormat:

    def segmentExtension: String = ".seg"
    def header: Array[Byte]      = BinarySegmentFormat.SegmentHeader

    // Returns Present(detail) if the 5-byte header is missing/malformed/unknown-version, else Absent.
    def validateHeader[S](handle: StoreSeam.Handle[S])(using Frame): Maybe[String] < S =
        handle.readAt(0L, BinarySegmentFormat.HeaderSize).map { arr =>
            if arr.length < BinarySegmentFormat.HeaderSize then Present(s"segment header truncated (${arr.length} bytes)")
            else
                val m = java.util.Arrays.copyOfRange(arr, 0, 4)
                val v = arr(4)
                if !java.util.Arrays.equals(m, BinarySegmentFormat.Magic) then Present("segment magic is not KJN1")
                else if v != BinarySegmentFormat.Version then Present(s"unknown segment format version 0x${(v & 0xff).toHexString}")
                else Absent
        }
    end validateHeader

    def recordSize(env: Event.Pending): Long =
        val idB = env.id.value.getBytes(BinarySegmentFormat.Utf8)
        val tpB = env.eventType.value.getBytes(BinarySegmentFormat.Utf8)
        val md  = FileJournalCore.encodeMetadata(metadataCodec, env.metadata)
        val pl  = env.payload.toArray
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
                                    val eventId  = BinarySegmentFormat.getLpStr(body)
                                    val eventTp  = BinarySegmentFormat.getLpStr(body)
                                    val metadata = BinarySegmentFormat.getLpBytes(body)
                                    val payload  = BinarySegmentFormat.getLpBytes(body)
                                    Result.succeed(DecodedRecord(offset, eventId, eventTp, metadata, payload, bodyLen))
                                end if
                        }
                    end if
                }
        }
    end decodeRecordAt

    // Returns Present(recordCount) if a valid terminator sits at pos, else Absent (torn/absent).
    def readTerminator[S](handle: StoreSeam.Handle[S], pos: Long)(using Frame): Maybe[Int] < S =
        handle.readAt(pos, BinarySegmentFormat.TerminatorSize).map { arr =>
            if arr.length < BinarySegmentFormat.TerminatorSize then Absent
            else
                val buf    = ByteBuffer.wrap(arr)
                val m      = new Array[Byte](4); buf.get(m)
                val count  = buf.getInt()
                val crcExp = buf.getInt()
                val crc    = new CRC32(); crc.update(BinarySegmentFormat.CommitMagic); crc.update(BinarySegmentFormat.intBytes(count))
                if java.util.Arrays.equals(m, BinarySegmentFormat.CommitMagic) && (crc.value & 0xffffffffL).toInt == crcExp then
                    Present(count)
                else Absent
        }
    end readTerminator

    def scan[S](handle: StoreSeam.Handle[S], size: Long, isActive: Boolean)(using Frame): ScanResult < S =
        Loop(BinarySegmentFormat.HeaderSize.toLong, BinarySegmentFormat.HeaderSize.toLong, Chunk.empty[Long], Chunk.empty[Long]) {
            (pos, committedEnd, pending, committed) =>
                if pos >= size then
                    if pending.isEmpty then Loop.done(ScanResult.Ok(committed, committedEnd, Absent))
                    else if isActive then Loop.done(ScanResult.Ok(committed, committedEnd, Present(pending.head)))
                    else Loop.done(ScanResult.Corrupt(s"unterminated batch at byte ${pending.head} in sealed segment"))
                else
                    readTerminator(handle, pos).map {
                        case Present(count) if count == pending.length =>
                            val newCommitted = committed ++ pending
                            val newPos       = pos + BinarySegmentFormat.TerminatorSize.toLong
                            Loop.continue(newPos, newPos, Chunk.empty[Long], newCommitted)
                        case _ =>
                            decodeRecordAt(handle, pos).map {
                                case Result.Success(dec) =>
                                    Loop.continue(pos + 8L + dec.bodyLen.toLong, committedEnd, pending.append(pos), committed)
                                case Result.Failure(detail) =>
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
        val window  = 1 << 16
        val overlap = BinarySegmentFormat.TerminatorSize.toLong - 1L
        Loop(from) { winPos =>
            if winPos > size - BinarySegmentFormat.TerminatorSize.toLong then Loop.done(false)
            else
                val len = math.min(window.toLong, size - winPos).toInt
                handle.readAt(winPos, len).map { arr =>
                    if scanWindowForTerminator(arr) then Loop.done(true)
                    else Loop.continue(winPos + (window.toLong - overlap))
                }
        }
    end hasCommitAfter

    @tailrec private def scanWindowForTerminator(arr: Array[Byte], i: Int = 0): Boolean =
        val last = arr.length - BinarySegmentFormat.TerminatorSize
        if i > last then false
        else if BinarySegmentFormat.terminatorAt(arr, i) then true
        else scanWindowForTerminator(arr, i + 1)
    end scanWindowForTerminator

    def frameBatch(firstOffset: Long, events: Chunk[Event.Pending]): Array[Byte] =
        val recs  = new Array[Array[Byte]](events.length)
        var total = 0
        var i     = 0
        while i < events.length do
            val e  = events(i)
            val md = FileJournalCore.encodeMetadata(metadataCodec, e.metadata)
            val r  = BinarySegmentFormat.encodeRecord(firstOffset + i, e.id.value, e.eventType.value, md, e.payload.toArray)
            recs(i) = r; total += r.length; i += 1
        end while
        val term   = BinarySegmentFormat.encodeTerminator(events.length)
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

end BinarySegmentFormat

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
    def acquire(streamId: Event.StreamId, ref: AtomicRef.Unsafe[StreamState])(using Frame): StreamState < S
    def release(streamId: Event.StreamId, ref: AtomicRef.Unsafe[StreamState])(using Frame): Unit < Sync
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

        def acquire(streamId: Event.StreamId, ref: AtomicRef.Unsafe[StreamState])(using Frame): StreamState < Sync =
            // Unsafe: bridges raw CAS spin claim into the Sync tier.
            Sync.Unsafe.defer(acquireUnsafe(streamId, ref))

        @tailrec private def acquireUnsafe(streamId: Event.StreamId, ref: AtomicRef.Unsafe[StreamState])(using AllowUnsafe): StreamState =
            val s = ref.get()
            if s.writer then
                yieldCurrentThread()
                acquireUnsafe(streamId, ref)
            else
                val claimed = s.copy(writer = true)
                if ref.compareAndSet(s, claimed) then claimed
                else acquireUnsafe(streamId, ref)
            end if
        end acquireUnsafe

        def release(streamId: Event.StreamId, ref: AtomicRef.Unsafe[StreamState])(using Frame): Unit < Sync =
            // Unsafe: bridges raw CAS release into the Sync tier.
            Sync.Unsafe.defer(releaseUnsafe(streamId, ref))

        private def releaseUnsafe(streamId: Event.StreamId, ref: AtomicRef.Unsafe[StreamState])(using AllowUnsafe): Unit =
            val cur = ref.get()
            if cur.writer then discard(ref.compareAndSet(cur, cur.copy(writer = false)))
        end releaseUnsafe
    end sync

    /** A fresh per-instance async claim: a lazily created, per-stream `Channel[Unit]` of capacity
      * one used as a mutex (preloaded with one token, so the first `take` succeeds immediately). A
      * blocked appender parks on `take`, suspending the fiber and freeing the carrier rather than
      * spinning; FIFO fairness comes from the channel's own wait queue.
      */
    def async()(using AllowUnsafe): ClaimSeam[Async] = new ClaimSeam[Async]:
        def holdThroughFlush: Boolean = false

        // Unsafe: bootstraps an empty in-process map; never touches platform I/O and is always safe.
        private val permits: AtomicRef.Unsafe[Map[Event.StreamId, Channel[Unit]]] = AtomicRef.Unsafe.init(Map.empty)

        @tailrec private def permitFor(streamId: Event.StreamId)(using AllowUnsafe, Frame): Channel[Unit] =
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

        def acquire(streamId: Event.StreamId, ref: AtomicRef.Unsafe[StreamState])(using Frame): StreamState < Async =
            // Unsafe: resolves the per-stream permit and reads stream state after take.
            Sync.Unsafe.defer(permitFor(streamId)).flatMap { permit =>
                Abort.run[Closed](permit.take).flatMap:
                    case Result.Success(_) =>
                        Sync.Unsafe.defer(ref.get()).asInstanceOf[StreamState < Async]
                    case Result.Failure(closed) =>
                        throw new IllegalStateException(s"stream permit channel for '${streamId.value}' closed unexpectedly: $closed")
                    case Result.Panic(e) => throw e
            }
        end acquire

        def release(streamId: Event.StreamId, ref: AtomicRef.Unsafe[StreamState])(using Frame): Unit < Sync =
            // Unsafe: resolves the per-stream permit and returns the mutex token.
            Sync.Unsafe.defer(permitFor(streamId)).flatMap { permit =>
                Abort.run[Closed](permit.offerDiscard(())).map:
                    case Result.Success(_) => ()
                    case Result.Failure(closed) =>
                        throw new IllegalStateException(s"stream permit channel for '${streamId.value}' closed unexpectedly: $closed")
                    case Result.Panic(e) => throw e
            }
        end release
    end async

    /** Reader-only no-op claim: indexing never takes the write permit. */
    def noop[S]: ClaimSeam[S] = new ClaimSeam[S]:
        def holdThroughFlush: Boolean = true
        def acquire(streamId: Event.StreamId, ref: AtomicRef.Unsafe[StreamState])(using Frame): StreamState < S =
            // Unsafe: bridges raw atomic read into the Sync tier.
            Sync.Unsafe.defer(ref.get()).asInstanceOf[StreamState < S]
        def release(streamId: Event.StreamId, ref: AtomicRef.Unsafe[StreamState])(using Frame): Unit < Sync = ()
    end noop

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
final private[kyo] class GroupCommitCoordinator(
    private val coordinators: AtomicRef.Unsafe[Map[String, AtomicRef.Unsafe[GroupCommitCoordinator.State]]]
):
    import GroupCommitCoordinator.State

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
    enum State derives CanEqual:
        case Idle
        case Flushing(waiters: List[Channel[Unit]])

    def init(using AllowUnsafe): GroupCommitCoordinator =
        new GroupCommitCoordinator(AtomicRef.Unsafe.init(Map.empty))
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

    /** Reader-only no-op flush: append is never invoked on a read-only open. */
    def noop[S]: FlushStrategy[S] = new FlushStrategy[S]:
        def confirmDurable(
            handle: StoreSeam.Handle[S],
            key: String,
            ref: AtomicRef.Unsafe[StreamState],
            targetOffset: Long
        )(using AllowUnsafe, Frame): Unit < S = ()
    end noop

end FlushStrategy

// --- Shared orchestration class ---------------------------------------------------------------

/** Recovers a [[FileJournalCore]] instance's bound value codec and journalId without depending on
  * its `S` type parameter, so a caller holding only a phantom `FileJournal.Reader[A, S]`
  * (e.g. [[kyo.EventLogSupport.mkReader]]) can downcast to this narrower interface instead of the
  * full `FileJournalCore[A, S]` type, which would require re-proving `S`'s bound at the cast
  * site.
  */
private[kyo] trait BoundValueAccess[A]:
    private[kyo] def boundValueCodec: EventLogCodecs.ValueCodec[A]
    private[kyo] def journalId: JournalId
end BoundValueAccess

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
  * The segment encoding is delegated to the injected [[SegmentFormat]], which is selected by the
  * MANIFEST marker at open time.
  */
final private[kyo] class FileJournalCore[A, S >: (Async & Abort[Throwable]) <: Sync](
    rootKey: String,
    streamsDir: Path,
    options: FileJournal.Options,
    metadataCodec: EventLogCodecs.MetadataCodec,
    seam: StoreSeam[S],
    lock: SegmentStore.Lock,
    codec: SegmentFormat,
    valueCodec: EventLogCodecs.ValueCodec[?],
    claimSeam: ClaimSeam[S],
    flushStrategy: FlushStrategy[S],
    handles: AtomicRef.Unsafe[Map[String, StoreSeam.Handle[S]]],
    streams: AtomicRef.Unsafe[Map[Event.StreamId, AtomicRef.Unsafe[StreamState]]],
    private[kyo] val journalId: JournalId,
    readerMode: Boolean = false
)(using frame: Frame) extends FileJournal.Backend[A, S] with BoundValueAccess[A]:

    import FileJournal.*

    // Recovery and indexing fail only with JournalStorageError or JournalCorruptedError; both mix
    // in all three per-op failure traits, so this union narrows cleanly into any Backend row
    // (append/read/streamInfo) at each forward site.
    private type IndexFailure = JournalStorageError | JournalCorruptedError

    /** The bound value codec for this reader/backend's domain type `A`, recovered for
      * [[kyo.EventLogSupport.mkReader]]'s typed decode wiring. Stored as `ValueCodec[?]` on the
      * class because the class's own SegmentFormat plumbing never needs it statically typed; this
      * accessor recovers the static type via the class's own `A`, sound because every
      * construction site threads `configuration.codecs.value: ValueCodec[A]` for the same `A`
      * this class is parameterized on.
      */
    private[kyo] def boundValueCodec: EventLogCodecs.ValueCodec[A] =
        // Unsafe: recovers the erased ValueCodec[?] field's static type; sound by construction
        // (see scaladoc above), never reachable with a mismatched A.
        valueCodec.asInstanceOf[EventLogCodecs.ValueCodec[A]]

    def append(
        streamId: Event.StreamId,
        expected: ExpectedOffset,
        events: Chunk[Event.Pending]
    ): AppendResult < (S & Abort[JournalAppendFailure]) =
        if events.isEmpty then Abort.fail(JournalEmptyAppendError())
        else
            Log.use { log =>
                // Unsafe: bridges Log.unsafe into the append critical section.
                Sync.Unsafe.defer(appendCriticalSection(streamId, expected, events, cell(streamId), log.unsafe).map(Abort.get))
                    .asInstanceOf[AppendResult < (S & Abort[JournalAppendFailure])]
            }

    def read(
        streamId: Event.StreamId,
        from: Event.StreamOffset,
        maxCount: Int
    ): Chunk[Event.Committed] < (S & Abort[JournalReadFailure]) =
        Log.use { log =>
            // Unsafe: bridges Log.unsafe into the read critical section.
            Sync.Unsafe.defer(readCriticalSection(streamId, from, maxCount, log.unsafe).map(Abort.get))
                .asInstanceOf[Chunk[Event.Committed] < (S & Abort[JournalReadFailure])]
        }

    def streamInfo(streamId: Event.StreamId): StreamInfo < (S & Abort[JournalStreamInfoFailure]) =
        Log.use { log =>
            // Unsafe: bridges Log.unsafe into the streamInfo critical section.
            Sync.Unsafe.defer(streamInfoCriticalSection(streamId, log.unsafe).map(Abort.get))
                .asInstanceOf[StreamInfo < (S & Abort[JournalStreamInfoFailure])]
        }

    private[kyo] def release()(using AllowUnsafe, Frame): Unit < S =
        discard(Result.catching[Throwable](lock.release()))
        Kyo.foreachDiscard(Chunk.from(handles.get().values)) { h =>
            Abort.run(Abort.catching[Throwable](h.close())).map(_ => ())
        }.map { _ =>
            if !readerMode then FileJournalCore.unregisterRoot(rootKey)
        }
    end release

    // --- get-or-create stream cell ------------------------------------------------------------

    @tailrec
    private def cell(streamId: Event.StreamId)(using AllowUnsafe): AtomicRef.Unsafe[StreamState] =
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
        streamId: Event.StreamId,
        expected: ExpectedOffset,
        events: Chunk[Event.Pending],
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
        streamId: Event.StreamId,
        expected: ExpectedOffset,
        events: Chunk[Event.Pending],
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
        streamId: Event.StreamId,
        expected: ExpectedOffset,
        events: Chunk[Event.Pending],
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
        streamId: Event.StreamId,
        expected: ExpectedOffset,
        events: Chunk[Event.Pending],
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
        streamId: Event.StreamId,
        ref: AtomicRef.Unsafe[StreamState],
        s: StreamState,
        events: Chunk[Event.Pending]
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
                                firstOffset = Event.StreamOffset.fromUnchecked(firstOffset),
                                lastOffset = Event.StreamOffset.fromUnchecked(lastOffset),
                                streamInfo = StreamInfo.Existing(
                                    Event.StreamVersion.after(Event.StreamOffset.fromUnchecked(lastOffset)),
                                    Event.StreamOffset.fromUnchecked(lastOffset)
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
    private def rotateIfNeeded(streamId: Event.StreamId, s: StreamState, nextOffset: Long)(using
        AllowUnsafe,
        Frame
    ): (Chunk[SegmentEntry], SegmentEntry) < (S & Abort[JournalStorageError]) =
        s.segments.lastMaybe match
            case Present(active) if active.writePos < options.segmentSize.toBytes =>
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
    private def createSegment(streamId: Event.StreamId, baseOffset: Long)(using
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
        val syncStreamsDir: Unit < S = if options.fsync == Fsync.Always && !existed then seam.syncDir(streamsDir) else ()
        syncStreamsDir.andThen(handleFor(segPath)).map { handle =>
            val writeHeader: Unit < S = if headerLen > 0L then handle.writeAt(0L, codec.header) else ()
            // seam.syncDir after creating the segment so its directory link survives a crash; the
            // subsequent handle.sync() (or group-commit flush) covers the segment's data.
            val syncSegmentDir: Unit < S = if options.fsync == Fsync.Always then seam.syncDir(dir) else ()
            writeHeader.andThen(syncSegmentDir).andThen(
                SegmentEntry(baseOffset, segPath, Chunk.empty[Long], headerLen, headerLen)
            )
        }
    end createSegment

    // --- read path ---------------------------------------------------------------------------

    private def readCriticalSection(
        streamId: Event.StreamId,
        from: Event.StreamOffset,
        maxCount: Int,
        log: Log.Unsafe
    )(using AllowUnsafe, Frame): Result[JournalReadFailure, Chunk[Event.Committed]] < S =
        val ref = cell(streamId)
        touchForRead(streamId, ref, log).map:
            case Result.Failure(err) => Result.fail(err)
            case Result.Success(s) =>
                if maxCount <= 0 || from.value > s.durableOffset then Result.succeed(Chunk.empty)
                else
                    val toOff = math.min(from.value + maxCount.toLong - 1L, s.durableOffset)
                    readRangeWithRelist(streamId, ref, s, from.value, toOff, log)
    end readCriticalSection

    private def streamInfoCriticalSection(streamId: Event.StreamId, log: Log.Unsafe)(using
        AllowUnsafe,
        Frame
    ): Result[JournalStreamInfoFailure, StreamInfo] < S =
        val ref = cell(streamId)
        touchForRead(streamId, ref, log).map(_.map(infoOf))
    end streamInfoCriticalSection

    // --- indexing / recovery entry points -----------------------------------------------------

    // Read path first-touch: recover under a one-shot claim if not yet indexed, then release it.
    // Reader mode rescans from disk on every call without taking the write claim.
    private def touchForRead(streamId: Event.StreamId, ref: AtomicRef.Unsafe[StreamState], log: Log.Unsafe)(using
        AllowUnsafe,
        Frame
    ): Result[IndexFailure, StreamState] < S =
        if readerMode then refreshFromDisk(streamId, ref, log)
        else ensureFirstTouch(streamId, ref, log)
    end touchForRead

    private def refreshFromDisk(streamId: Event.StreamId, ref: AtomicRef.Unsafe[StreamState], log: Log.Unsafe)(using
        AllowUnsafe,
        Frame
    ): Result[IndexFailure, StreamState] < S =
        recover(streamId, log).map:
            case Result.Failure(err) => Result.fail(err)
            case Result.Success(recovered) =>
                ref.set(recovered)
                Result.succeed(recovered)
    end refreshFromDisk

    private def ensureFirstTouch(streamId: Event.StreamId, ref: AtomicRef.Unsafe[StreamState], log: Log.Unsafe)(using
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
        streamId: Event.StreamId,
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

    private def recover(streamId: Event.StreamId, log: Log.Unsafe)(using AllowUnsafe, Frame): Result[IndexFailure, StreamState] < S =
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
    private def scanSegments(streamId: Event.StreamId, segFiles: Chunk[Path], log: Log.Unsafe)(using
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
                                            val truncateTorn: Unit < S =
                                                if readerMode then ((): Unit < S)
                                                else
                                                    tornAt match
                                                        case Present(from) if isActive =>
                                                            handle.truncate(committedEnd).map { _ =>
                                                                log.warn(
                                                                    s"FileJournal recovered stream '${streamId.value}': truncated torn tail of segment '${segPath.unsafe.show}' from byte $from to $committedEnd"
                                                                )(using frame, summon[AllowUnsafe])
                                                            }
                                                        case _ => ((): Unit < S)
                                            truncateTorn.map { _ =>
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

    private def offsetInSegments(s: StreamState, off: Long): Boolean =
        s.segments.exists { e =>
            off >= e.baseOffset && off < e.baseOffset + e.recordPositions.size.toLong
        }
    end offsetInSegments

    private def readRangeWithRelist(
        streamId: Event.StreamId,
        ref: AtomicRef.Unsafe[StreamState],
        s: StreamState,
        fromOff: Long,
        toOff: Long,
        log: Log.Unsafe
    )(using AllowUnsafe, Frame): Result[JournalReadFailure, Chunk[Event.Committed]] < S =
        Loop.indexed(s, false) { (idx, state, relisted) =>
            if readerMode && !relisted && fromOff <= state.durableOffset && !offsetInSegments(state, fromOff) then
                refreshFromDisk(streamId, ref, log).map:
                    case Result.Failure(err)   => Loop.done(Result.fail(err))
                    case Result.Success(fresh) => Loop.continue(fresh, true)
                    case Result.Panic(e)       => throw e
            else
                readRange(streamId, state, fromOff, toOff).map(r => Loop.done(r))
        }

    private def readRange(streamId: Event.StreamId, s: StreamState, fromOff: Long, toOff: Long)(using
        AllowUnsafe,
        Frame
    ): Result[JournalReadFailure, Chunk[Event.Committed]] < S =
        catchStorageError[Result[JournalReadFailure, Chunk[Event.Committed]]](s"Read of stream '${streamId.value}' failed") {
            Loop.indexed(Chunk.empty[Event.Committed], fromOff) { (_, out, off) =>
                if off > toOff then
                    Loop.done[Chunk[Event.Committed], Long, Result[JournalReadFailure, Chunk[Event.Committed]]](Result.succeed(out))
                else
                    val seg    = segmentFor(s, off)
                    val handle = handleFor(seg.path)
                    handle.map { h =>
                        val pos = seg.recordPositions((off - seg.baseOffset).toInt)
                        codec.decodeRecordAt(h, pos).map:
                            case Result.Failure(detail) =>
                                Loop.done[Chunk[Event.Committed], Long, Result[JournalReadFailure, Chunk[Event.Committed]]](
                                    Result.fail(JournalCorruptedError(Present(streamId), detail))
                                )
                            case Result.Success(dec) =>
                                rebuild(streamId, dec) match
                                    case Result.Failure(err) =>
                                        Loop.done[Chunk[Event.Committed], Long, Result[JournalReadFailure, Chunk[Event.Committed]]](
                                            Result.fail(err)
                                        )
                                    case Result.Success(ev) => Loop.continue(out :+ ev, off + 1L)
                    }
            }
        }.map {
            case Result.Success(inner) => inner
            case failure               => failure.asInstanceOf[Result[JournalReadFailure, Chunk[Event.Committed]]]
        }
    end readRange

    private def rebuild(streamId: Event.StreamId, dec: DecodedRecord)(using AllowUnsafe): Result[JournalReadFailure, Event.Committed] =
        val built =
            for
                eid <- Event.Id(dec.eventId)
                etp <- Event.Type(dec.eventType)
                // decodeMetadata uses the configured metadata codec; both BinarySegmentFormat.decodeRecordAt and
                // JsonlSegmentFormat.decodeRecordAt store metadata in the selected binary shadow form in DecodedRecord.
                md <- FileJournalCore.decodeMetadata(metadataCodec, dec.metadata)
            yield Event.Committed(
                streamId = streamId,
                offset = Event.StreamOffset.fromUnchecked(dec.offset),
                id = eid,
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

    private def streamDir(streamId: Event.StreamId): Path = streamsDir / BinarySegmentFormat.encodeStreamId(streamId)

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
            val last = Event.StreamOffset.fromUnchecked(s.durableOffset)
            StreamInfo.Existing(Event.StreamVersion.after(last), last)

    // Written-view: what the append conflict check compares `expected` against. Same-stream appends
    // are already serialized by the claim (CAS spin or parked permit), so this always reflects every
    // prior appender's write by the time the next one is granted the claim, even if that prior
    // append's own durability flush is still in flight.
    private def writtenInfoOf(s: StreamState): StreamInfo =
        if s.lastOffset < 0L then StreamInfo.Absent
        else
            val last = Event.StreamOffset.fromUnchecked(s.lastOffset)
            StreamInfo.Existing(Event.StreamVersion.after(last), last)

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
    private var heldRootsCell: Maybe[AtomicRef.Unsafe[Set[String]]] = Absent

    private def heldRoots(using AllowUnsafe): AtomicRef.Unsafe[Set[String]] =
        heldRootsCell match
            case Present(r) => r
            case Absent =>
                val r = AtomicRef.Unsafe.init(Set.empty[String])
                heldRootsCell = Present(r)
                r

    @tailrec private[kyo] def registerRoot(key: String)(using AllowUnsafe): Boolean =
        val snap = heldRoots.get()
        if snap.contains(key) then false
        else if heldRoots.compareAndSet(snap, snap + key) then true
        else registerRoot(key)
    end registerRoot

    @tailrec private[kyo] def unregisterRoot(key: String)(using AllowUnsafe): Unit =
        val snap = heldRoots.get()
        if !heldRoots.compareAndSet(snap, snap - key) then unregisterRoot(key)

    // Internal discriminator selecting which shipped SegmentFormat a Configuration's profile maps
    // to; never part of the public surface. The shipped engine honors exactly these two built-in
    // families, selected by the literal profileName string ("binary" or "jsonl") a Configuration
    // carries, so this string switch is total over every value FileJournal.Configuration#profileName
    // can ever carry.
    private[kyo] enum SegmentFamilyKind derives CanEqual:
        case Binary
        case Jsonl
    end SegmentFamilyKind

    private def familyKindOf(profileName: String)(using Frame): Result[JournalStorageError, SegmentFamilyKind] =
        profileName match
            case "binary" => Result.succeed(SegmentFamilyKind.Binary)
            case "jsonl"  => Result.succeed(SegmentFamilyKind.Jsonl)
            case other =>
                Result.fail(JournalStorageError(
                    s"custom families beyond Binary and Jsonl have no shipped SegmentFormat; implement Journal.Backend[S] directly (profileName '$other')",
                    Absent
                ))

    private def codecFor(
        kind: SegmentFamilyKind,
        metadataCodec: EventLogCodecs.MetadataCodec,
        valueCodec: EventLogCodecs.ValueCodec[?]
    ): SegmentFormat =
        kind match
            case SegmentFamilyKind.Binary => new BinarySegmentFormat(metadataCodec)
            case SegmentFamilyKind.Jsonl  => new JsonlSegmentFormat(valueCodec, metadataCodec)

    /** Opens (or creates) a file-backed journal rooted at `dir`, acquiring the single-owner lock
      * via the supplied [[StoreSeam]]. Internal; the public entry points are [[openSync]] and
      * [[openAsync]], which resolve the platform seam themselves.
      */
    private[kyo] def open[A, S >: (Async & Abort[Throwable]) <: Sync](
        dir: Path,
        configuration: FileJournal.Configuration[A],
        seam: StoreSeam[S],
        claimSeam: ClaimSeam[S],
        flushStrategyFor: FileJournal.Fsync => FlushStrategy[S]
    )(using
        frame: Frame
    ): FileJournal.Backend[A, S] < (Sync & Scope & Abort[JournalStorageError]) =
        Scope.acquireRelease(
            // Unsafe: creates the root, streams/ via Path.Unsafe and acquires the platform lock via
            // the injected StoreSeam; raw platform I/O is the only path to locking.
            Sync.Unsafe.defer(FileJournalCore.acquire(dir, configuration, seam, claimSeam, flushStrategyFor).map(Abort.get))
        )(backend =>
            // Unsafe: bridges the raw platform release calls (lock release, handle closes) into the
            // Sync tier for the Scope.acquireRelease finalization callback.
            Sync.Unsafe.defer(backend.release())
        )

    /** Opens a typed Sync file-backed journal over `dir` using `configuration`, resolving the
      * platform's synchronous [[StoreSeam]] itself (`kyo.platformSyncStore`, package `kyo`, one
      * `given`-free `def` per platform tree).
      */
    private[kyo] def openSync[A](
        dir: Path,
        configuration: FileJournal.Configuration[A]
    )(using frame: Frame): FileJournal.Backend[A, Sync] < (Sync & Scope & Abort[JournalStorageError]) =
        open(dir, configuration, kyo.platformSyncStore(), ClaimSeam.sync, FlushStrategy.inline)

    /** Opens a typed Async file-backed journal over `dir`, bootstrapping the async claim seam and
      * group-commit coordinator and resolving the platform's asynchronous [[StoreSeam]] itself
      * (`kyo.platformAsyncStore`).
      */
    private[kyo] def openAsync[A](
        dir: Path,
        configuration: FileJournal.Configuration[A]
    )(using frame: Frame): FileJournal.Backend[A, Async] < (Sync & Scope & Abort[JournalStorageError]) =
        // Unsafe: bootstraps in-process claim permits and group-commit coordinator maps.
        Sync.Unsafe.defer {
            val coordinator = GroupCommitCoordinator.init
            (ClaimSeam.async(), (fsync: FileJournal.Fsync) => FlushStrategy.groupCommit(fsync, coordinator))
        }.flatMap { (claim, flushFor) =>
            open(dir, configuration, kyo.platformAsyncStore(), claim, flushFor)
        }

    /** Opens an existing journal root for read-only access, skipping the writer lock and in-process
      * root registration. Resolves the platform's read-only synchronous [[StoreSeam]] itself.
      */
    private[kyo] def openReader[A](
        dir: Path,
        configuration: FileJournal.Configuration[A]
    )(using frame: Frame): FileJournal.Reader[A, Sync] < (Sync & Scope & Abort[JournalStorageError]) =
        Scope.acquireRelease(
            // Unsafe: validates the root via Path.Unsafe and constructs the read-only core without
            // acquiring the platform writer lock.
            Sync.Unsafe.defer(FileJournalCore.acquireReader(dir, configuration, kyo.platformSyncStore(isReadOnly = true)).map(Abort.get))
        )(reader =>
            // Unsafe: bridges handle closes into the Sync tier for the Scope.acquireRelease finalizer.
            Sync.Unsafe.defer(reader.release())
        )

    private def acquireReader[A, S >: (Async & Abort[Throwable]) <: Sync](
        dir: Path,
        configuration: FileJournal.Configuration[A],
        seam: StoreSeam[S]
    )(using
        frame: Frame,
        allow: AllowUnsafe
    ): Result[JournalStorageError, FileJournalCore[A, S]] < Sync =
        val rootKey = dir.unsafe.show
        if !dir.unsafe.exists() then
            Result.fail(JournalStorageError(s"Journal root '${dir.unsafe.show}' does not exist", Absent))
        else if !dir.unsafe.isDirectory() then
            Result.fail(JournalStorageError(s"Journal root '${dir.unsafe.show}' exists and is not a directory", Absent))
        else
            val streamsDir = dir / "streams"
            familyKindOf(configuration.profileName).flatMap { requestedKind =>
                validateFormatMarker(
                    dir,
                    requestedKind,
                    configuration.profileName,
                    configuration.payloadMediaType,
                    configuration.metadataMediaType,
                    writeIfMissing = false
                ).map { validatedKind =>
                    val codec = codecFor(validatedKind, configuration.codecs.metadata, configuration.codecs.value)
                    val noOpLock = new SegmentStore.Lock:
                        def release()(using AllowUnsafe): Unit = ()
                    new FileJournalCore[A, S](
                        rootKey,
                        streamsDir,
                        configuration.options,
                        configuration.codecs.metadata,
                        seam,
                        noOpLock,
                        codec,
                        configuration.codecs.value,
                        ClaimSeam.noop,
                        FlushStrategy.noop,
                        AtomicRef.Unsafe.init(Map.empty),
                        AtomicRef.Unsafe.init(Map.empty),
                        configuration.journalId,
                        readerMode = true
                    )
                }
            }
        end if
    end acquireReader

    private def acquire[A, S >: (Async & Abort[Throwable]) <: Sync](
        dir: Path,
        configuration: FileJournal.Configuration[A],
        seam: StoreSeam[S],
        claimSeam: ClaimSeam[S],
        flushStrategyFor: FileJournal.Fsync => FlushStrategy[S]
    )(using
        frame: Frame,
        allow: AllowUnsafe
    ): Result[JournalStorageError, FileJournalCore[A, S]] < Sync =
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
                    familyKindOf(configuration.profileName) match
                        case Result.Failure(err) =>
                            // Unsafe: release the lock before propagating the family-kind error.
                            discard(Result.catching[Throwable](acquiredLock.release()))
                            unregisterRoot(rootKey)
                            Result.fail(err)
                        case Result.Success(requestedKind) =>
                            // MANIFEST check/write runs after lock acquisition to prevent a TOCTOU
                            // race where two processes both see a fresh root and both write MANIFEST.
                            checkOrWriteFormatMarker(
                                dir,
                                requestedKind,
                                configuration.profileName,
                                configuration.payloadMediaType,
                                configuration.metadataMediaType
                            ) match
                                case Result.Failure(err) =>
                                    // Unsafe: release the lock before propagating the format error.
                                    discard(Result.catching[Throwable](acquiredLock.release()))
                                    unregisterRoot(rootKey)
                                    Result.fail(err)
                                case Result.Success(validatedKind) =>
                                    val codec = codecFor(validatedKind, configuration.codecs.metadata, configuration.codecs.value)
                                    Result.succeed(
                                        new FileJournalCore[A, S](
                                            rootKey,
                                            streamsDir,
                                            configuration.options,
                                            configuration.codecs.metadata,
                                            seam,
                                            acquiredLock,
                                            codec,
                                            configuration.codecs.value,
                                            claimSeam,
                                            flushStrategyFor(configuration.options.fsync),
                                            AtomicRef.Unsafe.init(Map.empty),
                                            AtomicRef.Unsafe.init(Map.empty),
                                            configuration.journalId
                                        )
                                    )
                            end match
            }
        end if
    end acquire

    // Reads or writes the MANIFEST marker file in `dir`: a structured record of the profile
    // family, layout version, and the payload/metadata media types resolved at Configuration
    // construction time. Must be called AFTER the process-level lock is held to prevent
    // TOCTOU races between concurrent opens of the same root.
    //
    // Rules:
    //   - No MANIFEST file + no existing stream directories: this is a fresh journal; write
    //     MANIFEST from requestedFormat/profileName/media types; proceed.
    //   - No MANIFEST file + existing stream directories: a MANIFEST-absent root with segments is
    //     either a legacy binary root created before the MANIFEST marker existed, or a
    //     crash-partial journal that never completed its first-open MANIFEST write; both infer
    //     Binary from segment presence. If requestedFormat != Binary: fail loud. If
    //     requestedFormat == Binary: proceed without writing MANIFEST (future opens infer Binary
    //     again; media types fall back to Binary's built-in defaults).
    //   - MANIFEST file with matching format: proceed.
    //   - MANIFEST file with mismatched format: fail with typed JournalStorageError.
    //   - MANIFEST file with unknown format value: fail with typed JournalStorageError.
    // Read-only MANIFEST validation: never writes the marker file.
    private def validateFormatMarker(
        dir: Path,
        requestedFormat: SegmentFamilyKind,
        profileName: String,
        payloadMediaType: String,
        metadataMediaType: String,
        writeIfMissing: Boolean
    )(using frame: Frame, allow: AllowUnsafe): Result[JournalStorageError, SegmentFamilyKind] =
        val formatFile = dir / "MANIFEST"
        if formatFile.unsafe.exists() then
            formatFile.unsafe.readBytes() match
                case Result.Failure(e) =>
                    Result.fail(JournalStorageError(s"Cannot read MANIFEST file in '${dir.unsafe.show}'", Present(e)))
                case Result.Success(bytes) =>
                    parseFormatMarker(dir, bytes, requestedFormat)
        else
            // No MANIFEST file. Check whether the streams/ directory has any entries, which would
            // indicate a legacy or crash-partial binary root created before the MANIFEST marker
            // was durably written.
            val streamsDir = dir / "streams"
            val hasStreams = streamsDir.unsafe.exists() && (streamsDir.unsafe.list() match
                case Result.Success(paths) => paths.nonEmpty
                case Result.Failure(_)     => false)
            if hasStreams && requestedFormat != SegmentFamilyKind.Binary then
                Result.fail(JournalStorageError(
                    s"Journal root '${dir.unsafe.show}' has existing segments but no MANIFEST marker; " +
                        "inferred Binary; Config requests Jsonl",
                    Absent
                ))
            else if hasStreams then
                // MANIFEST-absent, segments present: infer Binary (legacy or crash-partial root).
                // Do not write MANIFEST; future opens will infer Binary from segment presence again.
                Result.succeed(SegmentFamilyKind.Binary)
            else if writeIfMissing then
                val content =
                    s"format: $profileName\nversion: 1\npayload-media-type: $payloadMediaType\nmetadata-media-type: $metadataMediaType\n"
                formatFile.unsafe.writeBytes(Span.from(content.getBytes(Utf8))) match
                    case Result.Failure(e) =>
                        Result.fail(JournalStorageError(
                            s"Cannot write MANIFEST file in '${dir.unsafe.show}'",
                            Present(e)
                        ))
                    case Result.Success(_) =>
                        Result.succeed(requestedFormat)
                end match
            else
                Result.fail(JournalStorageError(
                    s"Journal root '${dir.unsafe.show}' has no MANIFEST marker and no segments",
                    Absent
                ))
            end if
        end if
    end validateFormatMarker

    private def checkOrWriteFormatMarker(
        dir: Path,
        requestedFormat: SegmentFamilyKind,
        profileName: String,
        payloadMediaType: String,
        metadataMediaType: String
    )(using frame: Frame, allow: AllowUnsafe): Result[JournalStorageError, SegmentFamilyKind] =
        validateFormatMarker(dir, requestedFormat, profileName, payloadMediaType, metadataMediaType, writeIfMissing = true)
    end checkOrWriteFormatMarker

    private def parseFormatMarker(
        dir: Path,
        bytes: Span[Byte],
        requestedFormat: SegmentFamilyKind
    )(using Frame): Result[JournalStorageError, SegmentFamilyKind] =
        val content = new String(bytes.toArray, Utf8)
        val pairs = content.split("\n").flatMap { line =>
            val idx = line.indexOf(": ")
            if idx < 0 then Array.empty[(String, String)]
            else Array((line.substring(0, idx).trim, line.substring(idx + 2).trim))
        }.toMap
        // payload-media-type / metadata-media-type are descriptive: read here for future
        // consumers but never fail an open on their absence or mismatch, unlike format/version.
        pairs.get("format") match
            case None =>
                Result.fail(JournalStorageError(
                    s"MANIFEST file in '${dir.unsafe.show}' has no 'format' key",
                    Absent
                ))
            case Some(diskFormatStr) =>
                val diskFormatResult = diskFormatStr match
                    case "binary" => Result.succeed(SegmentFamilyKind.Binary)
                    case "jsonl"  => Result.succeed(SegmentFamilyKind.Jsonl)
                    case other =>
                        Result.fail(JournalStorageError(
                            s"MANIFEST file in '${dir.unsafe.show}' has unknown format value '$other'",
                            Absent
                        ))
                diskFormatResult.flatMap { df =>
                    // Validate version before the format-mismatch check so an unknown version
                    // is always loud, even when the format value happens to match.
                    val versionCheck = pairs.get("version") match
                        case Some("1") => Result.succeed(())
                        case Some(v) =>
                            Result.fail(JournalStorageError(
                                s"MANIFEST file in '${dir.unsafe.show}' has version '$v'; supported: 1",
                                Absent
                            ))
                        case None =>
                            Result.fail(JournalStorageError(
                                s"MANIFEST file in '${dir.unsafe.show}' has no 'version' key",
                                Absent
                            ))
                    versionCheck.flatMap { _ =>
                        if df != requestedFormat then
                            val reqStr = requestedFormat match
                                case SegmentFamilyKind.Binary => "binary"
                                case SegmentFamilyKind.Jsonl  => "jsonl"
                            Result.fail(JournalStorageError(
                                s"Journal root '${dir.unsafe.show}' was created as $diskFormatStr; Config requests $reqStr",
                                Absent
                            ))
                        else Result.succeed(df)
                    }
                }
        end match
    end parseFormatMarker

    // --- metadata version-byte framing -------------------------------------------------------

    /** Metadata version byte for MsgPack-encoded bodies (legacy). */
    private[kyo] val MetadataVersionMsgPack: Byte = 0x01

    /** Metadata version byte for the configured-codec-encoded bodies (current). */
    private[kyo] val MetadataVersionCurrent: Byte = 0x02

    /** Encodes metadata through the Configuration's [[EventLogCodecs.MetadataCodec]], version-byte
      * framed. A MsgPack-wrapped metadata codec writes the legacy `0x01` version for wire
      * continuity; every other wrapped codec writes the current `0x02` version.
      */
    private[kyo] def encodeMetadata(metadataCodec: EventLogCodecs.MetadataCodec, md: Event.Metadata): Array[Byte] =
        val version = metadataCodec.codec match
            case _: MsgPack => MetadataVersionMsgPack
            case _          => MetadataVersionCurrent
        val writer = metadataCodec.codec.newWriter()
        writer.mapStart(md.values.size)
        md.values.foreach((k, v) =>
            writer.field(k.value, 0); Event.Metadata.Value.write(writer, v)
        )
        writer.mapEnd()
        val body = writer.result().toArray
        val out  = new Array[Byte](1 + body.length)
        out(0) = version
        java.lang.System.arraycopy(body, 0, out, 1, body.length)
        out
    end encodeMetadata

    /** Decodes metadata written by [[encodeMetadata]]. `0x02` decodes through the Configuration's
      * wrapped codec; `0x01` always decodes through MsgPack (the legacy wire format, tolerated on
      * read regardless of the configured codec).
      */
    private[kyo] def decodeMetadata(
        metadataCodec: EventLogCodecs.MetadataCodec,
        bytes: Array[Byte]
    )(using Frame): Result[JournalInvalidIdentifierError, Event.Metadata] =
        if bytes.isEmpty then Result.succeed(Event.Metadata.empty)
        else
            bytes(0) match
                case MetadataVersionCurrent => decodeMetadataBody(bytes, metadataCodec.codec)
                case MetadataVersionMsgPack => decodeMetadataBody(bytes, MsgPack())
                case other =>
                    Result.fail(JournalInvalidIdentifierError(
                        "metadata encoding version",
                        s"unknown byte 0x${(other & 0xff).toHexString}"
                    ))
    end decodeMetadata

    private def decodeMetadataBody(
        bytes: Array[Byte],
        codec: Codec
    )(using Frame): Result[JournalInvalidIdentifierError, Event.Metadata] =
        val payload  = Span.from(java.util.Arrays.copyOfRange(bytes, 1, bytes.length))
        val reader   = codec.newReader(payload)
        val rawPairs = Chunk.newBuilder[(String, Event.Metadata.Value)]
        @tailrec
        def readFields(): Unit =
            if reader.hasNextField() then
                val keyStr = reader.field()
                val v      = Event.Metadata.Value.read(reader)
                rawPairs += (keyStr -> v)
                readFields()
        try
            discard(reader.objectStart())
            readFields()
            reader.objectEnd()
            val pairs = rawPairs.result().map((k, v) => Event.Metadata.Key(k).map(mk => (mk, v)))
            Result.collect(pairs).map(ps => Event.Metadata(ps.toMap))
        catch
            case e: DecodeException =>
                Result.fail(JournalInvalidIdentifierError("metadata value tag", e.getMessage))
        end try
    end decodeMetadataBody

end FileJournalCore
