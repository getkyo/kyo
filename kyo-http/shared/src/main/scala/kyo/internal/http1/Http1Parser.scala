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
    onClosed: () => Unit = () => (),
    // Distinct from onClosed: the peer is still there and is owed an answer. RFC 9112 section 6.3 requires a 400 for a
    // message whose framing cannot be determined, and closing without one leaves the peer waiting out its own timeout
    // for a verdict already reached here. Defaults to onClosed so a caller that only reads keeps the old behavior.
    onInvalidRequest: () => Unit = () => ()
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
            // Reject invalid requests before processing body. The peer is answered rather than merely disconnected: the
            // framing is undeterminable, so nothing further can be read from this connection, but a 400 tells the peer
            // that now instead of leaving it to infer a verdict from silence.
            if invalid then
                onInvalidRequest()
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
            if builder.offsetsOverflowed then invalid = true
            builder.build()
        else
            // Scan for bare CR (CR not followed by LF) in entire header region
            if containsBareCr(rawBuf, 0, headerEnd) then
                invalid = true
            // Scan for bare LF (LF not preceded by CR) in the same region. Every legitimate LF here is the second
            // byte of a CRLF, so any other is a peer smuggling a line break past the CRLF framing: RFC 9112
            // section 2.2 lets a downstream recognize it as a line terminator, which turns one header this parser
            // stores into two headers that recipient reads. RFC 9110 section 5.5 makes rejecting it a MUST.
            if containsBareLf(rawBuf, 0, headerEnd) then
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
            if builder.offsetsOverflowed then invalid = true
            builder.build()
        end if
    end packRequest

    /** Parses "METHOD /path?query HTTP/1.1" from rawBuf[start..end). Structured as nested if/else to avoid `return` keywords.
      */
    private def parseRequestLine(rawBuf: Array[Byte], start: Int, end: Int): Unit =
        // Every failure branch here marks the request invalid rather than falling through. Falling through left the
        // builder at its defaults: method ordinal 0, which is GET, and no path, which routes to the root. That served a
        // request no conforming server would accept, AS a different request than the one sent, which is a desync a front
        // end's access-control rule cannot see. A request line that does not parse is refused (RFC 9112 section 3).
        // Find first space (after method)
        val sp1 = indexOfByte(rawBuf, start, end, ' ')
        if sp1 == -1 then invalid = true
        else
            // Parse method via byte-level comparison (zero-alloc). matchMethod is case-sensitive, which RFC 9110
            // section 9.1 requires: "get" is not the method "GET".
            val ordinal = matchMethod(rawBuf, start, sp1 - start)
            if ordinal == -1 then invalid = true
            else
                builder.setMethod(ordinal)

                // RFC 7230: exactly one SP between tokens. Reject multiple spaces.
                if sp1 + 1 < end && rawBuf(sp1 + 1) == ' ' then
                    invalid = true

                // Find second space (before HTTP version)
                val sp2 = indexOfByte(rawBuf, sp1 + 1, end, ' ')
                if sp2 == -1 then invalid = true
                else
                    // Reject if there's a space immediately after sp2 (triple+ space)
                    if sp2 + 1 < end && rawBuf(sp2 + 1) == ' ' then
                        invalid = true
                    // The URI is between sp1+1 and sp2
                    val uriStart = sp1 + 1
                    val uriEnd   = sp2
                    // An empty target is not a request-target (RFC 9112 section 3.2). Two adjacent spaces produce it.
                    if uriEnd <= uriStart then
                        invalid = true

                    // The version token must be HTTP/1.0 or HTTP/1.1. Anything else is not a request line this server
                    // parses, and accepting it silently (as "default to 1.1") is how an HTTP/0.9 line or a bogus
                    // version was served rather than refused. keep-alive defaults on for 1.1, off for 1.0.
                    val versionStart = sp2 + 1
                    val versionLen   = end - versionStart
                    if versionLen != 8 || !startsWith(
                            rawBuf,
                            versionStart,
                            "HTTP/1."
                        ) || rawBuf(versionStart + 7) != '0' && rawBuf(versionStart + 7) != '1'
                    then
                        invalid = true
                    else
                        builder.setKeepAlive(rawBuf(versionStart + 7) == '1')
                    end if

                    // Split a path range into path and query and store them. Segments first, then the path stored ONCE:
                    // either rebuilt from the resolved segments or copied from the request line. Storing it up front and
                    // rewriting it afterwards left a third copy of the path in the raw buffer, and the packed layout
                    // addresses that buffer with 16-bit offsets, so a long enough path pushed the header offsets past
                    // what they can represent.
                    def storePathAndQuery(pStart: Int, pEnd: Int): Unit =
                        val qMark = indexOfByte(rawBuf, pStart, pEnd, '?')
                        if qMark == -1 then
                            if parsePathSegments(rawBuf, pStart, pEnd) then builder.setPathFromSegments()
                            else builder.setPath(rawBuf, pStart, pEnd - pStart)
                        else
                            if parsePathSegments(rawBuf, pStart, qMark) then builder.setPathFromSegments()
                            else builder.setPath(rawBuf, pStart, qMark - pStart)
                            if qMark + 1 < pEnd then
                                builder.setQuery(rawBuf, qMark + 1, pEnd - qMark - 1)
                        end if
                    end storePathAndQuery

                    // The request target is usually origin-form ("/path"), taken on a single byte compare. Absolute-form
                    // ("scheme://authority/path", RFC 9112 section 3.2.2) and asterisk-form ("*") take the slower
                    // branches. Feeding an absolute-form target to the path splitter mangled it to "/scheme:/authority/
                    // path", so a front end applying an ACL to the real path and this server routed on different paths
                    // (Jetty authority/host family, Netty CVE-2026-59900).
                    if rawBuf(uriStart) == '/' then
                        storePathAndQuery(uriStart, uriEnd)
                    else if uriEnd - uriStart == 1 && rawBuf(uriStart) == '*' then
                        // Asterisk-form (OPTIONS *): an empty path, not fed to the path splitter which would mangle it.
                        builder.setPath(rawBuf, uriStart, 0)
                    else
                        // Not origin- or asterisk-form. If the target carries a scheme ("://") it is absolute-form:
                        // skip the authority to the first '/' or '?'. The authority (with any userinfo, port, or IPv6
                        // host) is skipped, not parsed, so it cannot disturb the path; kyo continues to route on Host.
                        // A target with no "://" is not absolute-form; it is stored as a path unchanged, preserving the
                        // prior lenient handling of a target without a leading slash.
                        @tailrec def findSchemeSep(i: Int): Int =
                            if i > uriEnd - 3 then -1
                            else if rawBuf(i) == ':' && rawBuf(i + 1) == '/' && rawBuf(i + 2) == '/' then i
                            else findSchemeSep(i + 1)
                        val schemeSep = findSchemeSep(uriStart)
                        if schemeSep == -1 then storePathAndQuery(uriStart, uriEnd)
                        else
                            @tailrec def findPathStart(i: Int): Int =
                                if i >= uriEnd then uriEnd
                                else if rawBuf(i) == '/' || rawBuf(i) == '?' then i
                                else findPathStart(i + 1)
                            val pathStart = findPathStart(schemeSep + 3)
                            if pathStart < uriEnd && rawBuf(pathStart) == '/' then
                                storePathAndQuery(pathStart, uriEnd)
                            else
                                // No path segment (bare authority "http://host", or a query with an empty path
                                // "http://host?q"). The effective path is "/"; reuse the '/' from "://" as its source.
                                builder.setPath(rawBuf, schemeSep + 1, 1)
                                if pathStart < uriEnd && rawBuf(pathStart) == '?' && pathStart + 1 < uriEnd then
                                    builder.setQuery(rawBuf, pathStart + 1, uriEnd - pathStart - 1)
                            end if
                        end if
                    end if
                end if
            end if
        end if
    end parseRequestLine

    /** Whether `rawBuf` at `off` begins with the ASCII bytes of `prefix`, without reading past `off + prefix.length`. */
    private def startsWith(rawBuf: Array[Byte], off: Int, prefix: String): Boolean =
        @tailrec def loop(i: Int): Boolean =
            if i >= prefix.length then true
            else if rawBuf(off + i) != prefix.charAt(i).toByte then false
            else loop(i + 1)
        loop(0)
    end startsWith

    /** Parses path segments from /seg1/seg2/seg3, validating each and resolving dot segments as it goes.
      *
      * Splitting happens on the literal byte '/', so an ENCODED separator does not split. That is the whole of the traversal problem: a
      * consumer that decodes a segment afterwards turns "%2F" back into a separator and recovers structure the splitter never agreed to,
      * which is how "..%2F..%2Fetc%2Fpasswd" arrives as one segment and leaves as four. So a segment is classified here, on the raw bytes
      * and before routing, and a segment whose decoded form would carry a separator is refused rather than decoded later.
      *
      * Dot segments are resolved here too (RFC 3986 section 5.2.4) rather than left for a handler, because a handler cannot undo them: by
      * the time it holds a capture the structure is already lost. Resolving before routing also means the route that matches is the route
      * for the path that was actually addressed.
      */
    private def parsePathSegments(rawBuf: Array[Byte], start: Int, end: Int): Boolean =
        val initialI = if start < end && rawBuf(start) == '/' then start + 1 else start
        // Whether the segment list no longer reproduces the raw path, which is what tells the caller the stored path
        // has to be rewritten. That is broader than "a dot segment was resolved": an EMPTY segment is dropped too, so
        // "/a//b" routes on [a, b] and "//" routes to the root, and RFC 3986 makes those different paths from what was
        // sent. Reporting only dot resolution left exactly that spelling routing on one path and reporting another.
        // Kept local to this call rather than made parser state: it describes one path, and a field would survive into
        // the next request on a keep-alive connection.
        var resolved = false
        @tailrec def loop(i: Int, segStart: Int): Unit =
            if i <= end then
                if i == end || rawBuf(i) == '/' then
                    // An empty segment contributes nothing but does mean the stored path and the segments differ.
                    if i == segStart && i < end then resolved = true
                    if i > segStart then
                        classifyPathSegment(rawBuf, segStart, i - segStart) match
                            case Http1Parser.SegInvalid => invalid = true
                            // "." addresses the segment it sits in, so it contributes nothing.
                            case Http1Parser.SegDot => resolved = true
                            case Http1Parser.SegDotDot =>
                                resolved = true
                                builder.removeLastPathSegment()
                            case _ => builder.addPathSegment(rawBuf, segStart, i - segStart)
                    end if
                    loop(i + 1, i + 1)
                else
                    loop(i + 1, segStart)
            end if
        end loop
        loop(initialI, initialI)
        resolved
    end parsePathSegments

    /** Classifies one raw path segment, decoding percent-escapes as it scans without allocating.
      *
      * Answers [[Http1Parser.SegInvalid]] for a segment that must not be accepted at all: a malformed escape (RFC 3986 section 2.1 requires
      * two hex digits, and leaving it to a decoder later means an exception from a code path with no handler), an escape decoding to '/'
      * (forged structure), or a NUL byte in either form (it truncates a path in any C-facing consumer). Otherwise it reports whether the
      * segment's DECODED form is "." or "..", so that "%2e%2e" is resolved exactly like "..".
      */
    private def classifyPathSegment(buf: Array[Byte], start: Int, len: Int): Int =
        val end = start + len
        // `dots` counts decoded '.' bytes and `decoded` counts all decoded bytes, so the two are equal only when the whole segment is dots.
        @tailrec def loop(i: Int, decoded: Int, dots: Int): Int =
            if i >= end then
                if decoded == 1 && dots == 1 then Http1Parser.SegDot
                else if decoded == 2 && dots == 2 then Http1Parser.SegDotDot
                else Http1Parser.SegNormal
            else
                val b = buf(i) & 0xff
                if b == '%' then
                    if i + 2 >= end then Http1Parser.SegInvalid
                    else
                        val hi = hexVal(buf(i + 1))
                        val lo = hexVal(buf(i + 2))
                        if hi < 0 || lo < 0 then Http1Parser.SegInvalid
                        else
                            // An encoded separator is NOT refused here. Inside a single segment it is the legitimate way to say
                            // "a slash that belongs to this value rather than dividing it", which is how a capture carries a
                            // value like "hello/world", and the client encodes a named capture exactly that way.
                            //
                            // It therefore SURVIVES into the capture, on both capture kinds, decoded. Nothing downstream strips
                            // or re-encodes it: a capture is request-supplied data, not a safe path fragment, and a handler
                            // resolving one against a directory must validate it as it would any other untrusted string.
                            val d = (hi << 4) | lo
                            if d == 0 then Http1Parser.SegInvalid
                            else loop(i + 3, decoded + 1, if d == '.' then dots + 1 else dots)
                        end if
                else if b == 0 then Http1Parser.SegInvalid
                else loop(i + 1, decoded + 1, if b == '.' then dots + 1 else dots)
                end if
        loop(start, 0, 0)
    end classifyPathSegment

    /** Numeric value of a hex digit byte, or -1 when it is not one. */
    private def hexVal(b: Byte): Int =
        val c = b & 0xff
        if c >= '0' && c <= '9' then c - '0'
        else if c >= 'a' && c <= 'f' then c - 'a' + 10
        else if c >= 'A' && c <= 'F' then c - 'A' + 10
        else -1
        end if
    end hexVal

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

                        // RFC 9110 section 5.1: a field name is a token. This subsumes narrower per-character checks:
                        // SP and HTAB are not tchars, so it rejects whitespace before the colon (RFC 7230 section 3.2.4,
                        // CVE-2019-16276) wherever it sits rather than only immediately before it; NUL is not a tchar
                        // either; and an empty name is not a token, since token = 1*tchar.
                        if !isToken(rawBuf, nameStart, nameLen) then
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
        if HeaderTokens.nameEquals(nameSrc, nameOff, nameLen, "Content-Length") then
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
        else if HeaderTokens.nameEquals(nameSrc, nameOff, nameLen, "Transfer-Encoding") then
            // RFC 9110 section 5.3 lets a recipient combine repeated field lines into one comma-separated list, so a
            // second Transfer-Encoding line means the first line's coding was not the final one, which RFC 9112
            // section 6.1 forbids (chunked must be final and must not be applied twice). Honoring whichever line came
            // first would frame the message differently from any intermediary that combined them. Duplicate
            // Content-Length is already refused above; this is the same rule for the same reason.
            if hasTransferEncoding then
                invalid = true
            hasTransferEncoding = true
            if hasContentLength then
                invalid = true
            // RFC 9112 section 6.3 item 6: a request whose final transfer coding is not chunked has no determinable
            // body length and must be rejected. Treating it as bodyless instead leaves the body in the buffer, where
            // the next parse reads attacker-controlled bytes as a request line.
            if HeaderTokens.isSoleChunkedCoding(valSrc, valOff, valLen) then
                builder.setChunked(true)
            else
                invalid = true
            end if
        else if HeaderTokens.nameEquals(nameSrc, nameOff, nameLen, "Connection") then
            // Connection is a list (RFC 9110 section 7.6.1), so each token is matched as a whole list element: a value
            // of "no-upgrade" names the token "no-upgrade" and must not be read as carrying "upgrade".
            if HeaderTokens.listContainsToken(valSrc, valOff, valLen, "close") then
                builder.setKeepAlive(false)
            else if HeaderTokens.listContainsToken(valSrc, valOff, valLen, "keep-alive") then
                builder.setKeepAlive(true)
            end if
            if HeaderTokens.listContainsToken(valSrc, valOff, valLen, "upgrade") then
                hasConnectionUpgrade = true
        else if HeaderTokens.nameEquals(nameSrc, nameOff, nameLen, "Expect") then
            // Expect is a list (RFC 9110 section 10.1.1), so "100-continue, foo" still asks for the interim response.
            if HeaderTokens.listContainsToken(valSrc, valOff, valLen, "100-continue") then
                builder.setExpectContinue(true)
        else if HeaderTokens.nameEquals(nameSrc, nameOff, nameLen, "Host") then
            hostCount += 1
            if valLen == 0 then
                builder.setEmptyHost(true)
        else if HeaderTokens.nameEquals(nameSrc, nameOff, nameLen, "Upgrade") then
            // Upgrade is a list of protocols in preference order (RFC 9110 section 7.8), so "websocket, h2c" offers
            // websocket. Matching the whole value would refuse every client that names a fallback.
            if HeaderTokens.listContainsToken(valSrc, valOff, valLen, "websocket") then
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

    // Field name and value comparison lives in HeaderTokens, which takes a length and compares it. The local helpers
    // that used to sit here took no length and so compared a prefix, which is how "chunkedfoo" matched "chunked"; they
    // are deliberately not kept alongside the correct ones, since a prefix comparison reachable by accident at a
    // framing decision is the defect itself.

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

    /** Whether buf[off..off+len) is an RFC 9110 section 5.6.2 `token`: `1*tchar`, so a zero length is not one. */
    private def isToken(buf: Array[Byte], off: Int, len: Int): Boolean =
        @tailrec def loop(i: Int): Boolean =
            if i >= len then true
            else if HttpHeaders.isTokenChar((buf(off + i) & 0xff).toChar) then loop(i + 1)
            else false
        len > 0 && loop(0)
    end isToken

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

    /** Outcomes of classifying a raw path segment; see `Http1Parser.classifyPathSegment`. */
    private[http1] val SegInvalid = -1
    private[http1] val SegNormal  = 0
    private[http1] val SegDot     = 1
    private[http1] val SegDotDot  = 2
end Http1Parser
