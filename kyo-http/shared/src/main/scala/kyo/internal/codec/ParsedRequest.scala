package kyo.internal.codec

import java.nio.charset.StandardCharsets
import kyo.*
import scala.annotation.tailrec

/** Opaque type over Span[Byte] representing a fully parsed HTTP request.
  *
  * The packed binary layout stores all metadata (method, flags, content-length, path/query offsets, segment offsets, header offsets) in a
  * compact index followed by raw bytes. This enables O(1) flag access and O(n) header lookups without any String allocations until the
  * caller explicitly requests a String value.
  *
  * Binary layout (all offsets relative to raw bytes section start):
  *   - [0..1] flags: 2 bytes — high byte = method ordinal, low byte = bit flags: bit0=chunked, bit1=keepAlive, bit2=hasQuery,
  *     bit3=expectContinue, bit4=hasHost, bit5=multipleHost, bit6=emptyHost, bit7=upgrade
  *   - [2..5] contentLength: 4 bytes big-endian (-1 if absent)
  *   - [6..9] pathOff:2 + pathLen:2
  *   - [10..13] queryOff:2 + queryLen:2 (0/0 if no query)
  *   - [14..15] segmentCount: 2 bytes
  *   - [16..16+segmentCount*4-1] per-segment (off:2 + len:2)
  *   - [16+segCount*4..+1] headerCount: 2 bytes
  *   - [+2..+headerCount*8-1] per-header (nameOff:2 + nameLen:2 + valOff:2 + valLen:2)
  *   - [rest] raw bytes — path, query, segment text, header names and values
  *
  * Constructed by ParsedRequestBuilder.build(). Consumed by Http1Parser callbacks and UnsafeServerDispatch. headersAsPacked extracts the
  * header section as a standalone array compatible with HttpHeaders.fromPacked.
  */
private[kyo] opaque type ParsedRequest = Span[Byte]

