package kyo.internal.http1

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.codec.*
import kyo.internal.util.*
import kyo.net.internal.util.GrowableByteBuffer
import kyo.scheduler.IOPromise
import scala.annotation.tailrec

/** Zero-copy HTTP/1.1 response parser for client connections. Callback-driven state machine that reads from the inbound Channel.Unsafe,
  * accumulates bytes in a flat reusable buffer, and produces ParsedResponse values via the onResponseParsed callback.
  *
  * Follows the same TakePromise + reuseTake pattern as Http1Parser. The key structural difference is that the first line is a status line
  * ("HTTP/1.1 200 OK") instead of a request line ("GET /path HTTP/1.1"), and headers are stored in a GrowableByteBuffer + offset array
  * rather than delegating to ParsedRequestBuilder.
  *
  * buildPackedHeaders() converts the accumulated offsets into the HttpHeaders.fromPacked format so the ParsedResponse can wrap headers as
  * HttpHeaders without any re-parsing.
  */
final private[kyo] class Http1ResponseParser(
    inbound: Channel.Unsafe[Span[Byte]],
    maxHeaderSize: Int = 65536,
    onResponseParsed: (ParsedResponse, Span[Byte]) => Unit = (_, _) => (),
    onClosed: () => Unit = () => ()
)(using allow: AllowUnsafe, frame: Frame):

    private val buf = new Array[Byte](maxHeaderSize)
    private var pos = 0

    // Reusable builder state for headers
    private val rawBytes       = new GrowableByteBuffer()
    private var headerCount    = 0
    private var hdrOffsets     = new Array[Int](128)
    private var hdrOffsetCount = 0
    private var invalid        = false
    // Whether a Content-Length header has been seen at all, which a running value of -1 cannot express.
    private var seenContentLength = false

    /** Reusable take promise — same pattern as Http1Parser.
      *
      * Extends `IOPromise[Closed, Span[Byte]]` so poll() returns `Result[Closed, Span[Byte]]` directly — no `< S` wrapper, no cast needed.
      * Cast to `Promise.Unsafe[Span[Byte], Abort[Closed]]` crosses the opaque boundary (same as ReadPump).
      */
    private class TakePromise extends IOPromise[Closed, Span[Byte]]:
        override protected def onComplete(): Unit =
            val result = poll()
            // Reset the promise back to Pending BEFORE calling parse(), so that if
            // parse() -> needMoreBytes() -> reuseTake() is called, the promise is
            // already in Pending state and ready to receive the next value.
            discard(becomeAvailable())
            result match
                case Present(Result.Success(span)) =>
                    val len = span.size
                    if pos + len <= maxHeaderSize then
                        discard(span.copyToArray(buf, pos))
                        pos += len
                        parse()
                    else
                        onClosed()
                    end if
                case Present(Result.Failure(_)) =>
                    // Expected on normal connection close
                    onClosed()
                case Present(Result.Panic(_)) =>
                    onClosed()
                case Absent => onClosed()
            end match
        end onComplete

        def resetForReuse(): Boolean = becomeAvailable()
    end TakePromise

    private val takePromise = new TakePromise
    // Cross opaque boundary: IOPromise[Closed, Span[Byte]] is the runtime representation of Promise.Unsafe[Span[Byte], Abort[Closed]].
    // Same pattern as ReadPump.
    private val takePromiseUnsafe: Fiber.Promise.Unsafe[Span[Byte], Abort[Closed]] =
        takePromise.asInstanceOf[Fiber.Promise.Unsafe[Span[Byte], Abort[Closed]]]

    /** Starts the parser by initiating the first read from the inbound channel. */
    def start(): Unit =
        if pos > 0 then
            parse()
        else
            needMoreBytes()
        end if
    end start

    private def parse(): Unit =
        val headerEnd = indexOf(buf, pos, Http1Parser.CRLF_CRLF)
        if headerEnd == -1 then
            needMoreBytes()
        else
            val response = packResponse(buf, headerEnd)
            // Guard against unparseable status lines. Common in cancellation paths where the buffered bytes
            // are response-body fragments (not a new status line) — keep at debug to avoid burying real
            // failures under cancellation noise.
            if response.statusCode < 100 || response.statusCode > 599 then
                Log.live.unsafe.debug(s"Http1ResponseParser: invalid status code ${response.statusCode}, closing")
                onClosed()
            else if invalid then
                Log.live.unsafe.debug("Http1ResponseParser: header field violates RFC 9110 section 5.5, closing")
                onClosed()
            else
                // Extract remaining bytes after headers — these are body bytes
                // (for both Content-Length and chunked responses)
                val remaining = pos - (headerEnd + 4)
                val bodySpan =
                    if remaining > 0 then
                        val cl = response.contentLength
                        val bodyLen =
                            if cl > 0 then math.min(remaining, cl)
                            else remaining // chunked or unknown — take all remaining
                        val bodyArr = new Array[Byte](bodyLen)
                        java.lang.System.arraycopy(buf, headerEnd + 4, bodyArr, 0, bodyLen)
                        // Compact any leftover bytes beyond what we extracted
                        val leftover = remaining - bodyLen
                        if leftover > 0 then
                            java.lang.System.arraycopy(buf, headerEnd + 4 + bodyLen, buf, 0, leftover)
                        pos = leftover
                        Span.fromUnsafe(bodyArr)
                    else
                        pos = 0
                        Span.empty[Byte]
                onResponseParsed(response, bodySpan)
            end if
        end if
    end parse

    private def needMoreBytes(): Unit =
        inbound.poll() match
            case Result.Success(maybe) =>
                maybe match
                    case Present(span) =>
                        val len = span.size
                        if pos + len <= maxHeaderSize then
                            discard(span.copyToArray(buf, pos))
                            pos += len
                            parse()
                        else
                            onClosed()
                        end if
                    case Absent =>
                        // No data available — register take promise directly.
                        // The promise is in Pending state either because it's fresh (first call)
                        // or because onComplete reset it before calling parse().
                        inbound.reuseTake(takePromiseUnsafe)
                end match
            case Result.Failure(_: Closed) =>
                onClosed()
            case Result.Panic(t) =>
                Log.live.unsafe.error("Http1ResponseParser poll panic", t)
                onClosed()
    end needMoreBytes

    /** Resets the parser for the next response on the same connection. */
    def reset(): Unit =
        rawBytes.reset()
        headerCount = 0
        hdrOffsetCount = 0
        invalid = false
        seenContentLength = false
    end reset

    /** Parses the status line and headers from raw bytes into a ParsedResponse. */
    private def packResponse(rawBuf: Array[Byte], headerEnd: Int): ParsedResponse =
        rawBytes.reset()
        headerCount = 0
        hdrOffsetCount = 0
        invalid = false
        seenContentLength = false

        // A response header is stored as the raw octets it was parsed from and is written back out verbatim, so a peer
        // that smuggles a line break past this parser has it re-emitted unchanged by any proxy that echoes the header.
        // Every legitimate CR and LF in this region is half of a CRLF; a bare one is a line terminator a downstream MAY
        // recognize (RFC 9112 section 2.2), and rejecting it is a recipient MUST (RFC 9110 section 5.5).
        if containsBareCr(rawBuf, 0, headerEnd) || containsBareLf(rawBuf, 0, headerEnd) then
            invalid = true

        // Find the end of the status line (first CRLF)
        val statusLineEnd = indexOf(rawBuf, headerEnd + 2, Http1Parser.CRLF_SINGLE)

        val (statusCode, isKeepAliveInit) =
            if statusLineEnd != -1 then
                val sc = parseStatusLine(rawBuf, 0, statusLineEnd)
                // Detect HTTP version for keep-alive default
                val ka = if statusLineEnd >= 8 then rawBuf(5) == '1' && rawBuf(7) == '1' else true // "HTTP/1.1"
                parseHeaders(rawBuf, statusLineEnd + 2, headerEnd)
                (sc, ka)
            else
                (0, true)
            end if
        end val

        // Scan for special headers — carries (contentLengthVal, isChunked, isKeepAlive) as params
        @tailrec def scanHeaders(i: Int, contentLengthVal: Int, isChunked: Boolean, isKeepAlive: Boolean): (Int, Boolean, Boolean) =
            if i >= hdrOffsetCount then (contentLengthVal, isChunked, isKeepAlive)
            else
                val nameOff = hdrOffsets(i)
                val nameLen = hdrOffsets(i + 1)
                val valOff  = hdrOffsets(i + 2)
                val valLen  = hdrOffsets(i + 3)
                val (nextCl, nextChunked, nextKa) =
                    if HeaderTokens.nameEquals(rawBytes.array, nameOff, nameLen, "Content-Length") then
                        // A second Content-Length is refused rather than allowed to overwrite the first. RFC 9112 section 6.3
                        // item 5 makes conflicting values unrecoverable, and letting the last one win is the response-side
                        // half of the smuggling primitive: a proxy honouring the first and this client the last disagree
                        // about where the body ends, so the tail of one response becomes the head of the next. The request
                        // side already refuses this; the asymmetry was the gap.
                        val parsed = parseContentLength(rawBytes.array, valOff, valLen)
                        // RFC 9112 section 6.3 item 4: an invalid Content-Length makes the framing unrecoverable. Treating it
                        // as absent instead reads the response to connection close, which a proxy that DID parse a number
                        // frames differently, so the tail of one response becomes the head of the next.
                        if parsed < 0 then
                            invalid = true
                        // A second value is refused rather than allowed to overwrite the first (section 6.3 item 5). The
                        // comparison is against seenContentLength, not the running value, because a malformed first header
                        // leaves -1 behind and a >= 0 test could never fire against it: "abc" then "5" would be accepted.
                        if seenContentLength && parsed != contentLengthVal then
                            invalid = true
                        seenContentLength = true
                        (parsed, isChunked, isKeepAlive)
                    else if HeaderTokens.nameEquals(rawBytes.array, nameOff, nameLen, "Transfer-Encoding") then
                        // The final coding decides chunk framing (RFC 9112 section 6.1), matched as a whole token so a
                        // server cannot steer this client's view of where one response ends by sending "chunkedfoo".
                        // Unlike the request side this only frames and never rejects: a response with no chunked coding
                        // and no Content-Length is read until close, a legal response length (section 6.3 item 8).
                        val chunked = isChunked || HeaderTokens.finalCodingIsChunked(rawBytes.array, valOff, valLen)
                        (contentLengthVal, chunked, isKeepAlive)
                    else if HeaderTokens.nameEquals(rawBytes.array, nameOff, nameLen, "Connection") then
                        val ka =
                            if HeaderTokens.listContainsToken(rawBytes.array, valOff, valLen, "close") then false
                            else if HeaderTokens.listContainsToken(rawBytes.array, valOff, valLen, "keep-alive") then true
                            else isKeepAlive
                        (contentLengthVal, isChunked, ka)
                    else
                        (contentLengthVal, isChunked, isKeepAlive)
                    end if
                end val
                scanHeaders(i + 4, nextCl, nextChunked, nextKa)
        val (scannedCl, isChunked, isKeepAliveHdr) = scanHeaders(0, -1, false, isKeepAliveInit)

        // RFC 9112 section 6.3 item 3: with both present, Transfer-Encoding determines the length and Content-Length is
        // discarded outright. Keeping it would leave two framings in play, and a recipient that later consulted the
        // wrong one would read the wrong number of bytes. The client already prefers chunked when deciding how to read
        // the body, so this makes the value it would have fallen back to unavailable rather than merely unpreferred.
        val contentLengthVal = if isChunked then -1 else scannedCl

        // Per RFC 7230 §3.3.3 item 7: a response without Content-Length and without
        // Transfer-Encoding: chunked is terminated by connection close. Such a
        // connection MUST NOT be reused, regardless of what Connection header (if any)
        // the server sent. Force keep-alive off so the pool discards the connection
        // and the next request opens a fresh one.
        // Responses that cannot carry a body (1xx, 204, 304, and responses to HEAD)
        // are exempt — their framing is status-determined, not body-determined.
        val noBodyAllowed =
            statusCode < 200 || statusCode == 204 || statusCode == 304
        val isKeepAlive =
            if isKeepAliveHdr && !isChunked && contentLengthVal < 0 && !noBodyAllowed then false
            else isKeepAliveHdr

        // Build packed header array compatible with HttpHeaders.fromPacked
        val packedHeaders = buildPackedHeaders()

        new ParsedResponse(statusCode, packedHeaders, contentLengthVal, isChunked, isKeepAlive)
    end packResponse

    /** Parses "HTTP/1.1 200 OK" from rawBuf[start..end). Returns the status code. */
    private def parseStatusLine(rawBuf: Array[Byte], start: Int, end: Int): Int =
        // Find first space (after "HTTP/1.1")
        val sp1 = indexOfByte(rawBuf, start, end, ' ')
        if sp1 == -1 then 0
        else
            // Find second space (after status code, before reason phrase)
            val sp2     = indexOfByte(rawBuf, sp1 + 1, end, ' ')
            val codeEnd = if sp2 == -1 then end else sp2
            parseStatusCode(rawBuf, sp1 + 1, codeEnd - sp1 - 1)
        end if
    end parseStatusLine

    /** Parses a 3-digit status code from raw bytes. */
    private def parseStatusCode(src: Array[Byte], off: Int, len: Int): Int =
        if len != 3 then 0
        else
            val d1 = src(off) - '0'
            val d2 = src(off + 1) - '0'
            val d3 = src(off + 2) - '0'
            if d1 < 0 || d1 > 9 || d2 < 0 || d2 > 9 || d3 < 0 || d3 > 9 then 0
            else d1 * 100 + d2 * 10 + d3

    /** Parses headers from rawBuf[start..end). Each header is "Name: Value\r\n". */
    private def parseHeaders(rawBuf: Array[Byte], start: Int, end: Int): Unit =
        @tailrec def loop(lineStart: Int): Unit =
            if lineStart < end then
                val lineEnd       = indexOf2(rawBuf, lineStart, end, '\r', '\n')
                val actualLineEnd = if lineEnd == -1 then end else lineEnd

                if actualLineEnd > lineStart then
                    // A header line beginning with SP or HTAB is obs-fold (a continuation of the previous value). RFC
                    // 9112 section 5.2 has a user agent UNFOLD it (replace the fold with SP); kyo instead fails closed,
                    // a deliberate hardening deviation matching its request-side obs-fold reject and its bare CR/LF
                    // handling. Silently dropping the colon-less fold line (the prior behaviour) truncated the value.
                    val firstByte = rawBuf(lineStart)
                    if firstByte == ' ' || firstByte == '\t' then
                        invalid = true

                    val colonIdx = indexOfByte(rawBuf, lineStart, actualLineEnd, ':')
                    if colonIdx != -1 then
                        val nameStart = lineStart
                        val nameLen   = colonIdx - lineStart
                        val valStart  = skipSpaces(rawBuf, colonIdx + 1, actualLineEnd)
                        val valLen    = actualLineEnd - valStart

                        // RFC 9110 section 5.1: a field name is a token. A name is re-emitted verbatim, and one carrying
                        // SP or a colon reads to a recipient as a different name and value than the peer sent.
                        if !isToken(rawBuf, nameStart, nameLen) then
                            invalid = true

                        // RFC 9110 section 5.5 names NUL alongside CR and LF in its recipient MUST.
                        if containsNull(rawBuf, valStart, valLen) then
                            invalid = true

                        // Write name and value to rawBytes buffer, record offsets
                        val rawNameOff = rawBytes.size
                        rawBytes.writeBytes(rawBuf, nameStart, nameLen)
                        val rawValOff = rawBytes.size
                        rawBytes.writeBytes(rawBuf, valStart, valLen)

                        ensureHdrOffsets(4)
                        hdrOffsets(hdrOffsetCount) = rawNameOff
                        hdrOffsets(hdrOffsetCount + 1) = nameLen
                        hdrOffsets(hdrOffsetCount + 2) = rawValOff
                        hdrOffsets(hdrOffsetCount + 3) = valLen
                        hdrOffsetCount += 4
                        headerCount += 1
                    end if
                end if

                loop(if lineEnd == -1 then end else lineEnd + 2)
        end loop
        loop(start)
    end parseHeaders

    /** Builds a packed header array compatible with HttpHeaders.fromPacked format.
      *
      * Layout: [headerCount: 2 bytes] [nameOff:2 nameLen:2 valOff:2 valLen:2]* [raw bytes]
      *
      * Offsets in the packed array are relative to the raw bytes section start.
      */
    private def buildPackedHeaders(): Array[Byte] =
        val indexSize = 2 + headerCount * 8
        val rawSize   = rawBytes.size
        val totalSize = indexSize + rawSize
        val result    = new Array[Byte](totalSize)

        // Header count (big-endian)
        result(0) = ((headerCount >> 8) & 0xff).toByte
        result(1) = (headerCount & 0xff).toByte

        // Header offsets — already relative to rawBytes start, which matches
        // the packed format's expectation (relative to raw section at index `2 + headerCount * 8`)
        @tailrec def writeHeaders(i: Int, p: Int): Unit =
            if i < hdrOffsetCount then
                val nameOff = hdrOffsets(i)
                val nameLen = hdrOffsets(i + 1)
                val valOff  = hdrOffsets(i + 2)
                val valLen  = hdrOffsets(i + 3)
                result(p) = ((nameOff >> 8) & 0xff).toByte
                result(p + 1) = (nameOff & 0xff).toByte
                result(p + 2) = ((nameLen >> 8) & 0xff).toByte
                result(p + 3) = (nameLen & 0xff).toByte
                result(p + 4) = ((valOff >> 8) & 0xff).toByte
                result(p + 5) = (valOff & 0xff).toByte
                result(p + 6) = ((valLen >> 8) & 0xff).toByte
                result(p + 7) = (valLen & 0xff).toByte
                writeHeaders(i + 4, p + 8)
        writeHeaders(0, 2)

        // Raw bytes
        rawBytes.copyTo(result, indexSize)

        result
    end buildPackedHeaders

    private def ensureHdrOffsets(need: Int): Unit =
        if hdrOffsetCount + need >= hdrOffsets.length then
            val newArr = new Array[Int](hdrOffsets.length * 2)
            java.lang.System.arraycopy(hdrOffsets, 0, newArr, 0, hdrOffsetCount)
            hdrOffsets = newArr

    /** Parses a decimal integer from raw bytes. Returns -1 on failure. */
    private def parseContentLength(src: Array[Byte], off: Int, len: Int): Int =
        if len <= 0 then -1
        else
            @tailrec def loop(i: Int, acc: Int): Int =
                if i >= len then acc
                else
                    val b = src(off + i)
                    if b < '0' || b > '9' then -1
                    else
                        val digit = b - '0'
                        // Overflow guard (mirrors the request parser): Int.MaxValue = 2147483647. Without it a value past
                        // Int range wraps to a small number, so this client frames the body at a length a server that
                        // parsed the true value never sent, the response-side overflow desync (RFC 9112 section 6.3).
                        if acc > 214748364 || (acc == 214748364 && digit > 7) then -1
                        else loop(i + 1, acc * 10 + digit)
                    end if
            loop(0, 0)
    end parseContentLength

    /** Scans buf[i..end) for any CR byte not immediately followed by LF. */
    @tailrec private def containsBareCr(buf: Array[Byte], i: Int, end: Int): Boolean =
        if i >= end then false
        else if buf(i) == '\r' then
            if i + 1 >= end || buf(i + 1) != '\n' then true
            else containsBareCr(buf, i + 2, end) // skip past CRLF
        else containsBareCr(buf, i + 1, end)

    /** Scans buf[i..end) for any LF byte not immediately preceded by CR. */
    @tailrec private def containsBareLf(buf: Array[Byte], i: Int, end: Int): Boolean =
        if i >= end then false
        else if buf(i) == '\n' then
            if i == 0 || buf(i - 1) != '\r' then true
            else containsBareLf(buf, i + 1, end)
        else containsBareLf(buf, i + 1, end)

    /** Scans buf[off..off+len) for null bytes (0x00). */
    @tailrec private def containsNull(buf: Array[Byte], off: Int, remaining: Int): Boolean =
        if remaining <= 0 then false
        else if buf(off) == 0 then true
        else containsNull(buf, off + 1, remaining - 1)

    /** Whether buf[off..off+len) is an RFC 9110 section 5.6.2 `token`: `1*tchar`, so a zero length is not one. */
    private def isToken(buf: Array[Byte], off: Int, len: Int): Boolean =
        @tailrec def loop(i: Int): Boolean =
            if i >= len then true
            else if HttpHeaders.isTokenChar((buf(off + i) & 0xff).toChar) then loop(i + 1)
            else false
        len > 0 && loop(0)
    end isToken

    // Field name and value comparison lives in HeaderTokens; see the note in Http1Parser for why no prefix-comparing
    // variant is kept beside it.

    @tailrec private def skipSpaces(rawBuf: Array[Byte], from: Int, limit: Int): Int =
        if from < limit && rawBuf(from) == ' ' then skipSpaces(rawBuf, from + 1, limit)
        else from

    /** Finds the index of a byte pattern in buf[0..limit). Returns -1 if not found. */
    private def indexOf(buf: Array[Byte], limit: Int, pattern: Array[Byte]): Int =
        val patLen = pattern.length
        if limit < patLen then -1
        else
            @tailrec def outer(i: Int): Int =
                if i > limit - patLen then -1
                else if patternMatchesAt(buf, i, pattern, patLen) then i
                else outer(i + 1)
            outer(0)
        end if
    end indexOf

    @tailrec private def patternMatchesAt(buf: Array[Byte], pos: Int, pattern: Array[Byte], patLen: Int, j: Int = 0): Boolean =
        if j >= patLen then true
        else if buf(pos + j) != pattern(j) then false
        else patternMatchesAt(buf, pos, pattern, patLen, j + 1)

    private def indexOf2(buf: Array[Byte], start: Int, limit: Int, b1: Char, b2: Char): Int =
        @tailrec def loop(i: Int): Int =
            if i >= limit - 1 then -1
            else if buf(i) == b1.toByte && buf(i + 1) == b2.toByte then i
            else loop(i + 1)
        loop(start)
    end indexOf2

    private def indexOfByte(buf: Array[Byte], start: Int, end: Int, b: Char): Int =
        @tailrec def loop(i: Int): Int =
            if i >= end then -1
            else if buf(i) == b.toByte then i
            else loop(i + 1)
        loop(start)
    end indexOfByte

end Http1ResponseParser
