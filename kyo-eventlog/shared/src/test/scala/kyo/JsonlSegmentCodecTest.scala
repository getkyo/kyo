package kyo

import java.nio.charset.StandardCharsets
import kyo.internal.BinarySegmentCodec
import kyo.internal.JsonlSegmentCodec
import kyo.internal.ScanResult
import kyo.internal.SegmentStore

/** Unit tests for [[JsonlSegmentCodec]] driven against an in-memory [[SegmentStore.Handle]]
  * so they run cross-platform without any file system. Covers: JSONL line format, CRC
  * integrity, scan tail recovery, payload transcoding, and torn-line edge cases.
  */
class JsonlSegmentCodecTest extends kyo.test.Test[Any]:

    private val Utf8  = StandardCharsets.UTF_8
    private val codec = new JsonlSegmentCodec(EventPayloadCodec.bytes)

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError(s"valid identifier: $r"))

    private def env(n: Int): EventEnvelope =
        EventEnvelope(
            valid(EventId(s"e-$n")),
            valid(EventType("T")),
            Span.from(s"p$n".getBytes(Utf8)),
            EventMetadata.empty
        )

    // --- in-memory segment store handle -------------------------------------------------------

    private def memHandle(initial: Array[Byte] = Array.emptyByteArray): SegmentStore.Handle =
        new SegmentStore.Handle:
            val buf                             = scala.collection.mutable.ArrayBuffer.from(initial)
            def size()(using AllowUnsafe): Long = buf.size.toLong
            def readAt(pos: Long, len: Int)(using AllowUnsafe): Array[Byte] =
                val from = pos.toInt
                val to   = math.min(from + len, buf.size)
                if from >= buf.size then Array.emptyByteArray
                else buf.slice(from, to).toArray
            end readAt
            def writeAt(pos: Long, bytes: Array[Byte])(using AllowUnsafe): Unit =
                val needed = pos.toInt + bytes.length
                while buf.size < needed do buf += 0.toByte
                bytes.indices.foreach(i => buf(pos.toInt + i) = bytes(i))
            end writeAt
            def sync()(using AllowUnsafe): Unit = ()
            def truncate(newSize: Long)(using AllowUnsafe): Unit =
                while buf.size > newSize.toInt do discard(buf.remove(buf.size - 1))
            def close()(using AllowUnsafe): Unit = ()

    // Writes a batch starting at `startPos`, returns the handle and the batch bytes.
    private def writeBatch(
        firstOffset: Long,
        events: Chunk[EventEnvelope],
        startPos: Long = 0L
    )(using
        AllowUnsafe
    )
        : (SegmentStore.Handle, Array[Byte]) =
        val bytes  = codec.frameBatch(firstOffset, events)
        val handle = memHandle()
        handle.writeAt(startPos, bytes)
        (handle, bytes)
    end writeBatch

    // --- JSONL line format round-trip --------------------------------------------------------

    "JSONL line format" - {
        "frameBatch produces events.length + 1 newline-terminated lines" in {
            import AllowUnsafe.embrace.danger
            val events = Chunk(env(0), env(1), env(2))
            val bytes  = codec.frameBatch(0L, events)
            val text   = new String(bytes, Utf8)
            // split on '\n' with -1 limit includes the trailing empty string after the last '\n'
            val lines = text.split("\n", -1).dropRight(1) // drop trailing empty
            assert(lines.length == events.length + 1)
            assert(lines.last.startsWith("""{"commit":"""))
            assert(lines(0).startsWith("""{"offset":0,"""))
            assert(lines(1).startsWith("""{"offset":1,"""))
        }
        "each event line carries the expected field values" in {
            import AllowUnsafe.embrace.danger
            val events = Chunk(env(0))
            val bytes  = codec.frameBatch(5L, events)
            val line   = new String(bytes, Utf8).linesIterator.next()
            assert(line.startsWith("""{"offset":5,"""))
            assert(line.contains(""""eventId":"e-0""""))
            assert(line.contains(""""eventType":"T""""))
            assert(line.contains(""""metadata":"""))
            assert(line.contains(""""payload":"""))
            assert(line.contains(""""crc":"0x"""))
        }
        "decodeRecordAt round-trips offset, eventId, eventType, and payload" in {
            import AllowUnsafe.embrace.danger
            val events          = Chunk(env(0), env(1))
            val (handle, bytes) = writeBatch(7L, events)
            val positions       = codec.extractPositions(7L, events, bytes, 0L)
            codec.decodeRecordAt(handle, positions(0)) match
                case Result.Success(dec) =>
                    assert(dec.offset == 7L)
                    assert(dec.eventId == "e-0")
                    assert(dec.eventType == "T")
                    // BytesPayloadCodec is identity: payload bytes are the raw event bytes.
                    assert(java.util.Arrays.equals(dec.payload, "p0".getBytes(Utf8)))
                case other =>
                    assert(false, s"expected Success for record 0, got $other")
            end match
            codec.decodeRecordAt(handle, positions(1)) match
                case Result.Success(dec) =>
                    assert(dec.offset == 8L)
                    assert(dec.eventId == "e-1")
                case other =>
                    assert(false, s"expected Success for record 1, got $other")
            end match
        }
        "extractPositions aligns with actual newline positions in the batch bytes" in {
            import AllowUnsafe.embrace.danger
            val events    = Chunk(env(0), env(1), env(2))
            val bytes     = codec.frameBatch(0L, events)
            val positions = codec.extractPositions(0L, events, bytes, 0L)
            assert(positions.length == 3)
            // Each position must point to the start of a `{"offset":N,` JSON object.
            positions.zipWithIndex.foreach { (pos, i) =>
                val lineStart = new String(bytes, pos.toInt, math.min(12, bytes.length - pos.toInt), Utf8)
                assert(lineStart.startsWith(s"""{"offset":$i,"""))
            }
        }
        "decodeRecordAt preserves the exact offset for values at the number-precision boundary" in {
            import AllowUnsafe.embrace.danger
            // 2^53 - 1 = 9007199254740991 is MAX_SAFE_INTEGER in JavaScript; the decimal JSON
            // encoding must round-trip the value exactly so offsets at this boundary are not truncated.
            val events   = Chunk(env(0))
            val jsMax    = 9007199254740991L
            val (h1, b1) = writeBatch(jsMax, events)
            val p1       = codec.extractPositions(jsMax, events, b1, 0L)
            codec.decodeRecordAt(h1, p1(0)) match
                case Result.Success(dec) => assert(dec.offset == jsMax)
                case other               => assert(false, s"expected success at 2^53-1 offset, got: $other")
            end match
            val nearMax  = Long.MaxValue - 1L
            val (h2, b2) = writeBatch(nearMax, events)
            val p2       = codec.extractPositions(nearMax, events, b2, 0L)
            codec.decodeRecordAt(h2, p2(0)) match
                case Result.Success(dec) => assert(dec.offset == nearMax)
                case other               => assert(false, s"expected success at Long.MaxValue-1 offset, got: $other")
            end match
        }
    }

    // --- scan tail recovery -------------------------------------------------------------------

    "scan" - {
        "a fully committed single-event batch is recovered with one position" in {
            import AllowUnsafe.embrace.danger
            val events          = Chunk(env(0))
            val (handle, bytes) = writeBatch(0L, events)
            codec.scan(handle, handle.size(), isActive = false) match
                case ScanResult.Ok(positions, committedEnd, tornAt) =>
                    assert(positions.length == 1)
                    assert(tornAt == Absent)
                    assert(committedEnd == handle.size())
                case other =>
                    assert(false, s"expected Ok, got $other")
            end match
        }
        "a fully committed multi-event batch is recovered with all positions" in {
            import AllowUnsafe.embrace.danger
            val events          = Chunk(env(0), env(1), env(2))
            val (handle, bytes) = writeBatch(0L, events)
            codec.scan(handle, handle.size(), isActive = false) match
                case ScanResult.Ok(positions, committedEnd, tornAt) =>
                    assert(positions.length == 3)
                    assert(tornAt == Absent)
                case other =>
                    assert(false, s"expected Ok, got $other")
            end match
        }
        "two sequential committed batches are both recovered" in {
            import AllowUnsafe.embrace.danger
            val batch1   = codec.frameBatch(0L, Chunk(env(0)))
            val batch2   = codec.frameBatch(1L, Chunk(env(1), env(2)))
            val combined = batch1 ++ batch2
            val handle   = memHandle(combined)
            codec.scan(handle, combined.length.toLong, isActive = false) match
                case ScanResult.Ok(positions, committedEnd, tornAt) =>
                    assert(positions.length == 3)
                    assert(tornAt == Absent)
                    assert(committedEnd == combined.length.toLong)
                case other =>
                    assert(false, s"expected Ok, got $other")
            end match
        }
        "an event line without a following commit line in active segment yields Ok+tornAt" in {
            import AllowUnsafe.embrace.danger
            val events    = Chunk(env(0))
            val bytes     = codec.frameBatch(0L, events)
            val positions = codec.extractPositions(0L, events, bytes, 0L)
            // Keep only the event line bytes (find the first '\n' and truncate there inclusive).
            var nlIdx = positions(0).toInt
            while nlIdx < bytes.length && bytes(nlIdx) != '\n'.toByte do nlIdx += 1
            val truncated = bytes.take(nlIdx + 1) // event line + '\n', no commit line
            val handle    = memHandle(truncated)
            codec.scan(handle, truncated.length.toLong, isActive = true) match
                case ScanResult.Ok(committed, committedEnd, Present(batchStart)) =>
                    assert(committed.isEmpty)
                    assert(batchStart == 0L)
                case other =>
                    assert(false, s"expected Ok with tornAt, got $other")
            end match
        }
        "an event line without a following commit line in sealed segment is Corrupt" in {
            import AllowUnsafe.embrace.danger
            val events    = Chunk(env(0))
            val bytes     = codec.frameBatch(0L, events)
            val positions = codec.extractPositions(0L, events, bytes, 0L)
            var nlIdx     = positions(0).toInt
            while nlIdx < bytes.length && bytes(nlIdx) != '\n'.toByte do nlIdx += 1
            val truncated = bytes.take(nlIdx + 1)
            val handle    = memHandle(truncated)
            codec.scan(handle, truncated.length.toLong, isActive = false) match
                case ScanResult.Corrupt(msg) => assert(msg.contains("sealed segment"))
                case other                   => assert(false, s"expected Corrupt, got $other")
        }
    }

    // --- CRC integrity ------------------------------------------------------------------------

    "CRC verification" - {
        "a flipped bit in a record line fails decodeRecordAt" in {
            import AllowUnsafe.embrace.danger
            val events          = Chunk(env(0))
            val (handle, bytes) = writeBatch(0L, events)
            val positions       = codec.extractPositions(0L, events, bytes, 0L)
            // Flip a byte at offset 12 within the event line (inside `"eventId"...`).
            val arr = handle.readAt(0L, handle.size().toInt)
            val pos = positions(0).toInt + 12
            arr(pos) = (arr(pos) ^ 0xff).toByte
            handle.writeAt(0L, arr)
            codec.decodeRecordAt(handle, positions(0)) match
                case Result.Failure(msg) => assert(msg.contains("CRC mismatch"))
                case other               => assert(false, s"expected Failure, got $other")
        }
        "a CRC-failed record line in active segment with no following commit is a torn tail" in {
            import AllowUnsafe.embrace.danger
            val events    = Chunk(env(0))
            val bytes     = codec.frameBatch(0L, events)
            val positions = codec.extractPositions(0L, events, bytes, 0L)
            // Compute end of event line.
            var nlIdx = positions(0).toInt
            while nlIdx < bytes.length && bytes(nlIdx) != '\n'.toByte do nlIdx += 1
            // Keep only the event line (no commit line follows).
            val arr = bytes.take(nlIdx + 1)
            // Flip a byte inside the event line.
            arr(positions(0).toInt + 8) = (arr(positions(0).toInt + 8) ^ 0xff).toByte
            val handle = memHandle(arr)
            codec.scan(handle, arr.length.toLong, isActive = true) match
                case ScanResult.Ok(committed, _, Present(_)) => assert(committed.isEmpty)
                case other                                   => assert(false, s"expected Ok torn, got $other")
        }
    }

    // --- payload transcoding ------------------------------------------------------------------

    "payload transcoding" - {
        "bytes codec encodeForJsonl returns a JSON string literal (including surrounding quotes)" in {
            import AllowUnsafe.embrace.danger
            val raw    = "hello".getBytes(Utf8)
            val result = EventPayloadCodec.bytes.encodeForJsonl(Span.from(raw))
            result match
                case Result.Success(s) =>
                    assert(s.startsWith("\""))
                    assert(s.endsWith("\""))
                    val inner   = s.substring(1, s.length - 1)
                    val decoded = java.util.Base64.getDecoder.decode(inner)
                    assert(java.util.Arrays.equals(decoded, raw))
                case other =>
                    assert(false, s"expected Success, got $other")
            end match
        }
        "bytes codec decodeFromJsonl inverts encodeForJsonl" in {
            import AllowUnsafe.embrace.danger
            val raw = Span.from(Array[Byte](0, 1, 2, 127, -1))
            val encoded = EventPayloadCodec.bytes.encodeForJsonl(raw) match
                case Result.Success(s) => s
                case other             => throw new AssertionError(s"encode failed: $other")
            // Build a minimal JSON reader positioned at the string value.
            val jsonBytes = encoded.getBytes(Utf8)
            val reader    = new Json().newReader(Span.from(jsonBytes))(using Frame.internal)
            EventPayloadCodec.bytes.decodeFromJsonl(reader) match
                case Result.Success(decoded) => assert(java.util.Arrays.equals(decoded.toArray, raw.toArray))
                case other                   => assert(false, s"expected Success, got $other")
        }
        "JSONL frame round-trips arbitrary binary payloads end-to-end" in {
            import AllowUnsafe.embrace.danger
            val rawPayload = Span.from(Array[Byte](0, 1, 2, 127, -1))
            val e          = EventEnvelope(valid(EventId("eid")), valid(EventType("T")), rawPayload, EventMetadata.empty)
            val bytes      = codec.frameBatch(0L, Chunk(e))
            val handle     = memHandle(bytes)
            val positions  = codec.extractPositions(0L, Chunk(e), bytes, 0L)
            codec.decodeRecordAt(handle, positions(0)) match
                case Result.Success(dec) =>
                    assert(java.util.Arrays.equals(dec.payload, rawPayload.toArray))
                case other =>
                    assert(false, s"expected Success, got $other")
            end match
        }
    }

    // --- torn JSONL line (no trailing newline) ------------------------------------------------

    "torn JSONL line (no trailing newline)" - {
        "a line without a terminating newline in active segment is treated as a torn tail" in {
            import AllowUnsafe.embrace.danger
            val events    = Chunk(env(0))
            val bytes     = codec.frameBatch(0L, events)
            val positions = codec.extractPositions(0L, events, bytes, 0L)
            // Slice out the event line bytes without its trailing '\n'.
            var nlIdx = positions(0).toInt
            while nlIdx < bytes.length && bytes(nlIdx) != '\n'.toByte do nlIdx += 1
            val partial = bytes.take(nlIdx) // event line WITHOUT '\n'
            val handle  = memHandle(partial)
            codec.scan(handle, partial.length.toLong, isActive = true) match
                case ScanResult.Ok(committed, _, Present(_)) => assert(committed.isEmpty)
                case other                                   => assert(false, s"expected Ok torn, got $other")
        }
        "a line without a terminating newline in sealed segment is Corrupt" in {
            import AllowUnsafe.embrace.danger
            val events    = Chunk(env(0))
            val bytes     = codec.frameBatch(0L, events)
            val positions = codec.extractPositions(0L, events, bytes, 0L)
            var nlIdx     = positions(0).toInt
            while nlIdx < bytes.length && bytes(nlIdx) != '\n'.toByte do nlIdx += 1
            val partial = bytes.take(nlIdx) // no '\n'
            val handle  = memHandle(partial)
            codec.scan(handle, partial.length.toLong, isActive = false) match
                case ScanResult.Corrupt(msg) => assert(msg.contains("sealed segment"))
                case other                   => assert(false, s"expected Corrupt, got $other")
        }
    }

end JsonlSegmentCodecTest
