package kyo.internal.http1

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.codec.*
import kyo.scheduler.IOPromise
import scala.annotation.tailrec

/** Zero-copy HTTP/1.1 request parser. Callback-driven state machine that reads from the inbound Channel.Unsafe, accumulates bytes in a
  * reusable flat buffer, and produces ParsedRequest values.
  *
  * Flow: start() -> needMoreBytes() -> TakePromise.onComplete() -> parse() -> onRequestParsed callback. On keep-alive: reset() then start()
  * again, optionally with injectLeftover() for pipelined bytes.
  *
  * TakePromise extends IOPromise and is registered directly as a channel taker via reuseTake. becomeAvailable() resets the promise in-place
  * so it can be reused for the next read without allocation. The reset happens BEFORE the parse() call so that if parse() immediately calls
  * needMoreBytes(), the promise is already in Pending state.
  *
  * All parsing is zero-allocation: byte comparisons are done on the raw buf[] array; strings are produced only in ParsedRequest extension
  * methods when the caller requests them.
  */
final private[kyo] class Http1Parser(
    inbound: Channel.Unsafe[Span[Byte]],
    builder: ParsedRequestBuilder,
    // Configurable via HttpTransportConfig.maxHeaderSize when that type is introduced.
    maxHeaderSize: Int = 65536,
    onRequestParsed: (ParsedRequest, Span[Byte]) => Unit = (_, _) => (),
    onClosed: () => Unit = () => ()
)(using allow: AllowUnsafe, frame: Frame):

    private val buf                  = new Array[Byte](maxHeaderSize)
    private var pos                  = 0
    private var hostCount            = 0
    private var hasUpgradeWebSocket  = false
    private var hasConnectionUpgrade = false
    private var invalid              = false
    private var hasContentLength     = false
    private var hasTransferEncoding  = false

    /** Reusable take promise that extends IOPromise to be directly registered as a channel taker. The onComplete override fires
      * synchronously when the channel delivers data. The resetForReuse method exposes the protected becomeAvailable for the parser to call.
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
                    // Expected on normal connection close (keep-alive termination, client disconnect)
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

    /** Starts the parser by initiating the first read from the inbound channel. If there are already bytes in the buffer (e.g., from HTTP
      * pipelining), attempts to parse them first.
      */
    def start(): Unit =
        if pos > 0 then
            parse()
        else
            needMoreBytes()

    private def parse(): Unit =
        val headerEnd = indexOf(buf, pos, Http1Parser.CRLF_CRLF)
        if headerEnd == -1 then
            needMoreBytes()
        else
            val request = packRequest(buf, headerEnd)
            // Compact: move remaining bytes (body) to start
            val remaining = pos - (headerEnd + 4)
            if remaining > 0 then
                java.lang.System.arraycopy(buf, headerEnd + 4, buf, 0, remaining)
            pos = remaining
            // Reject invalid requests before processing body
            if invalid then
                onClosed()
            else
                // Extract body bytes available in the parser buffer.
                // For Content-Length requests: extract up to contentLength bytes.
                // For chunked requests: extract all remaining bytes (chunk framing data)
                //   so the ChunkedBodyDecoder can use them as initial data.
                val cl = request.contentLength
                val bodySpan =
                    if cl > 0 && pos > 0 then
                        val bodyLen = math.min(pos, cl)
                        val bodyArr = new Array[Byte](bodyLen)
                        java.lang.System.arraycopy(buf, 0, bodyArr, 0, bodyLen)
                        // Advance past consumed body bytes
                        val bodyRemaining = pos - bodyLen
                        if bodyRemaining > 0 then
                            java.lang.System.arraycopy(buf, bodyLen, buf, 0, bodyRemaining)
                        pos = bodyRemaining
                        Span.fromUnsafe(bodyArr)
                    else if request.isChunked && pos > 0 then
                        // Pass remaining bytes to the chunked body decoder
                        val bodyArr = new Array[Byte](pos)
                        java.lang.System.arraycopy(buf, 0, bodyArr, 0, pos)
                        pos = 0
                        Span.fromUnsafe(bodyArr)
                    else
                        Span.empty[Byte]
                onRequestParsed(request, bodySpan)
            end if
        end if
    end parse

    private def needMoreBytes(): Unit =
        // Try non-blocking poll first (0 alloc)
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
            case Result.Failure(e) =>
                Log.live.unsafe.error("Http1Parser poll failure", e)
                onClosed()
            case Result.Panic(t) =>
                Log.live.unsafe.error("Http1Parser poll panic", t)
                onClosed()
    end needMoreBytes

    /** Resets the parser for the next request on the same connection. */
    def reset(): Unit =
        builder.reset()
        hostCount = 0
        hasUpgradeWebSocket = false
        hasConnectionUpgrade = false
        invalid = false
        hasContentLength = false
        hasTransferEncoding = false
        // Note: buf and pos are managed by parse/compact, not reset here.
        // The pos may have leftover bytes from pipelining, which is correct.
    end reset

    /** Injects leftover bytes (from body reading beyond Content-Length boundary) into the parser's buffer so they are available for the
      * next request parse. Must be called between reset() and start() on keep-alive connections.
      */
    def injectLeftover(data: Span[Byte]): Unit =
        if !data.isEmpty then
            val len = data.size
            if pos + len <= maxHeaderSize then
                discard(data.copyToArray(buf, pos))
                pos += len
    end injectLeftover

    /** Extracts any remaining bytes in the parser buffer and resets pos to 0. Used during HttpWebSocket upgrade to forward leftover bytes
      * (after HTTP headers) to the WS codec. After this call, the parser buffer is empty.
      */
    def takeRemainingBytes(): Span[Byte] =
        if pos <= 0 then Span.empty[Byte]
        else
            val arr = new Array[Byte](pos)
            java.lang.System.arraycopy(buf, 0, arr, 0, pos)
            pos = 0
            Span.fromUnsafe(arr)
    end takeRemainingBytes

    /** Parses the request line and headers from raw bytes into a ParsedRequest via the builder. */
    private def packRequest(rawBuf: Array[Byte], headerEnd: Int): ParsedRequest =
        builder.reset()
        hostCount = 0
        hasUpgradeWebSocket = false
        hasConnectionUpgrade = false
        invalid = false
        hasContentLength = false
        hasTransferEncoding = false

        // Find the end of the request line (first CRLF)
        val requestLineEnd = indexOf(rawBuf, headerEnd, Http1Parser.CRLF_SINGLE)

        if requestLineEnd == -1 then
            // Malformed: no request line found — return minimal request
            builder.build()
        else
            // Scan for bare CR (CR not followed by LF) in entire header region
            if containsBareCr(rawBuf, 0, headerEnd) then
                invalid = true
            parseRequestLine(rawBuf, 0, requestLineEnd)
            parseHeaders(rawBuf, requestLineEnd + 2, headerEnd)
            // Set Host header flags based on count observed during parsing
            if hostCount >= 1 then
                builder.setHasHost(true)
            if hostCount > 1 then
                builder.setMultipleHost(true)
            // Set HttpWebSocket upgrade flag when both Upgrade: websocket and Connection: upgrade are present
            if hasUpgradeWebSocket && hasConnectionUpgrade then
                builder.setUpgrade(true)
            builder.build()
        end if
    end packRequest

    /** Parses "METHOD /path?query HTTP/1.1" from rawBuf[start..end). Structured as nested if/else to avoid `return` keywords.
      */
    private def parseRequestLine(rawBuf: Array[Byte], start: Int, end: Int): Unit =
        // Find first space (after method)
        val sp1 = indexOfByte(rawBuf, start, end, ' ')
        if sp1 != -1 then
            // Parse method via byte-level comparison (zero-alloc)
            val ordinal = matchMethod(rawBuf, start, sp1 - start)
            if ordinal != -1 then
                builder.setMethod(ordinal)

                // RFC 7230: exactly one SP between tokens. Reject multiple spaces.
                if sp1 + 1 < end && rawBuf(sp1 + 1) == ' ' then
                    invalid = true

                // Find second space (before HTTP version)
                val sp2 = indexOfByte(rawBuf, sp1 + 1, end, ' ')
                if sp2 != -1 then
                    // Reject if there's a space immediately after sp2 (triple+ space)
                    if sp2 + 1 < end && rawBuf(sp2 + 1) == ' ' then
                        invalid = true
                    // The URI is between sp1+1 and sp2
                    val uriStart = sp1 + 1
                    val uriEnd   = sp2

                    // Check HTTP version for keep-alive default
                    // HTTP/1.1 defaults to keep-alive, HTTP/1.0 defaults to close
                    val versionStart = sp2 + 1
                    val isHttp11 =
                        if end - versionStart >= 8 then
                            rawBuf(versionStart + 5) == '1' && rawBuf(versionStart + 7) == '1'
                        else
                            true // default to 1.1
                    builder.setKeepAlive(isHttp11)

                    // Split URI into path and query
                    val qMark = indexOfByte(rawBuf, uriStart, uriEnd, '?')
                    if qMark == -1 then
                        // No query string
                        builder.setPath(rawBuf, uriStart, uriEnd - uriStart)
                        parsePathSegments(rawBuf, uriStart, uriEnd)
                    else
                        builder.setPath(rawBuf, uriStart, qMark - uriStart)
                        parsePathSegments(rawBuf, uriStart, qMark)
                        if qMark + 1 < uriEnd then
                            builder.setQuery(rawBuf, qMark + 1, uriEnd - qMark - 1)
                    end if
                end if
            end if
        end if
    end parseRequestLine

    /** Parses path segments from /seg1/seg2/seg3. */
    private def parsePathSegments(rawBuf: Array[Byte], start: Int, end: Int): Unit =
        val initialI = if start < end && rawBuf(start) == '/' then start + 1 else start
        @tailrec def loop(i: Int, segStart: Int): Unit =
            if i <= end then
                if i == end || rawBuf(i) == '/' then
                    if i > segStart then
                        builder.addPathSegment(rawBuf, segStart, i - segStart)
                    loop(i + 1, i + 1)
                else
                    loop(i + 1, segStart)
            end if
        end loop
        loop(initialI, initialI)
    end parsePathSegments

    /** Parses headers from rawBuf[start..end). Each header is "Name: Value\r\n". */
    private def parseHeaders(rawBuf: Array[Byte], start: Int, end: Int): Unit =
        @tailrec def loop(lineStart: Int): Unit =
            if lineStart < end then
                // Find end of this header line
                val lineEnd       = indexOf2(rawBuf, lineStart, end, '\r', '\n')
                val actualLineEnd = if lineEnd == -1 then end else lineEnd

                if actualLineEnd > lineStart then
                    // RFC 7230 section 3.2.4: reject obs-fold (line starting with SP or HTAB)
                    val firstByte = rawBuf(lineStart)
                    if firstByte == ' ' || firstByte == '\t' then
                        invalid = true

                    // Find the colon separator
                    val colonIdx = indexOfByte(rawBuf, lineStart, actualLineEnd, ':')
                    if colonIdx != -1 then
                        val nameStart = lineStart
                        val nameLen   = colonIdx - lineStart

                        // RFC 7230 section 3.2.4: no whitespace between header name and colon
                        if nameLen > 0 && (rawBuf(colonIdx - 1) == ' ' || rawBuf(colonIdx - 1) == '\t') then
                            invalid = true

                        // Reject null bytes in header name
                        if containsNull(rawBuf, nameStart, nameLen) then
                            invalid = true

                        // Skip ": " (colon + optional OWS including HTAB)
                        val valStart = skipSpaces(rawBuf, colonIdx + 1, actualLineEnd)
                        val valLen   = actualLineEnd - valStart

                        // Reject null bytes in header value
                        if containsNull(rawBuf, valStart, valLen) then
                            invalid = true

                        builder.addHeader(rawBuf, nameStart, nameLen, rawBuf, valStart, valLen)

                        // Detect special headers
                        detectSpecialHeader(rawBuf, nameStart, nameLen, rawBuf, valStart, valLen)
                    else
                        // CVE-2019-20444: header line without colon is invalid
                        invalid = true
                    end if
                end if

                // Move past CRLF
                loop(if lineEnd == -1 then end else lineEnd + 2)
        end loop
        loop(start)
    end parseHeaders

    /** Skips OWS bytes (SP and HTAB) starting at `from` up to `limit`. RFC 7230: OWS = *( SP / HTAB ). */
    @tailrec private def skipSpaces(rawBuf: Array[Byte], from: Int, limit: Int): Int =
        if from < limit then
            val b = rawBuf(from)
            if b == ' ' || b == '\t' then skipSpaces(rawBuf, from + 1, limit)
            else from
        else from

    /** Checks for Content-Length, Transfer-Encoding, and Connection headers. */
    private def detectSpecialHeader(
        nameSrc: Array[Byte],
        nameOff: Int,
        nameLen: Int,
        valSrc: Array[Byte],
        valOff: Int,
        valLen: Int
    ): Unit =
        if nameLen == 14 && asciiEqualsIgnoreCase(nameSrc, nameOff, "Content-Length") then
            if hasContentLength then
                invalid = true
            else
                hasContentLength = true
                if hasTransferEncoding then
                    invalid = true
                val cl = parseContentLength(valSrc, valOff, valLen)
                if cl < 0 then
                    invalid = true
                builder.setContentLength(cl)
        else if nameLen == 17 && asciiEqualsIgnoreCase(nameSrc, nameOff, "Transfer-Encoding") then
            hasTransferEncoding = true
            if hasContentLength then
                invalid = true
            if valLen >= 7 && asciiEqualsIgnoreCase(valSrc, valOff, "chunked") then
                builder.setChunked(true)
        else if nameLen == 10 && asciiEqualsIgnoreCase(nameSrc, nameOff, "Connection") then
            if valLen >= 5 && asciiEqualsIgnoreCase(valSrc, valOff, "close") then
                builder.setKeepAlive(false)
            else if valLen >= 10 && asciiEqualsIgnoreCase(valSrc, valOff, "keep-alive") then
                builder.setKeepAlive(true)
            end if
            // Also check for "upgrade" token in Connection header (may be sole value or comma-separated)
            if valLen >= 7 && asciiContainsIgnoreCase(valSrc, valOff, valLen, "upgrade") then
                hasConnectionUpgrade = true
        else if nameLen == 6 && asciiEqualsIgnoreCase(nameSrc, nameOff, "Expect") then
            if valLen >= 12 && asciiEqualsIgnoreCase(valSrc, valOff, "100-continue") then
                builder.setExpectContinue(true)
        else if nameLen == 4 && asciiEqualsIgnoreCase(nameSrc, nameOff, "Host") then
            hostCount += 1
            if valLen == 0 then
                builder.setEmptyHost(true)
        else if nameLen == 7 && asciiEqualsIgnoreCase(nameSrc, nameOff, "Upgrade") then
            if valLen >= 9 && asciiEqualsIgnoreCase(valSrc, valOff, "websocket") then
                hasUpgradeWebSocket = true
        end if
    end detectSpecialHeader

    /** Parses a decimal integer from raw bytes. Returns -1 on failure or overflow. */
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
                        // Overflow guard: Int.MaxValue = 2147483647
                        // If acc > 214748364, then acc*10 overflows.
                        // If acc == 214748364 and digit > 7, then acc*10+digit overflows.
                        if acc > 214748364 || (acc == 214748364 && digit > 7) then -1
                        else loop(i + 1, acc * 10 + digit)
                    end if
            loop(0, 0)
    end parseContentLength

    /** Case-insensitive comparison of raw bytes against an ASCII string. */
    private def asciiEqualsIgnoreCase(src: Array[Byte], off: Int, target: String): Boolean =
        @tailrec def loop(i: Int): Boolean =
            if i >= target.length then true
            else
                val a = (src(off + i) & 0xff).toChar.toLower
                val b = target.charAt(i).toLower
                if a != b then false
                else loop(i + 1)
        loop(0)
    end asciiEqualsIgnoreCase

    /** Checks if target appears anywhere in src[off..off+len) using case-insensitive comparison. */
    private def asciiContainsIgnoreCase(src: Array[Byte], off: Int, len: Int, target: String): Boolean =
        val targetLen = target.length
        if len < targetLen then false
        else
            @tailrec def scan(i: Int): Boolean =
                if i + targetLen > len then false
                else if asciiEqualsIgnoreCase(src, off + i, target) then true
                else scan(i + 1)
            scan(0)
        end if
    end asciiContainsIgnoreCase

    /** Scans buf[i..end) for any CR byte not immediately followed by LF. */
    @tailrec private def containsBareCr(buf: Array[Byte], i: Int, end: Int): Boolean =
        if i >= end then false
        else if buf(i) == '\r' then
            if i + 1 >= end || buf(i + 1) != '\n' then true
            else containsBareCr(buf, i + 2, end) // skip past CRLF
        else containsBareCr(buf, i + 1, end)

    /** Scans buf[off..off+len) for null bytes (0x00). */
    @tailrec private def containsNull(buf: Array[Byte], off: Int, remaining: Int): Boolean =
        if remaining <= 0 then false
        else if buf(off) == 0 then true
        else containsNull(buf, off + 1, remaining - 1)

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

    /** Checks if pattern matches buf at the given position. */
    @tailrec private def patternMatchesAt(buf: Array[Byte], pos: Int, pattern: Array[Byte], patLen: Int, j: Int = 0): Boolean =
        if j >= patLen then true
        else if buf(pos + j) != pattern(j) then false
        else patternMatchesAt(buf, pos, pattern, patLen, j + 1)

    /** Finds the index of a two-byte sequence in buf[start..limit). */
    private def indexOf2(buf: Array[Byte], start: Int, limit: Int, b1: Char, b2: Char): Int =
        @tailrec def loop(i: Int): Int =
            if i >= limit - 1 then -1
            else if buf(i) == b1.toByte && buf(i + 1) == b2.toByte then i
            else loop(i + 1)
        loop(start)
    end indexOf2

    /** Finds the index of a single byte in buf[start..end). */
    private def indexOfByte(buf: Array[Byte], start: Int, end: Int, b: Char): Int =
        @tailrec def loop(i: Int): Int =
            if i >= end then -1
            else if buf(i) == b.toByte then i
            else loop(i + 1)
        loop(start)
    end indexOfByte

    /** Returns the ParsedRequest ordinal for the HTTP method at buf[start..start+len), or -1 for unknown. Ordinals must match the `methods`
      * array in ParsedRequest: 0=GET, 1=POST, 2=PUT, 3=PATCH, 4=DELETE, 5=HEAD, 6=OPTIONS, 7=TRACE, 8=CONNECT
      */
    private def matchMethod(buf: Array[Byte], start: Int, len: Int): Int =
        if len == 3 then
            if buf(start) == 'G' && buf(start + 1) == 'E' && buf(start + 2) == 'T' then 0      // GET
            else if buf(start) == 'P' && buf(start + 1) == 'U' && buf(start + 2) == 'T' then 2 // PUT
            else -1
        else if len == 4 then
            if buf(start) == 'P' && buf(start + 1) == 'O' && buf(start + 2) == 'S' && buf(start + 3) == 'T' then 1      // POST
            else if buf(start) == 'H' && buf(start + 1) == 'E' && buf(start + 2) == 'A' && buf(start + 3) == 'D' then 5 // HEAD
            else -1
        else if len == 5 then
            if buf(start) == 'P' && buf(start + 1) == 'A' && buf(start + 2) == 'T' && buf(start + 3) == 'C' && buf(start + 4) == 'H' then
                3 // PATCH
            else if buf(start) == 'T' && buf(start + 1) == 'R' && buf(start + 2) == 'A' && buf(start + 3) == 'C' && buf(start + 4) == 'E'
            then 7 // TRACE
            else -1
        else if len == 6 then
            if buf(start) == 'D' && buf(start + 1) == 'E' && buf(start + 2) == 'L' && buf(start + 3) == 'E' && buf(start + 4) == 'T' && buf(
                    start + 5
                ) == 'E'
            then 4 // DELETE
            else -1
        else if len == 7 then
            if buf(start) == 'O' && buf(start + 1) == 'P' && buf(start + 2) == 'T' && buf(start + 3) == 'I' && buf(start + 4) == 'O' && buf(
                    start + 5
                ) == 'N' && buf(start + 6) == 'S'
            then 6 // OPTIONS
            else if buf(start) == 'C' && buf(start + 1) == 'O' && buf(start + 2) == 'N' && buf(start + 3) == 'N' && buf(
                    start + 4
                ) == 'E' && buf(start + 5) == 'C' && buf(start + 6) == 'T'
            then 8 // CONNECT
            else -1
        else -1
    end matchMethod

end Http1Parser

private[kyo] object Http1Parser:
    val CRLF_CRLF: Array[Byte]   = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII)
    val CRLF_SINGLE: Array[Byte] = "\r\n".getBytes(StandardCharsets.US_ASCII)
end Http1Parser