private[kyo] object ParsedRequest:

    /** Empty sentinel used as initial value before a request is parsed. */
    val empty: ParsedRequest = Span.empty[Byte]

    /** Pre-encoded route segment for zero-alloc matching. */
    opaque type Segment = Array[Byte]

    object Segment:
        def apply(s: String): Segment = s.getBytes(StandardCharsets.UTF_8)

        extension (self: Segment)
            def bytes: Array[Byte] = self
    end Segment

    // Lookup table: ordinal -> HttpMethod. Must match the ordinals assigned by ParsedRequestBuilder.setMethod.
    // HttpMethod is an opaque String, not a Scala 3 enum, so fromOrdinal is not available.
    private val methods: Array[HttpMethod] = Array(
        HttpMethod.GET,
        HttpMethod.POST,
        HttpMethod.PUT,
        HttpMethod.PATCH,
        HttpMethod.DELETE,
        HttpMethod.HEAD,
        HttpMethod.OPTIONS,
        HttpMethod.TRACE,
        HttpMethod.CONNECT
    )

    /** Returns the HttpMethod for a given ordinal, or GET as fallback. Note: the parser validates the method and rejects unknown methods
      * before reaching this code, so the fallback should never be exercised.
      */
    private[internal] def methodFromOrdinal(ordinal: Int): HttpMethod =
        if ordinal >= 0 && ordinal < methods.length then methods(ordinal)
        else
            // Defensive fallback — parser should reject unknown methods before this point.
            // Cannot log here because this is called from an opaque type extension method
            // that doesn't have AllowUnsafe/Frame in scope.
            HttpMethod.GET

    /** Returns the ordinal for a given HttpMethod name, or -1 if unknown. */
    private[internal] def ordinalFromName(name: String): Int =
        @tailrec def loop(i: Int): Int =
            if i >= methods.length then -1
            else if methods(i).name == name then i
            else loop(i + 1)
        loop(0)
    end ordinalFromName

    /** Creates a ParsedRequest from a packed byte array. Used by ParsedRequestBuilder. */
    private[internal] def fromArray(arr: Array[Byte]): ParsedRequest =
        Span.fromUnsafe(arr)

    // -- Byte reading helpers (big-endian) --

    private inline def readShort(arr: Span[Byte], offset: Int): Int =
        ((arr(offset) & 0xff) << 8) | (arr(offset + 1) & 0xff)

    private inline def readInt(arr: Span[Byte], offset: Int): Int =
        ((arr(offset) & 0xff) << 24) |
            ((arr(offset + 1) & 0xff) << 16) |
            ((arr(offset + 2) & 0xff) << 8) |
            (arr(offset + 3) & 0xff)

    import Segment.bytes

    extension (self: ParsedRequest)

        /** HTTP method from the high byte of flags. */
        def method: HttpMethod =
            val flags = readShort(self, 0)
            methodFromOrdinal((flags >> 8) & 0xff)

        /** Whether Transfer-Encoding: chunked was detected. Bit 0 of flags. */
        def isChunked: Boolean =
            (readShort(self, 0) & 1) != 0

        /** Whether Connection: keep-alive was detected. Bit 1 of flags. */
        def isKeepAlive: Boolean =
            (readShort(self, 0) & 2) != 0

        /** Whether the request has a query string. Bit 2 of flags. */
        def hasQuery: Boolean =
            (readShort(self, 0) & 4) != 0

        /** Whether the request has Expect: 100-continue header. Bit 3 of flags. */
        def expectContinue: Boolean =
            (readShort(self, 0) & 8) != 0

        /** Whether the request has a Host header. Bit 4 of flags. */
        def hasHost: Boolean =
            (readShort(self, 0) & 16) != 0

        /** Whether the request has multiple Host headers (RFC 9110 violation). Bit 5 of flags. */
        def hasMultipleHost: Boolean =
            (readShort(self, 0) & 32) != 0

        /** Whether the Host header value is empty. Bit 6 of flags. */
        def hasEmptyHost: Boolean =
            (readShort(self, 0) & 64) != 0

        /** Whether the request is a HttpWebSocket upgrade (Upgrade: websocket + Connection: upgrade). Bit 7 of flags. */
        def isUpgrade: Boolean =
            (readShort(self, 0) & 128) != 0

        /** Pre-parsed Content-Length (-1 if absent). */
        def contentLength: Int =
            readInt(self, 2)

        /** Number of path segments. */
        def pathSegmentCount: Int =
            readShort(self, 14)

        /** Computes the offset where raw bytes begin in the packed array. */
        private def rawBytesOffset: Int =
            val segCount          = readShort(self, 14)
            val headerCountOffset = 16 + segCount * 4
            val hdrCount          = readShort(self, headerCountOffset)
            headerCountOffset + 2 + hdrCount * 8
        end rawBytesOffset

        /** Checks if path segment `i` matches the given pre-encoded Segment via byte comparison. */
        def pathSegmentMatches(i: Int, segment: Segment): Boolean =
            val segCount = readShort(self, 14)
            if i < 0 || i >= segCount then false
            else
                val base   = rawBytesOffset
                val segIdx = 16 + i * 4
                val off    = readShort(self, segIdx)
                val len    = readShort(self, segIdx + 2)
                val segArr = segment.bytes
                if len != segArr.length then false
                else
                    @tailrec def cmp(j: Int): Boolean =
                        if j >= len then true
                        else if self(base + off + j) != segArr(j) then false
                        else cmp(j + 1)
                    cmp(0)
                end if
            end if
        end pathSegmentMatches

        /** Returns path segment `i` as a String (raw, not URL-decoded). 1 allocation. */
        def pathSegmentAsString(i: Int): String =
            val segCount = readShort(self, 14)
            if i < 0 || i >= segCount then ""
            else
                val base   = rawBytesOffset
                val segIdx = 16 + i * 4
                val off    = readShort(self, segIdx)
                val len    = readShort(self, segIdx + 2)
                val arr    = new Array[Byte](len)
                @tailrec def copy(j: Int): Unit =
                    if j < len then
                        arr(j) = self(base + off + j)
                        copy(j + 1)
                copy(0)
                new String(arr, StandardCharsets.UTF_8)
            end if
        end pathSegmentAsString

        /** Returns path segment `i` as a URL-decoded String. */
        def pathSegmentAsStringDecoded(i: Int): String =
            val raw = pathSegmentAsString(i)
            if raw.indexOf('%') < 0 then raw
            else java.net.URLDecoder.decode(raw, "UTF-8")
        end pathSegmentAsStringDecoded

        /** Returns all remaining path segments from index `fromSegment` onward, joined with '/' and URL-decoded. Used for rest captures. */
        def restPathAsString(fromSegment: Int): String =
            val segCount = readShort(self, 14)
            if fromSegment < 0 || fromSegment >= segCount then ""
            else if fromSegment == segCount - 1 then pathSegmentAsStringDecoded(fromSegment)
            else
                val sb = new java.lang.StringBuilder
                @tailrec def loop(i: Int): Unit =
                    if i < segCount then
                        if i > fromSegment then discard(sb.append('/'))
                        discard(sb.append(pathSegmentAsStringDecoded(i)))
                        loop(i + 1)
                loop(fromSegment)
                sb.toString
            end if
        end restPathAsString

        /** Returns the full path as a String. 1 allocation. */
        def pathAsString: String =
            val base = rawBytesOffset
            val off  = readShort(self, 6)
            val len  = readShort(self, 8)
            val arr  = new Array[Byte](len)
            @tailrec def copy(j: Int): Unit =
                if j < len then
                    arr(j) = self(base + off + j)
                    copy(j + 1)
            copy(0)
            new String(arr, StandardCharsets.UTF_8)
        end pathAsString

        /** Returns the full query string if present. */
        def queryRawString: Maybe[String] =
            if !hasQuery then Absent
            else
                val base = rawBytesOffset
                val off  = readShort(self, 10)
                val len  = readShort(self, 12)
                if len == 0 then Absent
                else
                    val arr = new Array[Byte](len)
                    @tailrec def copy(j: Int): Unit =
                        if j < len then
                            arr(j) = self(base + off + j)
                            copy(j + 1)
                    copy(0)
                    Present(new String(arr, StandardCharsets.UTF_8))
                end if

        /** Looks up a query parameter by name, scanning the raw query bytes. Decodes percent-encoding. */
        def queryParam(name: String): Maybe[String] =
            if !hasQuery then Absent
            else
                val base     = rawBytesOffset
                val queryOff = readShort(self, 10)
                val queryLen = readShort(self, 12)
                if queryLen == 0 then Absent
                else
                    val nameBytes = name.getBytes(StandardCharsets.UTF_8)
                    findQueryParam(base + queryOff, queryLen, nameBytes)
                end if

        /** Scans the query bytes for a parameter matching nameBytes, then decodes and returns its value. */
        private def findQueryParam(start: Int, length: Int, nameBytes: Array[Byte]): Maybe[String] =
            val end = start + length
            @tailrec def scan(pos: Int): Maybe[String] =
                if pos >= end then Absent
                else
                    // Find the end of the current key
                    val eqOrAmp = findChar(pos, end, '=', '&')
                    if eqOrAmp >= end || self(eqOrAmp) == '&' then
                        // No '=' found or key without value; skip this segment
                        if eqOrAmp >= end then Absent
                        else scan(eqOrAmp + 1)
                    else
                        // eqOrAmp points to '='
                        val keyLen   = eqOrAmp - pos
                        val valStart = eqOrAmp + 1
                        val valEnd   = findChar(valStart, end, '&', '&')
                        if keyLen == nameBytes.length && bytesMatchAt(pos, nameBytes) then
                            Present(urlDecode(valStart, valEnd - valStart))
                        else
                            if valEnd >= end then Absent
                            else scan(valEnd + 1)
                        end if
                    end if
            scan(start)
        end findQueryParam

        /** Finds the first occurrence of either c1 or c2 starting from pos, up to end. Returns end if not found. */
        private def findChar(pos: Int, end: Int, c1: Char, c2: Char): Int =
            @tailrec def loop(i: Int): Int =
                if i >= end then end
                else
                    val b = self(i) & 0xff
                    if b == c1.toInt || b == c2.toInt then i
                    else loop(i + 1)
            loop(pos)
        end findChar

        /** Checks if the bytes at position `pos` in self match `target` exactly. */
        private def bytesMatchAt(pos: Int, target: Array[Byte]): Boolean =
            @tailrec def loop(i: Int): Boolean =
                if i >= target.length then true
                else if self(pos + i) != target(i) then false
                else loop(i + 1)
            loop(0)
        end bytesMatchAt

        /** Decodes percent-encoded and '+'-encoded query value bytes into a String. */
        private def urlDecode(start: Int, length: Int): String =
            val buf = new Array[Byte](length) // at most length bytes
            val end = start + length
            @tailrec def loop(i: Int, j: Int): Int =
                if i >= end then j
                else
                    val b = self(i) & 0xff
                    if b == '%' && i + 2 < end then
                        val hi = hexVal(self(i + 1))
                        val lo = hexVal(self(i + 2))
                        if hi >= 0 && lo >= 0 then
                            buf(j) = ((hi << 4) | lo).toByte
                            loop(i + 3, j + 1)
                        else
                            buf(j) = b.toByte
                            loop(i + 1, j + 1)
                        end if
                    else if b == '+' then
                        buf(j) = ' '.toByte
                        loop(i + 1, j + 1)
                    else
                        buf(j) = b.toByte
                        loop(i + 1, j + 1)
                    end if
            val finalJ = loop(start, 0)
            new String(buf, 0, finalJ, StandardCharsets.UTF_8)
        end urlDecode

        /** Converts a hex digit byte to its numeric value, or -1 if invalid. */
        private def hexVal(b: Byte): Int =
            val c = b & 0xff
            if c >= '0' && c <= '9' then c - '0'
            else if c >= 'a' && c <= 'f' then c - 'a' + 10
            else if c >= 'A' && c <= 'F' then c - 'A' + 10
            else -1
            end if
        end hexVal

        /** Number of headers. */
        def headerCount: Int =
            val segCount = readShort(self, 14)
            readShort(self, 16 + segCount * 4)

        /** Returns the name of header at index `i`. */
        def headerName(i: Int): String =
            val segCount          = readShort(self, 14)
            val headerCountOffset = 16 + segCount * 4
            val hdrCount          = readShort(self, headerCountOffset)
            if i < 0 || i >= hdrCount then ""
            else
                val base      = headerCountOffset + 2 + hdrCount * 8
                val headerIdx = headerCountOffset + 2 + i * 8
                val off       = readShort(self, headerIdx)
                val len       = readShort(self, headerIdx + 2)
                val arr       = new Array[Byte](len)
                @tailrec def copy(j: Int): Unit =
                    if j < len then
                        arr(j) = self(base + off + j)
                        copy(j + 1)
                copy(0)
                new String(arr, StandardCharsets.UTF_8)
            end if
        end headerName

        /** Returns the value of header at index `i`. */
        def headerValue(i: Int): String =
            val segCount          = readShort(self, 14)
            val headerCountOffset = 16 + segCount * 4
            val hdrCount          = readShort(self, headerCountOffset)
            if i < 0 || i >= hdrCount then ""
            else
                val base      = headerCountOffset + 2 + hdrCount * 8
                val headerIdx = headerCountOffset + 2 + i * 8
                val off       = readShort(self, headerIdx + 4)
                val len       = readShort(self, headerIdx + 6)
                val arr       = new Array[Byte](len)
                @tailrec def copy(j: Int): Unit =
                    if j < len then
                        arr(j) = self(base + off + j)
                        copy(j + 1)
                copy(0)
                new String(arr, StandardCharsets.UTF_8)
            end if
        end headerValue

        /** Extracts the header section of the packed array as a standalone packed array compatible with the HttpHeaders.fromPacked format.
          *
          * The header section in ParsedRequest starts at `headerCountOffset` and runs to the end. The offsets within this section reference
          * raw bytes that are part of the same region, but they're relative to the full ParsedRequest's rawBytesOffset. We need to adjust
          * the offsets to be relative to the new array's raw bytes section.
          */
        def headersAsPacked: Array[Byte] =
            val segCount          = readShort(self, 14)
            val headerCountOffset = 16 + segCount * 4
            val hdrCount          = readShort(self, headerCountOffset)
            if hdrCount == 0 then
                // Return a minimal packed array: [count=0 (2 bytes)]
                val result = new Array[Byte](2)
                result(0) = 0
                result(1) = 0
                result
            else
                val fullRawBytesOffset = headerCountOffset + 2 + hdrCount * 8
                // The new packed array's raw bytes offset will be: 2 + hdrCount * 8
                val newRawBytesOffset = 2 + hdrCount * 8
                // We need to copy header index + raw bytes that headers reference
                // First, find the extent of raw bytes referenced by headers
                val headerSectionSize = self.size - headerCountOffset
                val result            = new Array[Byte](headerSectionSize)
                // Copy the whole header section (count + index + raw bytes)
                @tailrec def copyBytes(j: Int): Unit =
                    if j < headerSectionSize then
                        result(j) = self(headerCountOffset + j)
                        copyBytes(j + 1)
                copyBytes(0)
                // Now adjust offsets: each header has 4 shorts (nameOff, nameLen, valOff, valLen)
                // nameOff and valOff are relative to fullRawBytesOffset in the original ParsedRequest.
                // In the new array, raw bytes start at newRawBytesOffset, but the raw bytes
                // in the new array start at the same relative position (fullRawBytesOffset - headerCountOffset)
                // which equals newRawBytesOffset. So offsets don't need adjustment!
                // This is because offsets are already relative to the raw bytes section start,
                // and we copied the raw bytes section at the same relative position.
                result
            end if
        end headersAsPacked
    end extension
end ParsedRequest
