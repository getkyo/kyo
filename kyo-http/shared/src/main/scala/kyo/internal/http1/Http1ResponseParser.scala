package kyo.internal.http1

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.codec.*
import kyo.internal.util.*
import kyo.net.internal.util.GrowableByteBuffer
import kyo.scheduler.IOPromise
import scala.annotation.tailrec
import scala.util.control.NoStackTrace

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
    onFailure: Result.Error[Http1ClientConnection.ResponseFailure] => Unit = (_: Result.Error[Http1ClientConnection.ResponseFailure]) => ()
)(using allow: AllowUnsafe, frame: Frame):

    private val buf = new Array[Byte](maxHeaderSize)
    private var pos = 0

    // Reusable builder state for headers
    private val rawBytes       = new GrowableByteBuffer()
    private var headerCount    = 0
    private var hdrOffsets     = new Array[Int](128)
    private var hdrOffsetCount = 0
    // The first fault found in the status line, then the first found in the header section. The first one wins, so a head with
    // several faults always reports the same one. Each holds a constant from the companion or an interpolated status detail.
    private var statusFault: Maybe[String] = Absent
    private var invalid: Maybe[String]     = Absent
    // Whether a Content-Length header has been seen at all, which a running value of -1 cannot express.
    private var seenContentLength = false

    private var headRequest  = false
    private var rawAfterHead = false
    // Whether the peer sent any byte since the request was sent. A close before any byte is the only failure after which the client
    // knows the peer acknowledged nothing, which is what lets a stale pooled connection's request be retried.
    private var received = false

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
                case Present(read) => onRead(read)
                case Absent        =>
                    // Unreachable: this hook runs only after the compare-and-set to a completed state, and only the
                    // becomeAvailable above returns the promise to pending.
                    onFailure(Result.Panic(new IllegalStateException("the response take completed without a value") with NoStackTrace))
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
    def start(): Unit = needMoreBytes()

    /** Whether any byte arrived from the peer since the last [[reset]]. */
    def receivedAny: Boolean = received

    /** Handles the outcome of one inbound read, whether it came from the take or from a poll, so each outcome has one handling. */
    private[kyo] def onRead(read: Result[Closed, Span[Byte]]): Unit =
        read match
            case Result.Success(span) =>
                if span.nonEmpty then received = true
                // The limit bounds the head, not the read that carries it: a read may hold a small head and a large body. Only
                // what still fits is copied; the rest of the read stays in the span and is body, or the head is too large.
                val copied = math.min(span.size, maxHeaderSize - pos)
                discard(span.copyToArray(buf, pos, copied))
                pos += copied
                parse(span, copied)
            case Result.Failure(_) =>
                fail(HttpConnectionClosedException(HttpConnectionClosedException.Phase.BeforeHead))
            case panic: Result.Panic =>
                Log.live.unsafe.error("Http1ResponseParser read panic", panic.exception)
                onFailure(panic)
    end onRead

    private def fail(failure: Http1ClientConnection.ResponseFailure): Unit =
        // Bytes left in the buffer belong to a response that was refused, so none of them may be read as the next one.
        pos = 0
        onFailure(Result.Failure(failure))
    end fail

    /** Parses the bytes in `buf`, where `read` is the read that last filled it and `read[copied..)` is its part that did not fit. */
    @tailrec private def parse(read: Span[Byte], copied: Int): Unit =
        val headerEnd = indexOf(buf, pos, Http1Parser.CRLF_CRLF)
        if headerEnd == -1 then
            // A head that fits ends within the first maxHeaderSize bytes, so a full buffer without a terminator is a larger head.
            if pos == maxHeaderSize then fail(HttpProtocolException(s"the response head exceeds $maxHeaderSize bytes"))
            else needMoreBytes()
        else
            val response = packResponse(buf, headerEnd)
            if statusFault.nonEmpty then fail(HttpProtocolException(statusFault.get))
            else if invalid.nonEmpty then fail(HttpProtocolException(invalid.get))
            else if response.statusCode < 200 && response.statusCode != 101 then
                // An interim response precedes the final one on the same request (RFC 9110 section 15.2), so it is skipped and the
                // bytes after it are the start of the next head. A 101 is final: the connection switches protocol after it.
                val next  = headerEnd + 4
                val inBuf = pos - next
                java.lang.System.arraycopy(buf, next, buf, 0, inBuf)
                pos = inBuf
                val more = math.min(read.size - copied, maxHeaderSize - pos)
                if more > 0 then java.lang.System.arraycopy(read.toArrayUnsafe, copied, buf, pos, more)
                pos += more
                parse(read, copied + more)
            else
                val bodyStart = headerEnd + 4
                val inBuf     = pos - bodyStart
                val remaining = inBuf + (read.size - copied)
                val bodyLen   = math.min(remaining, bodyLimit(response))
                val bodySpan  =
                    if bodyLen <= 0 then Span.empty[Byte]
                    else
                        val bodyArr  = new Array[Byte](bodyLen)
                        val fromBuf  = math.min(inBuf, bodyLen)
                        val fromRead = bodyLen - fromBuf
                        java.lang.System.arraycopy(buf, bodyStart, bodyArr, 0, fromBuf)
                        if fromRead > 0 then java.lang.System.arraycopy(read.toArrayUnsafe, copied, bodyArr, fromBuf, fromRead)
                        Span.fromUnsafe(bodyArr)
                pos = 0
                // RFC 9112 section 6.3: bytes after the end of this response belong to no request the client sent, since it
                // sends the next request only after this response is read, and it MUST NOT read them as a separate response.
                // They are dropped with the connection, which carries nothing trustworthy after them.
                val delivered =
                    if remaining > bodyLen && response.isKeepAlive then
                        new ParsedResponse(response.statusCode, response.packedHeaders, response.contentLength, response.isChunked, false)
                    else response
                onResponseParsed(delivered, bodySpan)
            end if
        end if
    end parse

    /** How many of the bytes after the head can belong to this response's body. A body-less response ends at its head (RFC 9110
      * sections 6.4.1, 9.3.2, 15.3.5 and 15.4.5), whatever Content-Length it declares. A chunked or close-framed body takes every byte,
      * since its decoder finds its end, and so does a raw exchange, whose bytes after the head are the tunnelled protocol.
      */
    private def bodyLimit(response: ParsedResponse): Int =
        val status = response.statusCode
        if rawAfterHead then Int.MaxValue
        else if headRequest || status < 200 || status == 204 || status == 304 then 0
        else if response.isChunked || response.contentLength < 0 then Int.MaxValue
        else response.contentLength
        end if
    end bodyLimit

    private def needMoreBytes(): Unit =
        inbound.poll() match
            case Result.Success(Present(span)) => onRead(Result.succeed(span))
            case Result.Success(Absent)        =>
                // No data available: register the take promise directly. It is pending either because it is fresh or
                // because onComplete reset it before calling parse().
                inbound.reuseTake(takePromiseUnsafe)
            case Result.Failure(closed) => onRead(Result.fail(closed))
            case Result.Panic(t)        => onRead(Result.panic(t))
    end needMoreBytes

    /** Resets the parser for the response to the request just sent on this connection.
      *
      * @param requestMethod
      *   the request's method; the response to a HEAD ends at its head
      * @param rawAfterHead
      *   whether every byte after the head belongs to the caller, as on a raw connection, whose bytes after the head are the tunnelled
      *   protocol rather than an HTTP body
      */
    def reset(requestMethod: HttpMethod, rawAfterHead: Boolean): Unit =
        headRequest = requestMethod == HttpMethod.HEAD
        this.rawAfterHead = rawAfterHead
        received = false
        pos = 0
        rawBytes.reset()
        headerCount = 0
        hdrOffsetCount = 0
        statusFault = Absent
        invalid = Absent
        seenContentLength = false
    end reset

    private def flagInvalid(detail: String): Unit =
        if invalid.isEmpty then invalid = Present(detail)

    /** Parses the status line and headers from raw bytes into a ParsedResponse. */
    private def packResponse(rawBuf: Array[Byte], headerEnd: Int): ParsedResponse =
        import Http1ResponseParser.*
        rawBytes.reset()
        headerCount = 0
        hdrOffsetCount = 0
        statusFault = Absent
        invalid = Absent
        seenContentLength = false

        // A response header is stored as the raw octets it was parsed from and is written back out verbatim, so a peer
        // that smuggles a line break past this parser has it re-emitted unchanged by any proxy that echoes the header.
        // Every legitimate CR and LF in this region is half of a CRLF; a bare one is a line terminator a downstream MAY
        // recognize (RFC 9112 section 2.2), and rejecting it is a recipient MUST (RFC 9110 section 5.5).
        if containsBareCr(rawBuf, 0, headerEnd) || containsBareLf(rawBuf, 0, headerEnd) then
            flagInvalid(BareCrOrLf)

        // The first CRLF, which exists because headerEnd starts one.
        val statusLineEnd = indexOf(rawBuf, headerEnd + 2, Http1Parser.CRLF_SINGLE)

        // RFC 9112 section 4: status-line = HTTP-version SP status-code SP [ reason-phrase ], where HTTP-version is the
        // case-sensitive "HTTP/" DIGIT "." DIGIT (section 2.3) and only major version 1 has this syntax (RFC 9110 section 2.5).
        // The keep-alive default reads the version's digits at bytes 5 and 7, which this check guarantees are there.
        val versionOk =
            statusLineEnd >= 9 && startsWith(rawBuf, Http1Prefix) && isDigit(rawBuf(7)) && rawBuf(8) == ' '
        val statusCode =
            if !versionOk then
                statusFault = Present(NoHttpVersion)
                0
            else
                val code = parseStatusLine(rawBuf, statusLineEnd)
                if code < 0 then statusFault = Present(NoStatusCode)
                else if code < 100 || code > 599 then statusFault = Present(s"the response status code $code is outside 100 to 599")
                math.max(code, 0)
            end if
        end statusCode
        val isKeepAliveInit = !versionOk || rawBuf(7) == '1'
        parseHeaders(rawBuf, statusLineEnd + 2, headerEnd)

        // Scan for special headers — carries (contentLengthVal, isChunked, isKeepAlive) as params
        @tailrec def scanHeaders(i: Int, contentLengthVal: Int, isChunked: Boolean, isKeepAlive: Boolean): (Int, Boolean, Boolean) =
            if i >= hdrOffsetCount then (contentLengthVal, isChunked, isKeepAlive)
            else
                val nameOff                       = hdrOffsets(i)
                val nameLen                       = hdrOffsets(i + 1)
                val valOff                        = hdrOffsets(i + 2)
                val valLen                        = hdrOffsets(i + 3)
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
                            flagInvalid(InvalidContentLength)
                        // A second value is refused rather than allowed to overwrite the first (section 6.3 item 5). The
                        // comparison is against seenContentLength, not the running value, because a malformed first header
                        // leaves -1 behind and a >= 0 test could never fire against it: "abc" then "5" would be accepted.
                        if seenContentLength && parsed != contentLengthVal then
                            flagInvalid(ConflictingContentLength)
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

    /** The status code of a status line whose version and first SP are at bytes 0 to 8 of rawBuf[0..end), or -1 when the field after
      * that SP is not three digits. The reason phrase and its SP are optional.
      */
    private def parseStatusLine(rawBuf: Array[Byte], end: Int): Int =
        val sp      = indexOfByte(rawBuf, 9, end, ' ')
        val codeEnd = if sp == -1 then end else sp
        parseStatusCode(rawBuf, 9, codeEnd - 9)
    end parseStatusLine

    /** Parses a 3-digit status code from raw bytes, or -1 when they are not three digits. */
    private def parseStatusCode(src: Array[Byte], off: Int, len: Int): Int =
        if len != 3 || !isDigit(src(off)) || !isDigit(src(off + 1)) || !isDigit(src(off + 2)) then -1
        else (src(off) - '0') * 100 + (src(off + 1) - '0') * 10 + (src(off + 2) - '0')

    private def isDigit(b: Byte): Boolean = b >= '0' && b <= '9'

    private def startsWith(rawBuf: Array[Byte], prefix: Array[Byte]): Boolean =
        patternMatchesAt(rawBuf, 0, prefix, prefix.length)

    /** Parses headers from rawBuf[start..end). Each header is "Name: Value\r\n". */
    private def parseHeaders(rawBuf: Array[Byte], start: Int, end: Int): Unit =
        import Http1ResponseParser.*
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
                        flagInvalid(Folded)

                    val colonIdx = indexOfByte(rawBuf, lineStart, actualLineEnd, ':')
                    if colonIdx != -1 then
                        val nameStart = lineStart
                        val nameLen   = colonIdx - lineStart
                        val valStart  = skipSpaces(rawBuf, colonIdx + 1, actualLineEnd)
                        val valLen    = actualLineEnd - valStart

                        // RFC 9110 section 5.1: a field name is a token. A name is re-emitted verbatim, and one carrying
                        // SP or a colon reads to a recipient as a different name and value than the peer sent.
                        if !isToken(rawBuf, nameStart, nameLen) then
                            flagInvalid(NameNotToken)

                        // RFC 9110 section 5.5 names NUL alongside CR and LF in its recipient MUST.
                        if containsNull(rawBuf, valStart, valLen) then
                            flagInvalid(NulInValue)

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

    /** Skips the optional whitespace before a field value, SP or HTAB (RFC 9110 section 5.6.3). */
    @tailrec private def skipSpaces(rawBuf: Array[Byte], from: Int, limit: Int): Int =
        if from < limit && (rawBuf(from) == ' ' || rawBuf(from) == '\t') then skipSpaces(rawBuf, from + 1, limit)
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

private[kyo] object Http1ResponseParser:

    // The details of a refused head. Each names the fault and never echoes the peer's bytes, so a detail cannot carry a smuggled
    // line into a log or an exception message.
    private val NoHttpVersion            = "the response status line does not begin with HTTP/1.x"
    private val NoStatusCode             = "the response status line has no three-digit status code"
    private val BareCrOrLf               = "a response header line holds a bare CR or LF"
    private val Folded                   = "a response header line is folded"
    private val NameNotToken             = "a response header name is not a token"
    private val NulInValue               = "a response header value holds a NUL"
    private val InvalidContentLength     = "the response Content-Length is not a valid length"
    private val ConflictingContentLength = "the response has conflicting Content-Length values"

    private val Http1Prefix = "HTTP/1.".getBytes(java.nio.charset.StandardCharsets.US_ASCII)

end Http1ResponseParser
