package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import scala.annotation.tailrec

/** JSONL segment codec: one JSON object per line per event, followed by one commit line per batch.
  * Files carry no binary header; the FORMAT marker at the root distinguishes them from binary
  * segments. All multi-byte integer fields use decimal encoding; byte payloads are base64-encoded
  * when the [[EventPayloadCodec]] is the identity codec, or embedded as JSON values when it is
  * schema-derived.
  *
  * Line formats (both end with a `\n`):
  *
  * {{{
  *   Record : {"offset":N,"eventId":"...","eventType":"...","metadata":{...},"payload":<value>,"crc":"0xhhhhhhhh"}
  *   Commit : {"commit":N,"crc":"0xhhhhhhhh"}
  * }}}
  *
  * The 20-byte CRC suffix `,"crc":"0xhhhhhhhh"}` is always appended after a stripped closing
  * brace of the covered JSON body; on read, the covered bytes are recovered by taking everything
  * before the suffix (bytes `[0, n-20)`) and appending a `}` byte, where `n` is the line length
  * without the newline. CRC32 operates on the UTF-8 bytes of the covered body.
  *
  * The `metadata` field is encoded as a JSON object on write. On read the JSON object is
  * converted to MsgPack bytes via [[BinarySegmentCodec.encodeMetadata]] so that
  * [[DecodedRecord.metadata]] is always in MsgPack form regardless of the segment encoding, and
  * [[FileJournalCore.rebuild]] requires no format-specific branching.
  */
