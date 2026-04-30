package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.codec.*
import kyo.internal.http1.*
import kyo.internal.util.*

/** Security tests for kyo-http's HTTP/1.1 parser based on real CVEs from other HTTP libraries.
  *
  * Tests that FAIL expose real vulnerabilities -- that is the desired outcome. These tests must NOT be weakened to pass.
  */
class HttpSecurityTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    import AllowUnsafe.embrace.danger

    // ---- Helpers ----

    /** Parse a raw HTTP request string. Returns (parsedRequest, closedCalled).
      *   - parsedRequest is non-null if the parser produced a request
      *   - closedCalled is true if the parser rejected the input via onClosed
      */
    private def parseRaw(rawRequest: String, maxHeaderSize: Int = 65536): (ParsedRequest, Boolean) =
        val channel = Channel.Unsafe.init[Span[Byte]](16)
        val bytes   = rawRequest.getBytes(StandardCharsets.ISO_8859_1) // ISO-8859-1 to preserve all byte values
        discard(channel.offer(Span.fromUnsafe(bytes)))

        val builder               = new ParsedRequestBuilder
        var result: ParsedRequest = null.asInstanceOf[ParsedRequest]
        var closedCalled: Boolean = false
        val parser = new Http1Parser(
            channel,
            builder,
            maxHeaderSize,
            onRequestParsed = (req, _) => result = req,
            onClosed = () => closedCalled = true
        )
        parser.start()
        (result, closedCalled)
    end parseRaw

    /** Assert that the parser rejects the input: either no request produced, or onClosed called. */
    private def assertRejected(rawRequest: String, context: String = ""): Unit =
        val (req, closed) = parseRaw(rawRequest)
        val ctx           = if context.nonEmpty then s" ($context)" else ""
        discard(assert(
            req == null || closed,
            s"Parser should have rejected malicious input$ctx but produced a request: " +
                s"method=${if req != null then req.method else "N/A"}, " +
                s"path=${if req != null then req.pathAsString else "N/A"}, " +
                s"closed=$closed"
        ))
    end assertRejected

    /** Feed raw bytes to ChunkedBodyDecoder via the unsafe callback API. Returns (decodedBytes, completed) where completed=true means the
      * decoder finished processing the terminal chunk.
      */
    private def decodeChunked(rawChunkedBody: String): (Array[Byte], Boolean) =
        val channel = Channel.Unsafe.init[Span[Byte]](16)
        val bytes   = rawChunkedBody.getBytes(StandardCharsets.ISO_8859_1)
        val initial = Span.fromUnsafe(bytes)

        var resultBytes: Array[Byte] = Array.emptyByteArray
        var completed                = false
        var failed                   = false

        ChunkedBodyDecoder.readBufferedUnsafe(channel, initial) { result =>
            result match
                case Result.Success(span) =>
                    resultBytes = span.toArray
                    completed = true
                case Result.Failure(_) =>
                    failed = true
                case Result.Panic(_) =>
                    failed = true
        }

        (resultBytes, completed)
    end decodeChunked

    // ====================================================================================
    // Group 1: Request Smuggling -- Multiple Content-Length (CVE-2019-20445, CVE-2026-23941)
    // ====================================================================================

    "Request Smuggling: Multiple Content-Length" - {

        "CVE-2019-20445: reject duplicate Content-Length with different values" in {
            // An attacker sends two Content-Length headers with different values.
            // A compliant parser MUST reject this to prevent request smuggling.
            // If the parser silently uses one value, front-end and back-end proxies
            // may disagree on where the request body ends.
            val (req, closed) = parseRaw(
                "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\nContent-Length: 100\r\n\r\nhello"
            )
            // The parser should either reject (null/closed) or at minimum NOT silently pick one value.
            // If it parsed successfully, check that it did NOT just use one of the values.
            if req != null && !closed then
                // If the parser accepted both headers, it should have flagged an error somehow.
                // Currently the parser silently overwrites with the last value -- this is a vulnerability.
                fail(
                    s"VULNERABILITY: Parser accepted duplicate Content-Length headers without rejection. " +
                        s"Content-Length=${req.contentLength}. This enables request smuggling attacks (CVE-2019-20445)."
                )
            end if
            succeed
        }

        "reject duplicate Content-Length with same values (still ambiguous per RFC)" in {
            // Even identical duplicate Content-Length should be rejected per strict parsing.
            // RFC 9110 section 8.6: "If the message is received without Transfer-Encoding and
            // with either multiple Content-Length header field values or a single Content-Length
            // header field value with a comma-separated list, and any of the values differs from
            // the others, then the message framing is indeterminate."
            val (req, closed) = parseRaw(
                "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\nContent-Length: 5\r\n\r\nhello"
            )
            if req != null && !closed then
                fail(
                    "VULNERABILITY: Parser accepted duplicate Content-Length headers (same value). " +
                        "Strict parsers should reject any duplicate Content-Length."
                )
            end if
            succeed
        }
    }

    // ====================================================================================
    // Group 2: Request Smuggling -- CL + TE Conflict
    // ====================================================================================

    "Request Smuggling: Content-Length + Transfer-Encoding conflict" - {

        "reject request with both Content-Length and Transfer-Encoding: chunked" in {
            // RFC 9110 section 8.6: "If a message is received with both a Transfer-Encoding and a
            // Content-Length header field, the Transfer-Encoding overrides the Content-Length.
            // Such a message might indicate an attempt to perform request smuggling or response
            // splitting and ought to be handled as an error."
            // A server that is NOT acting as a proxy SHOULD reject this.
            val (req, closed) = parseRaw(
                "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 100\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n"
            )
            if req != null && !closed then
                // If accepted, at minimum TE must win. But the RFC says servers SHOULD reject.
                if req.isChunked && req.contentLength >= 0 then
                    fail(
                        "VULNERABILITY: Parser accepted request with BOTH Content-Length and Transfer-Encoding " +
                            s"without rejecting. CL=${req.contentLength}, chunked=${req.isChunked}. " +
                            "This enables CL.TE request smuggling attacks."
                    )
                end if
            end if
            succeed
        }
    }

    // ====================================================================================
    // Group 3: Header Injection -- Space Before Colon (CVE-2019-16276, Netty #9571)
    // ====================================================================================

    "Header Injection: Space before colon" - {

        "CVE-2019-16276: reject header with space before colon" in {
            // RFC 7230 section 3.2.4: "No whitespace is allowed between the header field-name and colon."
            // Go's net/http CVE-2019-16276: spaces before colon allowed header injection.
            // The space makes "Host " a different header name than "Host", causing
            // request routing confusion between proxies.
            val (req, closed) = parseRaw(
                "GET / HTTP/1.1\r\nHost : example.com\r\n\r\n"
            )
            if req != null && !closed then
                // Check if the parser accepted "Host " as a header
                var foundHostWithSpace = false
                var i                  = 0
                while i < req.headerCount do
                    if req.headerName(i) == "Host " || req.headerName(i) == "Host" then
                        foundHostWithSpace = true
                    i += 1
                end while
                if foundHostWithSpace then
                    fail(
                        "VULNERABILITY: Parser accepted header with space before colon ('Host : value'). " +
                            "This violates RFC 7230 section 3.2.4 and enables header injection (CVE-2019-16276)."
                    )
                end if
            end if
            succeed
        }

        "reject Transfer-Encoding with space before colon" in {
            // This is the most dangerous variant: if "Transfer-Encoding " is treated as
            // a different header than "Transfer-Encoding", a proxy might not see the TE header
            // while the backend does, enabling request smuggling.
            val (req, closed) = parseRaw(
                "POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding : chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n"
            )
            if req != null && !closed then
                // The parser must NOT enter chunked mode from a space-before-colon TE header
                if req.isChunked then
                    fail(
                        "VULNERABILITY: Parser entered chunked mode from 'Transfer-Encoding : chunked' " +
                            "(space before colon). This violates RFC 7230 and enables TE smuggling."
                    )
                end if
            end if
            succeed
        }
    }

    // ====================================================================================
    // Group 4: Header Injection -- Obs-fold (CVE-2022-32213, CVE-2022-32215)
    // ====================================================================================

    "Header Injection: Obs-fold continuation lines" - {

        "CVE-2022-32213: reject obs-fold in Transfer-Encoding header" in {
            // Node.js CVE-2022-32213: obs-fold (line folding) in headers was used to
            // smuggle Transfer-Encoding past proxies.
            // RFC 7230 section 3.2.4: "A server that receives an obs-fold in a request message
            // ... MUST ... reject the message"
            val (req, closed) = parseRaw(
                "POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding:\r\n chunked\r\nContent-Length: 5\r\n\r\nhello"
            )
            if req != null && !closed then
                // If the parser accepted this and entered chunked mode, it's vulnerable
                if req.isChunked then
                    fail(
                        "VULNERABILITY: Parser accepted obs-fold in Transfer-Encoding header and entered chunked mode. " +
                            "This enables request smuggling (CVE-2022-32213)."
                    )
                end if
                // Even if not chunked, accepting obs-fold at all is a vulnerability
                // because the continuation line " chunked" might be interpreted as a
                // separate header by some implementations
            end if
            succeed
        }

        "reject obs-fold continuation line (space after CRLF)" in {
            // General obs-fold: a line starting with space or tab after CRLF is a continuation
            // of the previous header. This MUST be rejected per RFC 7230.
            val (req, closed) = parseRaw(
                "GET / HTTP/1.1\r\nHost: h\r\nX-Foo: bar\r\n baz\r\n\r\n"
            )
            if req != null && !closed then
                // Check if " baz" was folded into X-Foo or treated as a separate header
                var xFooValue = ""
                var i         = 0
                while i < req.headerCount do
                    if req.headerName(i) == "X-Foo" then
                        xFooValue = req.headerValue(i)
                    i += 1
                end while
                // If the parser silently accepted obs-fold (either folding or as separate header),
                // that's a vulnerability
                // Note: the parser treats " baz" as a header line. If it finds no colon,
                // it may just skip it. But accepting obs-fold at all is the issue.
                fail(
                    "VULNERABILITY: Parser accepted request with obs-fold continuation line (' baz' after CRLF). " +
                        "Obs-fold MUST be rejected per RFC 7230 section 3.2.4 (CVE-2022-32215)."
                )
            end if
            succeed
        }
    }

    // ====================================================================================
    // Group 5: Bare CR/LF in Headers (CVE-2022-35256)
    // ====================================================================================

    "Header Injection: Bare CR/LF" - {

        "CVE-2022-35256: reject header terminated by bare CR without LF" in {
            // Node.js CVE-2022-35256: bare CR (without LF) could be used to inject headers.
            // The parser should only accept CRLF (\r\n) as line terminators.
            // Input: "Host: h\rX-Injected: evil" -- bare CR after Host value
            val (req, closed) = parseRaw(
                "GET / HTTP/1.1\r\nHost: h\rX-Injected: evil\r\n\r\n"
            )
            if req != null && !closed then
                // Check if X-Injected was parsed as a separate header
                var foundInjected = false
                var i             = 0
                while i < req.headerCount do
                    if req.headerName(i) == "X-Injected" then
                        foundInjected = true
                    i += 1
                end while
                if foundInjected then
                    fail(
                        "VULNERABILITY: Parser accepted bare CR as header line terminator, allowing header injection. " +
                            "X-Injected was parsed as a separate header (CVE-2022-35256)."
                    )
                else
                    // Even if X-Injected wasn't found as separate header, the bare CR should cause rejection
                    fail(
                        "VULNERABILITY: Parser accepted request with bare CR in header without rejecting. " +
                            "Only CRLF should be accepted as line terminators (CVE-2022-35256)."
                    )
                end if
            end if
            succeed
        }

        "reject bare LF as header terminator" in {
            // Bare LF (\n without \r) should not be accepted as a valid header line terminator.
            // Using bare LF can cause disagreement between parsers about header boundaries.
            val (req, closed) = parseRaw(
                "GET / HTTP/1.1\nHost: h\n\n"
            )
            if req != null && !closed then
                fail(
                    "VULNERABILITY: Parser accepted bare LF as header terminator. " +
                        "Only CRLF (\\r\\n) is valid per RFC 7230."
                )
            end if
            succeed
        }
    }

    // ====================================================================================
    // Group 6: Content-Length Parsing (CVE-2018-7159)
    // ====================================================================================

    "Content-Length Parsing Attacks" - {

        "CVE-2018-7159: reject Content-Length with embedded spaces" in {
            // Node.js CVE-2018-7159: Content-Length values like "1 0" were parsed as 10,
            // ignoring the space. This enables body length confusion.
            val (req, closed) = parseRaw(
                "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 1 0\r\n\r\n0123456789"
            )
            if req != null && !closed then
                if req.contentLength == 10 then
                    fail(
                        "VULNERABILITY: Parser interpreted 'Content-Length: 1 0' as 10, ignoring embedded space. " +
                            "This enables body length confusion (CVE-2018-7159)."
                    )
                else if req.contentLength > 0 then
                    fail(
                        s"VULNERABILITY: Parser accepted 'Content-Length: 1 0' as ${req.contentLength}. " +
                            "Content-Length with embedded spaces must be rejected."
                    )
                end if
                // contentLength == -1 means it was rejected (parseContentLength returned -1), which is acceptable
            end if
            succeed
        }

        "reject negative Content-Length" in {
            val (req, closed) = parseRaw(
                "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: -1\r\n\r\n"
            )
            if req != null && !closed then
                if req.contentLength != -1 then
                    // -1 is the default "absent" value, so if it's -1, the parser effectively rejected it
                    fail(
                        s"VULNERABILITY: Parser accepted negative Content-Length: ${req.contentLength}. " +
                            "Negative Content-Length must be rejected."
                    )
                end if
            end if
            succeed
        }

        "reject Content-Length with leading plus sign" in {
            val (req, closed) = parseRaw(
                "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: +10\r\n\r\n0123456789"
            )
            if req != null && !closed then
                if req.contentLength == 10 then
                    fail(
                        "VULNERABILITY: Parser accepted 'Content-Length: +10' as 10. " +
                            "Leading plus sign is not valid in Content-Length per RFC 9110."
                    )
                else if req.contentLength > 0 then
                    fail(
                        s"VULNERABILITY: Parser accepted 'Content-Length: +10' as ${req.contentLength}. " +
                            "Leading plus sign must be rejected."
                    )
                end if
            end if
            succeed
        }

        "reject Content-Length with hex value" in {
            val (req, closed) = parseRaw(
                "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 0xa\r\n\r\n0123456789"
            )
            if req != null && !closed then
                if req.contentLength == 10 then
                    fail(
                        "VULNERABILITY: Parser accepted hex Content-Length '0xa' as 10. " +
                            "Content-Length must be decimal only."
                    )
                else if req.contentLength > 0 then
                    fail(
                        s"VULNERABILITY: Parser accepted hex Content-Length '0xa' as ${req.contentLength}."
                    )
                end if
            end if
            succeed
        }

        "reject Content-Length exceeding Int.MaxValue" in {
            // The parser uses Int for content-length. A value exceeding Int.MaxValue
            // must not silently overflow/wrap to a small positive number.
            val (req, closed) = parseRaw(
                "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 99999999999999999999\r\n\r\n"
            )
            if req != null && !closed then
                if req.contentLength >= 0 then
                    fail(
                        s"VULNERABILITY: Parser accepted enormous Content-Length as ${req.contentLength}. " +
                            "Integer overflow in Content-Length parsing."
                    )
                end if
            end if
            succeed
        }
    }

    // ====================================================================================
    // Group 7: Chunked Encoding Attacks (CVE-2013-2028, CVE-2025-22871, Hyper GHSA)
    // ====================================================================================

    "Chunked Encoding Attacks" - {

        "CVE-2013-2028: reject chunk size that overflows signed integer" in {
            // nginx CVE-2013-2028: chunk size 0x7fffffff (or larger) overflows when cast to signed int.
            // The parser should reject chunk sizes that would overflow Int range.
            val (decoded, done) = decodeChunked("7fffffff\r\n")
            // With a chunk size of 2^31-1, the decoder should need more data or reject.
            // If it claims "done" with that huge size, something is wrong.
            if done then
                fail(
                    "VULNERABILITY: Chunked decoder accepted 0x7fffffff chunk size and reported Done " +
                        "without receiving the actual data. This may indicate integer overflow (CVE-2013-2028)."
                )
            end if
            // Acceptable: NeedMore (waiting for 2GB of data) -- not ideal but not a crash
            succeed
        }

        "Hyper GHSA: reject chunk size exceeding 64-bit range" in {
            // Hyper GHSA: chunk size "f0000000000000003" exceeds 64-bit range.
            // The parser should detect the overflow rather than silently truncating.
            val (decoded, done) = decodeChunked("f0000000000000003\r\ndata\r\n0\r\n\r\n")
            // If the decoder truncated the chunk size and processed only a few bytes, that's a vulnerability
            if done && decoded.length > 0 && decoded.length < 100 then
                fail(
                    s"VULNERABILITY: Chunked decoder silently truncated enormous chunk size " +
                        s"'f0000000000000003' and decoded ${decoded.length} bytes. " +
                        "Integer overflow in chunk size parsing (Hyper GHSA)."
                )
            end if
            succeed
        }

        "CVE-2025-22871: reject bare LF as chunk line terminator" in {
            // Go CVE-2025-22871: bare LF (without CR) accepted as chunk-size line terminator.
            // This can cause disagreement between proxy and backend about chunk boundaries.
            val (decoded, done) = decodeChunked("5\nhello\r\n0\r\n\r\n")
            if done then
                val result = new String(decoded, StandardCharsets.US_ASCII)
                if result == "hello" then
                    fail(
                        "VULNERABILITY: Chunked decoder accepted bare LF as chunk-size line terminator. " +
                            "Only CRLF is valid per RFC 7230 (CVE-2025-22871)."
                    )
                end if
            end if
            succeed
        }

        "reject chunk with missing CRLF after data" in {
            // After each chunk's data, there must be a CRLF before the next chunk-size line.
            // If the CRLF is missing, the parser should detect the framing error.
            val (decoded, done) = decodeChunked("5\r\nhello5\r\nworld\r\n0\r\n\r\n")
            if done then
                val result = new String(decoded, StandardCharsets.US_ASCII)
                // If the decoder accepted this and decoded both chunks, the missing CRLF was ignored
                if result.contains("hello") && result.contains("world") then
                    fail(
                        "VULNERABILITY: Chunked decoder accepted chunk data without trailing CRLF. " +
                            "RFC 7230 requires CRLF after each chunk data section."
                    )
                end if
            end if
            succeed
        }

        "reject negative chunk size" in {
            // Chunk sizes are hex and unsigned. A leading '-' is not valid hex.
            val (decoded, done) = decodeChunked("-1\r\ndata\r\n0\r\n\r\n")
            if done && decoded.length > 0 then
                fail(
                    "VULNERABILITY: Chunked decoder accepted negative chunk size '-1'. " +
                        "Chunk sizes must be non-negative hex values."
                )
            end if
            succeed
        }
    }

    // ====================================================================================
    // Group 8: Request Line Attacks (CVE-2016-8743)
    // ====================================================================================

    "Request Line Attacks" - {

        "CVE-2016-8743: reject tab delimiter in request line" in {
            // Apache CVE-2016-8743: tabs accepted as delimiters in request line.
            // RFC 7230 section 3.1.1: request-line = method SP request-target SP HTTP-version
            // Only SP (0x20) is valid, not HT (0x09).
            val (req, closed) = parseRaw(
                "GET\t/\tHTTP/1.1\r\nHost: h\r\n\r\n"
            )
            if req != null && !closed then
                if req.method == HttpMethod.GET && req.pathAsString == "/" then
                    fail(
                        "VULNERABILITY: Parser accepted tab character as request line delimiter. " +
                            "Only SP (0x20) is valid per RFC 7230 (CVE-2016-8743)."
                    )
                end if
            end if
            succeed
        }

        "reject multiple spaces in request line" in {
            // RFC 7230: only a single SP between method, target, and version.
            // Multiple spaces could cause URI parsing discrepancies between proxies.
            val (req, closed) = parseRaw(
                "GET  /  HTTP/1.1\r\nHost: h\r\n\r\n"
            )
            if req != null && !closed then
                if req.method == HttpMethod.GET then
                    // Check if the path was parsed correctly despite double spaces
                    val path = req.pathAsString
                    if path == "/" then
                        fail(
                            "VULNERABILITY: Parser accepted multiple spaces in request line and parsed path as '/'. " +
                                "Only single SP delimiter is valid per RFC 7230."
                        )
                    else if path == "" || path.startsWith(" ") then
                        fail(
                            s"VULNERABILITY: Parser accepted multiple spaces in request line (path='$path'). " +
                                "Only single SP delimiter is valid per RFC 7230."
                        )
                    end if
                end if
            end if
            succeed
        }
    }

    // ====================================================================================
    // Group 9: Transfer-Encoding Obfuscation (PortSwigger variants)
    // ====================================================================================

    "Transfer-Encoding Obfuscation" - {

        "reject Transfer-Encoding with leading space in header name" in {
            // PortSwigger: " Transfer-Encoding: chunked" -- leading space in header name.
            // This is also an obs-fold if following another header, or simply invalid.
            val (req, closed) = parseRaw(
                "POST / HTTP/1.1\r\nHost: h\r\n Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n"
            )
            if req != null && !closed then
                // Leading space before header name is either obs-fold or invalid
                if req.isChunked then
                    fail(
                        "VULNERABILITY: Parser entered chunked mode from ' Transfer-Encoding: chunked' " +
                            "(leading space in header name). This enables TE obfuscation attacks."
                    )
                end if
            end if
            succeed
        }

        "reject Transfer-Encoding: xchunked" in {
            // PortSwigger variant: "Transfer-Encoding: xchunked" is not a valid encoding.
            // The parser should NOT enter chunked mode for unknown TE values.
            val (req, closed) = parseRaw(
                "POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: xchunked\r\n\r\n"
            )
            if req != null && !closed then
                if req.isChunked then
                    fail(
                        "VULNERABILITY: Parser entered chunked mode from 'Transfer-Encoding: xchunked'. " +
                            "Only exact 'chunked' value should activate chunked decoding."
                    )
                end if
            end if
            succeed
        }

        "reject Transfer-Encoding with tab after colon" in {
            // Tab (0x09) is valid OWS per RFC 7230, so "Transfer-Encoding:\tchunked" SHOULD be
            // recognized as chunked. The key concern is consistency -- if the parser doesn't
            // recognize it, a proxy might also not, creating a smuggling vector.
            val (req, closed) = parseRaw(
                "POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding:\tchunked\r\n\r\n0\r\n\r\n"
            )
            if req != null && !closed then
                // Tab is valid OWS, so chunked should be recognized.
                // However, the parser's skipSpaces only skips 0x20, not 0x09.
                // If it does NOT recognize chunked here, it creates a mismatch with
                // proxies that DO strip tabs, enabling TE smuggling.
                if !req.isChunked then
                    fail(
                        "VULNERABILITY: Parser did NOT recognize 'Transfer-Encoding:\\tchunked' " +
                            "(tab after colon). Tab is valid OWS per RFC 7230. Inconsistent handling " +
                            "creates TE smuggling vectors when proxies strip tabs."
                    )
                end if
            end if
            succeed
        }
    }

    // ====================================================================================
    // Group 10: Null Byte Injection
    // ====================================================================================

    "Null Byte Injection" - {

        "reject null byte in header name" in {
            // Null bytes in header names can cause truncation in C-based backends,
            // leading to header injection.
            val (req, closed) = parseRaw(
                "GET / HTTP/1.1\r\nHost: h\r\nX-Evil\u0000Header: value\r\n\r\n"
            )
            if req != null && !closed then
                // Check if the null byte was accepted in a header name
                var foundNullHeader = false
                var i               = 0
                while i < req.headerCount do
                    val name = req.headerName(i)
                    if name.contains('\u0000') || name == "X-Evil" then
                        foundNullHeader = true
                    i += 1
                end while
                if foundNullHeader then
                    fail(
                        "VULNERABILITY: Parser accepted null byte in header name. " +
                            "Null bytes in headers enable injection attacks via C-string truncation."
                    )
                end if
            end if
            succeed
        }

        "reject null byte in header value" in {
            val (req, closed) = parseRaw(
                "GET / HTTP/1.1\r\nHost: h\r\nX-Header: val\u0000ue\r\n\r\n"
            )
            if req != null && !closed then
                var foundNullValue = false
                var i              = 0
                while i < req.headerCount do
                    val value = req.headerValue(i)
                    if value.contains('\u0000') then
                        foundNullValue = true
                    i += 1
                end while
                if foundNullValue then
                    fail(
                        "VULNERABILITY: Parser accepted null byte in header value. " +
                            "Null bytes in headers enable injection attacks."
                    )
                end if
            end if
            succeed
        }
    }

    // ====================================================================================
    // Group 11: Header Without Colon (CVE-2019-20444)
    // ====================================================================================

    "Header Without Colon" - {

        "CVE-2019-20444: reject header line without colon" in {
            // Netty CVE-2019-20444: header line without colon was accepted.
            // This can cause the header value to be interpreted as part of the
            // previous header or create confusion in downstream processing.
            val (req, closed) = parseRaw(
                "GET / HTTP/1.1\r\nHost: h\r\nNOCOLONHERE\r\n\r\n"
            )
            if req != null && !closed then
                // The parser should have rejected this.
                // If it silently skipped the line, check if the request was accepted
                // with only the Host header -- that's still a vulnerability because
                // the request should have been rejected entirely.
                fail(
                    "VULNERABILITY: Parser accepted request with header line lacking a colon ('NOCOLONHERE'). " +
                        s"headerCount=${req.headerCount}. " +
                        "Header lines without colons must cause request rejection (CVE-2019-20444)."
                )
            end if
            succeed
        }
    }

end HttpSecurityTest
