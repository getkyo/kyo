package kyo

import java.nio.charset.StandardCharsets
import kyo.internal.BinarySegmentCodec
import kyo.internal.JsonlSegmentCodec
import kyo.internal.ScanResult
import kyo.internal.StoreSeam

/** Unit tests for [[JsonlSegmentCodec]] driven against an in-memory [[StoreSeam.Handle]] so they
  * run cross-platform without any file system. Covers: JSONL line format, CRC integrity, scan
  * tail recovery, payload transcoding, and torn-line edge cases.
  */
class JsonlSegmentCodecTest extends kyo.test.Test[Any]:

    private val Utf8  = StandardCharsets.UTF_8
    private val codec = new JsonlSegmentCodec(EventPayloadCodec.bytes, EventMetadataCodec.default)

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

    private def memHandle(initial: Array[Byte] = Array.emptyByteArray): StoreSeam.Handle[Sync] =
        new StoreSeam.Handle[Sync]:
            private val buf                      = scala.collection.mutable.ArrayBuffer.from(initial)
            def size()(using Frame): Long < Sync = Sync.defer(buf.size.toLong)
            def readAt(pos: Long, len: Int)(using Frame): Array[Byte] < Sync =
                Sync.defer {
                    val from = pos.toInt
                    val to   = math.min(from + len, buf.size)
                    if from >= buf.size then Array.emptyByteArray
                    else buf.slice(from, to).toArray
                }
            def writeAt(pos: Long, bytes: Array[Byte])(using Frame): Unit < Sync =
                Sync.defer {
                    val needed = pos.toInt + bytes.length
                    while buf.size < needed do buf += 0.toByte
                    bytes.indices.foreach(i => buf(pos.toInt + i) = bytes(i))
                }
            def sync()(using Frame): Unit < Sync = Sync.defer(())
            def truncate(newSize: Long)(using Frame): Unit < Sync =
                Sync.defer(while buf.size > newSize.toInt do discard(buf.remove(buf.size - 1)))
            def close()(using Frame): Unit < Sync = Sync.defer(())

    // Writes a batch starting at `startPos`, returns the handle and the batch bytes.
    private def writeBatch(
        firstOffset: Long,
        events: Chunk[EventEnvelope],
        startPos: Long = 0L
    )(using Frame): (StoreSeam.Handle[Sync], Array[Byte]) < Sync =
        val bytes  = codec.frameBatch(firstOffset, events)
        val handle = memHandle()
        handle.writeAt(startPos, bytes).andThen((handle, bytes))
    end writeBatch

    // --- JSONL line format round-trip --------------------------------------------------------

    "JSONL line format" - {
        "frameBatch produces events.length + 1 newline-terminated lines" in {
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
            val events = Chunk(env(0), env(1))
            for
                (handle, bytes) <- writeBatch(7L, events)
                positions = codec.extractPositions(7L, events, bytes, 0L)
                r0 <- codec.decodeRecordAt(handle, positions(0))
                r1 <- codec.decodeRecordAt(handle, positions(1))
            yield
                r0 match
                    case Result.Success(dec) =>
                        assert(dec.offset == 7L)
                        assert(dec.eventId == "e-0")
                        assert(dec.eventType == "T")
                        // BytesPayloadCodec is identity: payload bytes are the raw event bytes.
                        assert(java.util.Arrays.equals(dec.payload, "p0".getBytes(Utf8)))
                    case other =>
                        assert(false, s"expected Success for record 0, got $other")
                end match
                r1 match
                    case Result.Success(dec) =>
                        assert(dec.offset == 8L)
                        assert(dec.eventId == "e-1")
                    case other =>
                        assert(false, s"expected Success for record 1, got $other")
                end match
            end for
        }
        "extractPositions aligns with actual newline positions in the batch bytes" in {
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
            // 2^53 - 1 = 9007199254740991 is MAX_SAFE_INTEGER in JavaScript; the decimal JSON
            // encoding must round-trip the value exactly so offsets at this boundary are not truncated.
            val events  = Chunk(env(0))
            val jsMax   = 9007199254740991L
            val nearMax = Long.MaxValue - 1L
            for
                (h1, b1) <- writeBatch(jsMax, events)
                p1 = codec.extractPositions(jsMax, events, b1, 0L)
                r1       <- codec.decodeRecordAt(h1, p1(0))
                (h2, b2) <- writeBatch(nearMax, events)
                p2 = codec.extractPositions(nearMax, events, b2, 0L)
                r2 <- codec.decodeRecordAt(h2, p2(0))
            yield
                r1 match
                    case Result.Success(dec) => assert(dec.offset == jsMax)
                    case other               => assert(false, s"expected success at 2^53-1 offset, got: $other")
                r2 match
                    case Result.Success(dec) => assert(dec.offset == nearMax)
                    case other               => assert(false, s"expected success at Long.MaxValue-1 offset, got: $other")
            end for
        }
    }

    // --- scan tail recovery -------------------------------------------------------------------

    "scan" - {
        "a fully committed single-event batch is recovered with one position" in {
            val events = Chunk(env(0))
            for
                (handle, _) <- writeBatch(0L, events)
                size        <- handle.size()
                result      <- codec.scan(handle, size, isActive = false)
            yield result match
                case ScanResult.Ok(positions, committedEnd, tornAt) =>
                    assert(positions.length == 1)
                    assert(tornAt == Absent)
                    assert(committedEnd == size)
                case other =>
                    assert(false, s"expected Ok, got $other")
            end for
        }
        "a fully committed multi-event batch is recovered with all positions" in {
            val events = Chunk(env(0), env(1), env(2))
            for
                (handle, _) <- writeBatch(0L, events)
                size        <- handle.size()
                result      <- codec.scan(handle, size, isActive = false)
            yield result match
                case ScanResult.Ok(positions, _, tornAt) =>
                    assert(positions.length == 3)
                    assert(tornAt == Absent)
                case other =>
                    assert(false, s"expected Ok, got $other")
            end for
        }
        "two sequential committed batches are both recovered" in {
            val batch1   = codec.frameBatch(0L, Chunk(env(0)))
            val batch2   = codec.frameBatch(1L, Chunk(env(1), env(2)))
            val combined = batch1 ++ batch2
            val handle   = memHandle(combined)
            codec.scan(handle, combined.length.toLong, isActive = false).map {
                case ScanResult.Ok(positions, committedEnd, tornAt) =>
                    assert(positions.length == 3)
                    assert(tornAt == Absent)
                    assert(committedEnd == combined.length.toLong)
                case other =>
                    assert(false, s"expected Ok, got $other")
            }
        }
        "an event line without a following commit line in active segment yields Ok+tornAt" in {
            val events    = Chunk(env(0))
            val bytes     = codec.frameBatch(0L, events)
            val positions = codec.extractPositions(0L, events, bytes, 0L)
            // Keep only the event line bytes (find the first '\n' and truncate there inclusive).
            var nlIdx = positions(0).toInt
            while nlIdx < bytes.length && bytes(nlIdx) != '\n'.toByte do nlIdx += 1
            val truncated = bytes.take(nlIdx + 1) // event line + '\n', no commit line
            val handle    = memHandle(truncated)
            codec.scan(handle, truncated.length.toLong, isActive = true).map {
                case ScanResult.Ok(committed, _, Present(batchStart)) =>
                    assert(committed.isEmpty)
                    assert(batchStart == 0L)
                case other =>
                    assert(false, s"expected Ok with tornAt, got $other")
            }
        }
        "an event line without a following commit line in sealed segment is Corrupt" in {
            val events    = Chunk(env(0))
            val bytes     = codec.frameBatch(0L, events)
            val positions = codec.extractPositions(0L, events, bytes, 0L)
            var nlIdx     = positions(0).toInt
            while nlIdx < bytes.length && bytes(nlIdx) != '\n'.toByte do nlIdx += 1
            val truncated = bytes.take(nlIdx + 1)
            val handle    = memHandle(truncated)
            codec.scan(handle, truncated.length.toLong, isActive = false).map {
                case ScanResult.Corrupt(msg) => assert(msg.contains("sealed segment"))
                case other                   => assert(false, s"expected Corrupt, got $other")
            }
        }
    }

    // --- CRC integrity ------------------------------------------------------------------------

    "CRC verification" - {
        "a flipped bit in a record line fails decodeRecordAt" in {
            val events = Chunk(env(0))
            for
                (handle, bytes) <- writeBatch(0L, events)
                positions = codec.extractPositions(0L, events, bytes, 0L)
                size <- handle.size()
                arr  <- handle.readAt(0L, size.toInt)
                // Flip a byte at offset 12 within the event line (inside `"eventId"...`).
                flipped =
                    val pos = positions(0).toInt + 12; arr(pos) = (arr(pos) ^ 0xff).toByte; arr
                _      <- handle.writeAt(0L, flipped)
                result <- codec.decodeRecordAt(handle, positions(0))
            yield result match
                case Result.Failure(msg) => assert(msg.contains("CRC mismatch"))
                case other               => assert(false, s"expected Failure, got $other")
            end for
        }
        "a CRC-failed record line in active segment with no following commit is a torn tail" in {
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
            codec.scan(handle, arr.length.toLong, isActive = true).map {
                case ScanResult.Ok(committed, _, Present(_)) => assert(committed.isEmpty)
                case other                                   => assert(false, s"expected Ok torn, got $other")
            }
        }
    }

    // --- payload transcoding ------------------------------------------------------------------

    "payload transcoding" - {
        "bytes codec encodeForJsonl returns a JSON string literal (including surrounding quotes)" in {
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
            val rawPayload = Span.from(Array[Byte](0, 1, 2, 127, -1))
            val e          = EventEnvelope(valid(EventId("eid")), valid(EventType("T")), rawPayload, EventMetadata.empty)
            val bytes      = codec.frameBatch(0L, Chunk(e))
            val handle     = memHandle(bytes)
            val positions  = codec.extractPositions(0L, Chunk(e), bytes, 0L)
            codec.decodeRecordAt(handle, positions(0)).map {
                case Result.Success(dec) =>
                    assert(java.util.Arrays.equals(dec.payload, rawPayload.toArray))
                case other =>
                    assert(false, s"expected Success, got $other")
            }
        }
    }

    // --- golden on-disk line bytes -----------------------------------------------------------

    "golden on-disk line bytes" - {
        "record and commit lines match the exact locked byte sequence including lowercase CRC suffix" in {
            // Fixed event: offset 0, eventId "e-0", eventType "T", empty metadata, raw payload "p0".
            // BytesPayloadCodec base64-encodes 0x70 0x30 ("p0") as "cDA=", wrapped in JSON quotes.
            // This test independently derives the expected bytes from the format spec and asserts
            // byte-exact equality with frameBatch output, pinning the canonical JSONL line format
            // against silent drift and confirming the CRC hex is lowercase.
            def goldenLine(body: String): String =
                val bodyBytes = body.getBytes(Utf8)
                val crc       = new kyo.internal.CRC32()
                crc.update(bodyBytes)
                body.dropRight(1) + f""","crc":"0x${crc.value}%08x"}""" + "\n"
            end goldenLine
            val expectedRecord = goldenLine(
                """{"offset":0,"eventId":"e-0","eventType":"T","metadata":{},"payload":"cDA="}"""
            )
            val expectedCommit = goldenLine("""{"commit":1}""")
            val bytes          = codec.frameBatch(0L, Chunk(env(0)))
            val lines          = new String(bytes, Utf8).split("\n", -1).dropRight(1)
            assert(lines.length == 2)
            assert(lines(0) + "\n" == expectedRecord)
            assert(lines(1) + "\n" == expectedCommit)
            // First byte of the segment is the opening '{' of the first JSON object.
            assert(bytes(0) == '{'.toByte)
            // Segment file extension and 20-digit zero-padded stem.
            assert(codec.segmentExtension == ".jsonl")
            assert(codec.segmentName(0L) == "00000000000000000000.jsonl")
        }
    }

    // --- torn JSONL line (no trailing newline) ------------------------------------------------

    "torn JSONL line (no trailing newline)" - {
        "a line without a terminating newline in active segment is treated as a torn tail" in {
            val events    = Chunk(env(0))
            val bytes     = codec.frameBatch(0L, events)
            val positions = codec.extractPositions(0L, events, bytes, 0L)
            // Slice out the event line bytes without its trailing '\n'.
            var nlIdx = positions(0).toInt
            while nlIdx < bytes.length && bytes(nlIdx) != '\n'.toByte do nlIdx += 1
            val partial = bytes.take(nlIdx) // event line WITHOUT '\n'
            val handle  = memHandle(partial)
            codec.scan(handle, partial.length.toLong, isActive = true).map {
                case ScanResult.Ok(committed, _, Present(_)) => assert(committed.isEmpty)
                case other                                   => assert(false, s"expected Ok torn, got $other")
            }
        }
        "a line without a terminating newline in sealed segment is Corrupt" in {
            val events    = Chunk(env(0))
            val bytes     = codec.frameBatch(0L, events)
            val positions = codec.extractPositions(0L, events, bytes, 0L)
            var nlIdx     = positions(0).toInt
            while nlIdx < bytes.length && bytes(nlIdx) != '\n'.toByte do nlIdx += 1
            val partial = bytes.take(nlIdx) // no '\n'
            val handle  = memHandle(partial)
            codec.scan(handle, partial.length.toLong, isActive = false).map {
                case ScanResult.Corrupt(msg) => assert(msg.contains("sealed segment"))
                case other                   => assert(false, s"expected Corrupt, got $other")
            }
        }
    }

end JsonlSegmentCodecTest