final private[kyo] class JsonlSegmentCodec(payloadCodec: EventPayloadCodec) extends SegmentCodec:

    private val Utf8         = StandardCharsets.UTF_8
    private val CrcSuffixLen = 20 // ,"crc":"0xhhhhhhhh"}

    def segmentExtension: String             = ".jsonl"
    def header: Array[Byte]                  = Array.emptyByteArray
    def recordSize(env: EventEnvelope): Long = 0L // never called; extractPositions overrides

    // JSONL segments carry no binary file header.
    def validateHeader(h: SegmentStore.Handle)(using AllowUnsafe): Maybe[String] = Absent

    // --- write ---------------------------------------------------------------------------------

    def frameBatch(firstOffset: Long, events: Chunk[EventEnvelope]): Array[Byte] =
        val lines = new Array[Array[Byte]](events.length + 1)
        var total = 0
        var i     = 0
        while i < events.length do
            val line = encodeEventLine(firstOffset + i, events(i))
            lines(i) = line
            total += line.length
            i += 1
        end while
        val commitLine = encodeCommitLine(events.length)
        lines(events.length) = commitLine
        total += commitLine.length
        val out = new Array[Byte](total)
        var pos = 0
        i = 0
        while i < lines.length do
            java.lang.System.arraycopy(lines(i), 0, out, pos, lines(i).length)
            pos += lines(i).length
            i += 1
        end while
        out
    end frameBatch

    // Scans `\n` boundaries in the already-encoded batch to find per-record file-byte positions.
    // The first `events.length` lines in batchBytes are event lines; the last is the commit line.
    override def extractPositions(
        firstOffset: Long,
        events: Chunk[EventEnvelope],
        batchBytes: Array[Byte],
        startPos: Long
    ): Array[Long] =
        val positions = new Array[Long](events.length)
        var lineStart = 0
        var i         = 0
        while i < events.length do
            positions(i) = startPos + lineStart.toLong
            var j = lineStart
            while j < batchBytes.length && batchBytes(j) != '\n'.toByte do j += 1
            lineStart = j + 1 // skip the '\n'
            i += 1
        end while
        positions
    end extractPositions

    // --- scan ----------------------------------------------------------------------------------

    def scan(handle: SegmentStore.Handle, size: Long, isActive: Boolean)(using AllowUnsafe): ScanResult =
        val committed = Chunk.newBuilder[Long]
        // pendingBuf accumulates record positions for the current uncommitted batch. Using a
        // mutable ArrayBuffer keeps append O(1) amortised; Chunk.append is O(n) per call.
        val pendingBuf   = new scala.collection.mutable.ArrayBuffer[Long]()
        var pos          = 0L
        var committedEnd = 0L
        var batchStart   = 0L
        @tailrec def loop(): ScanResult =
            if pos >= size then
                if pendingBuf.isEmpty then ScanResult.Ok(committed.result(), committedEnd, Absent)
                else if isActive then ScanResult.Ok(committed.result(), committedEnd, Present(batchStart))
                else ScanResult.Corrupt(s"unterminated batch at byte $batchStart in sealed segment")
            else
                readLineAt(handle, pos, size) match
                    case null =>
                        // Truncated line with no terminating `\n`.
                        if isActive && !hasCommitAfter(handle, pos, size) then
                            ScanResult.Ok(committed.result(), committedEnd, Present(batchStart))
                        else ScanResult.Corrupt(s"truncated JSONL line at byte $pos in sealed segment")
                    case lineBytes =>
                        val lineLen = lineBytes.length.toLong + 1L // +1 for '\n'
                        if isCommitLine(lineBytes) then
                            parseCommitCount(lineBytes) match
                                case Right(count) if count == pendingBuf.length && verifyCrcLine(lineBytes) =>
                                    pendingBuf.foreach(p => discard(committed += p))
                                    pos += lineLen
                                    committedEnd = pos
                                    batchStart = pos
                                    pendingBuf.clear()
                                    loop()
                                case _ =>
                                    if isActive && !hasCommitAfter(handle, pos + lineLen, size) then
                                        ScanResult.Ok(committed.result(), committedEnd, Present(batchStart))
                                    else ScanResult.Corrupt(s"commit CRC/count mismatch at byte $pos")
                        else
                            if verifyCrcLine(lineBytes) then
                                discard(pendingBuf.addOne(pos))
                                pos += lineLen
                                loop()
                            else
                                if isActive && !hasCommitAfter(handle, pos + lineLen, size) then
                                    ScanResult.Ok(committed.result(), committedEnd, Present(batchStart))
                                else ScanResult.Corrupt(s"record CRC mismatch at byte $pos")
                        end if
        loop()
    end scan

    def decodeRecordAt(handle: SegmentStore.Handle, pos: Long)(using AllowUnsafe): Result[String, DecodedRecord] =
        try
            readLineAt(handle, pos, handle.size()) match
                case null => Result.fail(s"truncated JSONL line at byte $pos")
                case lineBytes =>
                    if !verifyCrcLine(lineBytes) then Result.fail(s"JSONL CRC mismatch at byte $pos")
                    else
                        val reader = new Json().newReader(Span.from(lineBytes))(using Frame.internal)
                        discard(reader.objectStart())
                        var offset    = -1L
                        var eventId   = ""
                        var eventType = ""
                        var metadata  = Array.emptyByteArray
                        var payload   = Span.empty[Byte]
                        while reader.hasNextField() do
                            reader.field() match
                                case "offset"    => offset = reader.long()
                                case "eventId"   => eventId = reader.string()
                                case "eventType" => eventType = reader.string()
                                case "metadata"  => metadata = readMetadataJson(reader)
                                case "payload" =>
                                    payloadCodec.decodeFromJsonl(reader)(using Frame.internal) match
                                        case Result.Success(s)  => payload = s
                                        case Result.Failure(ex) => throw ex
                                case _ => reader.skip()
                        end while
                        reader.objectEnd()
                        if offset < 0L then Result.fail(s"missing offset field in JSONL line at byte $pos")
                        else
                            Result.succeed(DecodedRecord(
                                offset = offset,
                                eventId = eventId,
                                eventType = eventType,
                                metadata = metadata,
                                payload = payload.toArray,
                                bodyLen = lineBytes.length + 1 // +1 for '\n', kept for consistency
                            ))
                        end if
        catch
            // Narrow to the parse/decode failure types the JSON reader and payload codec produce.
            // Broader exception types (NPE, IndexOutOfBounds) are defects and must propagate.
            case e: DecodeException => Result.fail(s"JSONL decode error at byte $pos: ${e.getMessage}")
    end decodeRecordAt

    def hasCommitAfter(handle: SegmentStore.Handle, from: Long, size: Long)(using AllowUnsafe): Boolean =
        var pos   = from
        var found = false
        while !found && pos < size do
            readLineAt(handle, pos, size) match
                case null => pos = size // no more complete lines
                case lineBytes =>
                    if isCommitLine(lineBytes) && verifyCrcLine(lineBytes) then found = true
                    pos += lineBytes.length.toLong + 1L
        end while
        found
    end hasCommitAfter

    // --- private helpers -----------------------------------------------------------------------

    // Encodes one event as a JSONL line (with trailing '\n'). Throws on payload encode failure;
    // the caller (frameBatch) is invoked from writeBatch which catches Exception.
    private def encodeEventLine(offset: Long, e: EventEnvelope): Array[Byte] =
        val metaJson = encodeMetadataJson(e.metadata)
        val payloadJson = payloadCodec.encodeForJsonl(e.payload)(using Frame.internal) match
            case Result.Success(s)  => s
            case Result.Failure(ex) => throw ex
        val bodyStr = s"""{"offset":$offset,"eventId":"${escapeJson(e.id.value)}","eventType":"${escapeJson(
                e.eventType.value
            )}","metadata":$metaJson,"payload":$payloadJson}"""
        val bodyBytes = bodyStr.getBytes(Utf8)
        appendCrc(bodyBytes)
    end encodeEventLine

    private def encodeCommitLine(count: Int): Array[Byte] =
        val bodyBytes = s"""{"commit":$count}""".getBytes(Utf8)
        appendCrc(bodyBytes)

    // Appends the 20-char CRC suffix and a newline to `bodyBytes`. The CRC covers `bodyBytes` as-is
    // (the complete JSON body including its closing `}`). The suffix replaces the trailing `}` with
    // `,"crc":"0xhhhhhhhh"}\n` by stripping the last byte and appending the suffix.
    private def appendCrc(bodyBytes: Array[Byte]): Array[Byte] =
        val crc         = new CRC32(); crc.update(bodyBytes)
        val suffixStr   = f""","crc":"0x${crc.value & 0xffffffffL}%08x"}""" + "\n"
        val suffixBytes = suffixStr.getBytes(Utf8) // 21 ASCII bytes (20-char suffix + '\n')
        val out         = new Array[Byte](bodyBytes.length - 1 + suffixBytes.length)
        java.lang.System.arraycopy(bodyBytes, 0, out, 0, bodyBytes.length - 1)
        java.lang.System.arraycopy(suffixBytes, 0, out, bodyBytes.length - 1, suffixBytes.length)
        out
    end appendCrc

    // Reads one line starting at `pos`, scanning for `\n`. Returns the line bytes WITHOUT the
    // trailing `\n`, or null if no `\n` exists before `size` (truncated file). Uses a 4 KiB
    // initial chunk and doubles on miss to avoid per-byte positional reads.
    private def readLineAt(handle: SegmentStore.Handle, pos: Long, size: Long)(using AllowUnsafe): Array[Byte] =
        var chunkLen = math.min(4096L, size - pos).toInt
        if chunkLen <= 0 then return null
        var chunk = handle.readAt(pos, chunkLen)
        var nlIdx = indexOf(chunk, '\n'.toByte)
        while nlIdx < 0 && chunk.length.toLong < (size - pos) do
            chunkLen = math.min(chunkLen * 2L, size - pos).toInt
            chunk = handle.readAt(pos, chunkLen)
            nlIdx = indexOf(chunk, '\n'.toByte)
        end while
        if nlIdx < 0 then null
        else java.util.Arrays.copyOfRange(chunk, 0, nlIdx)
    end readLineAt

    private def indexOf(arr: Array[Byte], b: Byte): Int =
        var i = 0
        while i < arr.length && arr(i) != b do i += 1
        if i == arr.length then -1 else i
    end indexOf

    // A line is a commit line if it starts with `{"commit":`.
    private val CommitPrefix: Array[Byte] = """{"commit":""".getBytes(Utf8)
    private def isCommitLine(lineBytes: Array[Byte]): Boolean =
        if lineBytes.length < CommitPrefix.length then false
        else
            var i = 0
            while i < CommitPrefix.length && lineBytes(i) == CommitPrefix(i) do i += 1
            i == CommitPrefix.length

    // Parses the `commit` count from a commit line. Returns Right(count) on success. Does NOT
    // verify the CRC (the caller may verify separately or pass through verifyCrcLine first).
    private def parseCommitCount(lineBytes: Array[Byte]): Either[String, Int] =
        try
            val reader = new Json().newReader(Span.from(lineBytes))(using Frame.internal)
            discard(reader.objectStart())
            var count = -1
            while reader.hasNextField() do
                reader.field() match
                    case "commit" => count = reader.long().toInt
                    case _        => reader.skip()
            end while
            reader.objectEnd()
            if count < 0 then Left("missing commit count") else Right(count)
        catch case e: Exception => Left(s"commit parse error: ${e.getMessage}")

    // Verifies the CRC of a line (without the trailing `\n`).
    // CRC covers lineBytes[0, n-CrcSuffixLen) + `}`. The hex digits are at lineBytes[n-10, n-2).
    private def verifyCrcLine(lineBytes: Array[Byte]): Boolean =
        val n = lineBytes.length
        if n < CrcSuffixLen + 2 then false // need at least `{}` (2 bytes) + 20-byte suffix
        else
            try
                val hexStr   = new String(lineBytes, n - 10, 8, Utf8)
                val expected = java.lang.Long.parseLong(hexStr, 16).toInt
                val crc      = new CRC32()
                crc.update(lineBytes, 0, n - CrcSuffixLen)
                crc.update(Array[Byte]('}'))
                (crc.value & 0xffffffffL).toInt == expected
            catch case _: NumberFormatException => false
        end if
    end verifyCrcLine

    // Encodes EventMetadata as a JSON object string, e.g. `{"tag1":"v1","tag2":42}`.
    private def encodeMetadataJson(md: EventMetadata): String =
        val w = new Json().newWriter()
        w.mapStart(md.values.size)
        md.values.foreach { (k, v) =>
            w.field(k.value, 0)
            MetadataValue.write(w, v)
        }
        w.mapEnd()
        new String(w.result().toArray, Utf8)
    end encodeMetadataJson

    // Reads a JSON metadata object from `reader` (cursor positioned at the object value) and
    // returns MsgPack bytes via BinarySegmentCodec.encodeMetadata. Invalid keys are silently
    // dropped (they are not expected from correctly-encoded JSONL; if they appear, partial
    // metadata is preferable to a read failure).
    private def readMetadataJson(reader: Codec.Reader): Array[Byte] =
        discard(reader.objectStart())
        val pairs = Chunk.newBuilder[(MetadataKey, MetadataValue)]
        while reader.hasNextField() do
            val key = reader.field()
            val v   = MetadataValue.read(reader)
            MetadataKey(key)(using Frame.internal) match
                case Result.Success(mk) => pairs += (mk -> v)
                case Result.Failure(_)  => () // skip invalid key
        end while
        reader.objectEnd()
        BinarySegmentCodec.encodeMetadata(EventMetadata(pairs.result().toMap))
    end readMetadataJson

    // Minimal JSON string escaping. All 7-bit ASCII special chars that need escaping are handled;
    // valid UTF-8 multi-byte sequences pass through unchanged (the JSON spec allows them).
    private def escapeJson(s: String): String =
        val sb = new java.lang.StringBuilder(s.length)
        var i  = 0
        while i < s.length do
            s.charAt(i) match
                case '"'                 => discard(sb.append("\\\""))
                case '\\'                => discard(sb.append("\\\\"))
                case '\n'                => discard(sb.append("\\n"))
                case '\r'                => discard(sb.append("\\r"))
                case '\t'                => discard(sb.append("\\t"))
                case c if c.toInt < 0x20 => discard(sb.append(f"\\u${c.toInt}%04x"))
                case c                   => discard(sb.append(c))
            end match
            i += 1
        end while
        sb.toString
    end escapeJson

end JsonlSegmentCodec
