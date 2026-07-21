package kyo.internal.codec

import kyo.*
import kyo.internal.util.*
import kyo.net.internal.util.GrowableByteBuffer
import scala.annotation.tailrec

/** Mutable builder that constructs packed ParsedRequest byte arrays during HTTP/1.1 parsing.
  *
  * Connection-scoped and reused across requests. Http1Parser calls the setXxx/addXxx methods as it tokenises the request line and headers,
  * then calls build() once to produce the immutable ParsedRequest. reset() must be called between requests.
  *
  * Internal buffers grow on demand but are never shrunk — steady-state operation after warm-up allocates only the single result array
  * emitted by build().
  *
  * All raw bytes (path, query, segment text, header names/values) are written sequentially into rawBytes with their offsets tracked in
  * parallel int arrays. build() copies everything into the compact ParsedRequest layout in one pass.
  */
final private[kyo] class ParsedRequestBuilder(using AllowUnsafe):
    private val rawBytes           = new GrowableByteBuffer()
    private var headerCount        = 0
    private var segmentCount       = 0
    private var flags              = 0
    private var contentLengthValue = -1
    private var pathSet            = false
    private var querySet           = false

    // Path offset/length in rawBytes
    private var pathOff = 0
    private var pathLen = 0

    // Query offset/length in rawBytes
    private var queryOff = 0
    private var queryLen = 0

    // Segment offsets: [off0, len0, off1, len1, ...]
    private var segOffsets     = new Array[Int](32)
    private var segOffsetCount = 0

    // Header offsets: [nameOff0, nameLen0, valOff0, valLen0, ...]
    private var hdrOffsets     = new Array[Int](128)
    private var hdrOffsetCount = 0

    // -- Fixed metadata setters --

    def setMethod(ordinal: Int): Unit =
        flags = (flags & 0x00ff) | (ordinal << 8)

    def setChunked(chunked: Boolean): Unit =
        if chunked then flags = flags | 1 else flags = flags & ~1

    def setKeepAlive(keepAlive: Boolean): Unit =
        if keepAlive then flags = flags | 2 else flags = flags & ~2

    def setHasQuery(has: Boolean): Unit =
        if has then flags = flags | 4 else flags = flags & ~4

    def setExpectContinue(expect: Boolean): Unit =
        if expect then flags = flags | 8 else flags = flags & ~8

    def setHasHost(has: Boolean): Unit =
        if has then flags = flags | 16 else flags = flags & ~16

    def setMultipleHost(multiple: Boolean): Unit =
        if multiple then flags = flags | 32 else flags = flags & ~32

    def setEmptyHost(empty: Boolean): Unit =
        if empty then flags = flags | 64 else flags = flags & ~64

    def setUpgrade(upgrade: Boolean): Unit =
        if upgrade then flags = flags | 128 else flags = flags & ~128

    def setContentLength(len: Int): Unit =
        contentLengthValue = len

    // -- Variable-length data --

    def setPath(src: Array[Byte], offset: Int, length: Int): Unit =
        pathOff = rawBytes.size
        rawBytes.writeBytes(src, offset, length)
        pathLen = length
        pathSet = true
    end setPath

    def setQuery(src: Array[Byte], offset: Int, length: Int): Unit =
        queryOff = rawBytes.size
        rawBytes.writeBytes(src, offset, length)
        queryLen = length
        querySet = true
        setHasQuery(true)
    end setQuery

    def addPathSegment(src: Array[Byte], offset: Int, length: Int): Unit =
        val rawOff = rawBytes.size
        rawBytes.writeBytes(src, offset, length)
        ensureSegOffsets(2)
        segOffsets(segOffsetCount) = rawOff
        segOffsets(segOffsetCount + 1) = length
        segOffsetCount += 2
        segmentCount += 1
    end addPathSegment

    /** Rewrites the stored path as "/" plus the surviving segments joined by "/".
      *
      * Called only when parsing actually resolved a dot segment, which is rare, so the ordinary request pays nothing: the path is stored
      * once from the raw bytes and left alone. When resolution DID change the path, storing the raw form would leave the request routed on
      * one path while reporting another: `pathAsString` reaches handlers and filters as `req.url.path`, so `/public/../admin/secret` would
      * route to `admin/secret` while every log line and every filter decision read `/public/../admin/secret`. A recipient acting on one
      * view while another recipient acts on the other is the same disagreement this parser refuses in message framing, so the two are kept
      * in agreement here rather than left to whoever reads them.
      *
      * The segments are already stored in `rawBytes`; this appends a fresh joined copy and repoints the path at it, since the buffer is
      * append-only and the segment offsets must stay valid.
      */
    def setPathFromSegments(): Unit =
        val newOff = rawBytes.size
        if segmentCount == 0 then rawBytes.writeByte('/'.toByte)
        else
            var i = 0
            while i < segmentCount do
                rawBytes.writeByte('/'.toByte)
                val segOff = segOffsets(i * 2)
                val segLen = segOffsets(i * 2 + 1)
                rawBytes.writeBytes(rawBytes.array, segOff, segLen)
                i += 1
            end while
        end if
        pathOff = newOff
        pathLen = rawBytes.size - newOff
        pathSet = true
    end setPathFromSegments

    /** Drops the most recently added path segment, or does nothing when there is none.
      *
      * This is how a ".." segment is applied while the path is being parsed (RFC 3986 section 5.2.4 removes dot segments), so the router and
      * every capture see the resolved path rather than one still carrying traversal. The segment's bytes stay in the raw buffer and are simply
      * no longer addressed, which costs a few unreferenced bytes and keeps the append-only buffer append-only.
      *
      * A ".." with nothing left to drop is a no-op, per the same section: a path cannot be walked above its root.
      */

    def removeLastPathSegment(): Unit =
        if segmentCount > 0 then
            segOffsetCount -= 2
            segmentCount -= 1

    /** Whether the accumulated bytes have outgrown the 16-bit offsets the packed layout addresses them with.
      *
      * `build()` writes every path, segment and header offset as a two-byte field, so once the raw buffer passes 65535 bytes an offset
      * wraps and a later read lands somewhere else entirely: a header name or value resolves to an arbitrary window of the buffer, which
      * is request-supplied data at a request-influenced offset. The parser asks this before building and refuses the request instead,
      * because a wrapped offset is not a degraded answer, it is a confidently wrong one.
      */
    def offsetsOverflowed: Boolean = rawBytes.size > 0xffff

    def addHeader(
        nameSrc: Array[Byte],
        nameOff: Int,
        nameLen: Int,
        valSrc: Array[Byte],
        valOff: Int,
        valLen: Int
    ): Unit =
        val rawNameOff = rawBytes.size
        rawBytes.writeBytes(nameSrc, nameOff, nameLen)
        val rawValOff = rawBytes.size
        rawBytes.writeBytes(valSrc, valOff, valLen)
        ensureHdrOffsets(4)
        hdrOffsets(hdrOffsetCount) = rawNameOff
        hdrOffsets(hdrOffsetCount + 1) = nameLen
        hdrOffsets(hdrOffsetCount + 2) = rawValOff
        hdrOffsets(hdrOffsetCount + 3) = valLen
        hdrOffsetCount += 4
        headerCount += 1
    end addHeader

    // -- Capacity helpers --

    private def ensureSegOffsets(need: Int): Unit =
        if segOffsetCount + need >= segOffsets.length then
            val newArr = new Array[Int](segOffsets.length * 2)
            java.lang.System.arraycopy(segOffsets, 0, newArr, 0, segOffsetCount)
            segOffsets = newArr

    private def ensureHdrOffsets(need: Int): Unit =
        if hdrOffsetCount + need >= hdrOffsets.length then
            val newArr = new Array[Int](hdrOffsets.length * 2)
            java.lang.System.arraycopy(hdrOffsets, 0, newArr, 0, hdrOffsetCount)
            hdrOffsets = newArr

    // -- Build --

    /** Packs everything into the final ParsedRequest layout. 1 Array[Byte] allocation.
      *
      * Layout:
      *   - [flags:2][contentLength:4][pathOff:2][pathLen:2][queryOff:2][queryLen:2] = 14 bytes
      *   - [segmentCount:2][seg1Off:2][seg1Len:2]... = 2 + segmentCount*4 bytes
      *   - [headerCount:2][h1NameOff:2][h1NameLen:2][h1ValOff:2][h1ValLen:2]... = 2 + headerCount*8 bytes
      *   - [raw bytes]
      */
    def build(): ParsedRequest =
        val fixedSize        = 2 + 4 + 4 + 4 // flags + contentLength + path(off+len) + query(off+len)
        val segmentIndexSize = 2 + segmentCount * 4
        val headerIndexSize  = 2 + headerCount * 8
        val indexSize        = fixedSize + segmentIndexSize + headerIndexSize
        val rawSize          = rawBytes.size
        val totalSize        = indexSize + rawSize

        val result = new Array[Byte](totalSize)

        // Flags
        val p1 = writeShort(result, 0, flags)
        // Content-Length
        val p2 = writeInt(result, p1, contentLengthValue)

        // Path offset and length
        val p3 =
            if pathSet then
                val p3a = writeShort(result, p2, pathOff)
                writeShort(result, p3a, pathLen)
            else
                val p3a = writeShort(result, p2, 0)
                writeShort(result, p3a, 0)
            end if
        end p3

        // Query offset and length
        val p4 =
            if querySet then
                val p4a = writeShort(result, p3, queryOff)
                writeShort(result, p4a, queryLen)
            else
                val p4a = writeShort(result, p3, 0)
                writeShort(result, p4a, 0)
            end if
        end p4

        // Segment count
        val p5 = writeShort(result, p4, segmentCount)

        // Segment offsets
        @tailrec def writeSegments(i: Int, pos: Int): Int =
            if i >= segOffsetCount then pos
            else writeSegments(i + 2, writeShort(result, writeShort(result, pos, segOffsets(i)), segOffsets(i + 1)))
        val p6 = writeSegments(0, p5)

        // Header count
        val p7 = writeShort(result, p6, headerCount)

        // Header offsets (name off, name len, val off, val len)
        @tailrec def writeHeaders(h: Int, pos: Int): Int =
            if h >= hdrOffsetCount then pos
            else
                val ph1 = writeShort(result, pos, hdrOffsets(h))     // nameOff
                val ph2 = writeShort(result, ph1, hdrOffsets(h + 1)) // nameLen
                val ph3 = writeShort(result, ph2, hdrOffsets(h + 2)) // valOff
                val ph4 = writeShort(result, ph3, hdrOffsets(h + 3)) // valLen
                writeHeaders(h + 4, ph4)
        val p8 = writeHeaders(0, p7)

        // Raw bytes
        rawBytes.copyTo(result, p8)

        ParsedRequest.fromArray(result)
    end build

    // -- Reset --

    def reset(): Unit =
        headerCount = 0
        segmentCount = 0
        flags = 0
        contentLengthValue = -1
        pathSet = false
        querySet = false
        pathOff = 0
        pathLen = 0
        queryOff = 0
        queryLen = 0
        segOffsetCount = 0
        hdrOffsetCount = 0
        rawBytes.reset()
    end reset

    // -- Byte writing helpers (big-endian) --

    private inline def writeShort(arr: Array[Byte], pos: Int, value: Int): Int =
        arr(pos) = ((value >> 8) & 0xff).toByte
        arr(pos + 1) = (value & 0xff).toByte
        pos + 2
    end writeShort

    private inline def writeInt(arr: Array[Byte], pos: Int, value: Int): Int =
        arr(pos) = ((value >> 24) & 0xff).toByte
        arr(pos + 1) = ((value >> 16) & 0xff).toByte
        arr(pos + 2) = ((value >> 8) & 0xff).toByte
        arr(pos + 3) = (value & 0xff).toByte
        pos + 4
    end writeInt
end ParsedRequestBuilder
