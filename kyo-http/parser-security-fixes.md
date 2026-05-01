# HTTP/1.1 Parser Security Fixes

## Overview

14 security vulnerabilities in `Http1Parser` and `ChunkedBodyDecoder` allow request smuggling, header injection, and integer overflow attacks. All fixes below are zero-allocation: they use primitive `Boolean`/`Int` flags checked inline during existing parse loops. No new objects, strings, arrays, or exceptions are created per request.

### Design Principles

1. **All new state is `private var` boolean/int fields, reset in `reset()`** -- parser is connection-scoped and reused across keep-alive requests
2. **Validation happens inline during `parseHeaders()` and `parseRequestLine()`** -- no extra passes over the buffer
3. **Invalid requests short-circuit via a `private var invalid: Boolean` flag checked in `parse()` before calling `onRequestParsed`** -- the parser still produces a `ParsedRequest` (to avoid needing a nullable path), but `parse()` calls `onClosed()` instead of `onRequestParsed()` when `invalid` is set
4. **The chunked decoder fixes are in `processReadDataCrlf()`** -- strict CRLF enforcement
5. **`skipSpaces` expanded to also skip tabs** -- RFC 7230 OWS = SP / HTAB

### Rejection Mechanism

The parser currently has no way to reject a request mid-parse -- `packRequest` always returns a `ParsedRequest`. Rather than adding nullable return types or exception throws (which would allocate), we add a single `private var invalid: Boolean` flag. Individual validation checks set `invalid = true` during the existing `parseHeaders`/`parseRequestLine` loops. After `packRequest` returns, `parse()` checks `invalid` and calls `onClosed()` instead of `onRequestParsed()`.

**File:** `Http1Parser.scala`

New fields added alongside existing `hostCount`, `hasUpgradeWebSocket`, `hasConnectionUpgrade`:

```scala
private var invalid             = false
private var hasContentLength    = false
private var hasTransferEncoding = false
```

Reset in `reset()` and at the top of `packRequest()`:

```scala
def reset(): Unit =
    builder.reset()
    hostCount = 0
    hasUpgradeWebSocket = false
    hasConnectionUpgrade = false
    invalid = false
    hasContentLength = false
    hasTransferEncoding = false
```

Short-circuit in `parse()`:

```scala
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
            val cl = request.contentLength
            val bodySpan =
                if cl > 0 && pos > 0 then
                    val bodyLen = math.min(pos, cl)
                    val bodyArr = new Array[Byte](bodyLen)
                    java.lang.System.arraycopy(buf, 0, bodyArr, 0, bodyLen)
                    val bodyRemaining = pos - bodyLen
                    if bodyRemaining > 0 then
                        java.lang.System.arraycopy(buf, bodyLen, buf, 0, bodyRemaining)
                    pos = bodyRemaining
                    Span.fromUnsafe(bodyArr)
                else if request.isChunked && pos > 0 then
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
```

---

## Fix 1: Duplicate Content-Length headers (CVE-2019-20445)

**File:** `Http1Parser.scala:324` (inside `detectSpecialHeader`)

**Vulnerability:** When multiple `Content-Length` headers appear, the parser silently overwrites `contentLength` with the last value. A front-end proxy may use the first value while the back-end uses the last, enabling request smuggling.

**Fix:** Track whether a `Content-Length` header has already been seen with the `hasContentLength` boolean flag. If a second `Content-Length` is encountered, set `invalid = true`.

Current code:
```scala
if nameLen == 14 && asciiEqualsIgnoreCase(nameSrc, nameOff, "Content-Length") then
    val cl = parseContentLength(valSrc, valOff, valLen)
    builder.setContentLength(cl)
```

Fixed code:
```scala
if nameLen == 14 && asciiEqualsIgnoreCase(nameSrc, nameOff, "Content-Length") then
    if hasContentLength then
        invalid = true
    else
        hasContentLength = true
        val cl = parseContentLength(valSrc, valOff, valLen)
        builder.setContentLength(cl)
```

**Performance:** Zero alloc -- single boolean check + boolean write, inline in existing `detectSpecialHeader` branch.

---

## Fix 2: Duplicate Content-Length with same value accepted

**File:** `Http1Parser.scala:324` (inside `detectSpecialHeader`)

**Vulnerability:** Even when both `Content-Length` values are identical (e.g., `Content-Length: 5` appearing twice), the parser accepts the request. RFC 9110 section 8.6 requires rejection of any duplicate `Content-Length` headers regardless of value.

**Fix:** This is handled by the same fix as Fix 1. The `hasContentLength` flag triggers `invalid = true` on any second occurrence, regardless of whether the values match. No value comparison is needed.

Current code:
```scala
if nameLen == 14 && asciiEqualsIgnoreCase(nameSrc, nameOff, "Content-Length") then
    val cl = parseContentLength(valSrc, valOff, valLen)
    builder.setContentLength(cl)
```

Fixed code:
```scala
if nameLen == 14 && asciiEqualsIgnoreCase(nameSrc, nameOff, "Content-Length") then
    if hasContentLength then
        invalid = true
    else
        hasContentLength = true
        val cl = parseContentLength(valSrc, valOff, valLen)
        builder.setContentLength(cl)
```

**Performance:** Zero alloc -- same boolean check as Fix 1, no additional work.

---

## Fix 3: CL + TE conflict accepted (RFC 9110 section 8.6)

**File:** `Http1Parser.scala:324-329` (inside `detectSpecialHeader`)

**Vulnerability:** The parser accepts requests with both `Content-Length` and `Transfer-Encoding: chunked` headers, setting both fields on the builder. RFC 9110 section 8.6 says a server that is not acting as a proxy SHOULD reject such messages because they indicate a request smuggling attempt.

**Fix:** Track `hasTransferEncoding` alongside `hasContentLength`. After both branches execute (during the same `parseHeaders` loop), cross-check: if CL is seen when TE already exists, or TE is seen when CL already exists, set `invalid = true`.

Current code:
```scala
if nameLen == 14 && asciiEqualsIgnoreCase(nameSrc, nameOff, "Content-Length") then
    val cl = parseContentLength(valSrc, valOff, valLen)
    builder.setContentLength(cl)
else if nameLen == 17 && asciiEqualsIgnoreCase(nameSrc, nameOff, "Transfer-Encoding") then
    if valLen >= 7 && asciiEqualsIgnoreCase(valSrc, valOff, "chunked") then
        builder.setChunked(true)
```

Fixed code:
```scala
if nameLen == 14 && asciiEqualsIgnoreCase(nameSrc, nameOff, "Content-Length") then
    if hasContentLength then
        invalid = true
    else
        hasContentLength = true
        if hasTransferEncoding then
            invalid = true
        val cl = parseContentLength(valSrc, valOff, valLen)
        builder.setContentLength(cl)
else if nameLen == 17 && asciiEqualsIgnoreCase(nameSrc, nameOff, "Transfer-Encoding") then
    hasTransferEncoding = true
    if hasContentLength then
        invalid = true
    if valLen >= 7 && asciiEqualsIgnoreCase(valSrc, valOff, "chunked") then
        builder.setChunked(true)
```

**Performance:** Zero alloc -- two boolean reads and one boolean write per CL/TE header, inline in existing branches.

---

## Fix 4: Space before colon in header name (CVE-2019-16276)

**File:** `Http1Parser.scala:289` (inside `parseHeaders`, after finding colonIdx)

**Vulnerability:** RFC 7230 section 3.2.4 states "No whitespace is allowed between the header field-name and colon." The parser accepts headers like `Host : value` where a space appears before the colon. This allows a proxy to see `Host ` (with space) as a non-standard header while the backend treats it as `Host`, enabling header injection.

**Fix:** After finding the colon position in `parseHeaders`, check if the byte immediately before the colon is a space (0x20) or tab (0x09). If so, set `invalid = true`.

Current code:
```scala
// Find the colon separator
val colonIdx = indexOfByte(rawBuf, lineStart, actualLineEnd, ':')
if colonIdx != -1 then
    val nameStart = lineStart
    val nameLen   = colonIdx - lineStart
```

Fixed code:
```scala
// Find the colon separator
val colonIdx = indexOfByte(rawBuf, lineStart, actualLineEnd, ':')
if colonIdx != -1 then
    val nameStart = lineStart
    val nameLen   = colonIdx - lineStart
    // RFC 7230 section 3.2.4: no whitespace between header name and colon
    if nameLen > 0 && (rawBuf(colonIdx - 1) == ' ' || rawBuf(colonIdx - 1) == '\t') then
        invalid = true
```

**Performance:** Zero alloc -- single byte comparison at an already-computed index, inline in existing header loop iteration.

---

## Fix 5: Obs-fold continuation lines accepted (CVE-2022-32213)

**File:** `Http1Parser.scala:280-307` (inside `parseHeaders`)

**Vulnerability:** RFC 7230 section 3.2.4 requires that a server receiving obs-fold (a continuation line starting with SP or HTAB after CRLF) in a request MUST reject the message. The parser's `parseHeaders` loop iterates line-by-line but does not check whether a line starts with whitespace. A line like `" chunked"` after `Transfer-Encoding:` would be treated as a separate (malformed) header line. While the current parser may not fold it, it still accepts the request, which violates the RFC. More critically, a line starting with SP/HTAB after CRLF is by definition an obs-fold and the entire request must be rejected.

**Fix:** At the beginning of each header line iteration, before searching for the colon, check if the first byte is SP (0x20) or HTAB (0x09). If so, set `invalid = true`.

Current code:
```scala
@tailrec def loop(lineStart: Int): Unit =
    if lineStart < end then
        // Find end of this header line
        val lineEnd       = indexOf2(rawBuf, lineStart, end, '\r', '\n')
        val actualLineEnd = if lineEnd == -1 then end else lineEnd

        if actualLineEnd > lineStart then
            // Find the colon separator
            val colonIdx = indexOfByte(rawBuf, lineStart, actualLineEnd, ':')
```

Fixed code:
```scala
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
```

**Performance:** Zero alloc -- single byte read at `lineStart` (already in cache from the loop index), inline in existing loop.

---

## Fix 6: Bare CR without LF in headers (CVE-2022-35256)

**File:** `Http1Parser.scala:87-89` (in `parse()`, before `indexOf` for `CRLF_CRLF`)

**Vulnerability:** The parser uses `indexOf(buf, pos, CRLF_CRLF)` to find the end of headers, which looks for `\r\n\r\n`. A bare `\r` without `\n` (e.g., `Host: h\rX-Injected: evil\r\n`) gets embedded in the header line text. Then `indexOf2` in `parseHeaders` looks for `\r\n` and may split lines differently than expected. A bare CR can trick the parser into accepting injected headers.

Additionally, bare LF (`\n\n` as header terminator without `\r`) is accepted because `indexOf` for `CRLF_CRLF` would not match, and the parser would just keep reading more bytes. However, if the input uses `\n` consistently (e.g., `GET / HTTP/1.1\nHost: h\n\n`), the `\r\n\r\n` pattern is never found, so the parser asks for more bytes and never produces a request. This means bare-LF-only requests are already rejected for headers. But bare CR within a CRLF-terminated request is the real attack vector.

**Fix:** After finding `headerEnd` via `CRLF_CRLF`, scan the header region `buf[0..headerEnd)` for bare CR bytes (a CR not followed by LF). This is done with a single pass that checks each `\r` to ensure `\r\n` follows. If a bare CR is found, set `invalid = true`.

The scan is added in `packRequest()` right after finding the request line end, before parsing headers. It reuses the same byte range that `parseHeaders` will iterate over.

Current code (in `packRequest`):
```scala
if requestLineEnd == -1 then
    // Malformed: no request line found -- return minimal request
    builder.build()
else
    parseRequestLine(rawBuf, 0, requestLineEnd)
    parseHeaders(rawBuf, requestLineEnd + 2, headerEnd)
```

Fixed code (in `packRequest`):
```scala
if requestLineEnd == -1 then
    // Malformed: no request line found -- return minimal request
    builder.build()
else
    // Scan for bare CR (CR not followed by LF) in entire header region
    if containsBareCr(rawBuf, 0, headerEnd) then
        invalid = true
    parseRequestLine(rawBuf, 0, requestLineEnd)
    parseHeaders(rawBuf, requestLineEnd + 2, headerEnd)
```

New private method (zero-alloc inline scan):

```scala
/** Scans buf[start..end) for any CR byte not immediately followed by LF. */
@tailrec private def containsBareCr(buf: Array[Byte], i: Int, end: Int): Boolean =
    if i >= end then false
    else if buf(i) == '\r' then
        if i + 1 >= end || buf(i + 1) != '\n' then true
        else containsBareCr(buf, i + 2, end) // skip past CRLF
    else containsBareCr(buf, i + 1, end)
```

**Performance:** Zero alloc -- tail-recursive scan over bytes already in the buffer. Runs once per request, O(headerSize). The header bytes are already in L1 cache from the `indexOf(CRLF_CRLF)` scan that just completed. In practice this is ~50-200 bytes for typical requests.

---

## Fix 7: Content-Length integer overflow

**File:** `Http1Parser.scala:353-363` (`parseContentLength`)

**Vulnerability:** The parser accumulates Content-Length digits with `acc * 10 + (b - '0')` using `Int` arithmetic. For a value like `99999999999999999999`, this silently overflows and wraps around to a small or negative number. A negative result would be treated as "absent" (-1), but a small positive result from overflow would cause the parser to read too few body bytes, leaving the rest to be interpreted as the next request (smuggling).

**Fix:** Add an overflow guard: before `acc * 10 + digit`, check if `acc` exceeds `Int.MaxValue / 10` (which is 214748364). If `acc > 214748364`, or if `acc == 214748364` and the digit exceeds 7 (since `Int.MaxValue` is 2147483647), return -1. This uses only int comparisons, no Long promotion.

Current code:
```scala
private def parseContentLength(src: Array[Byte], off: Int, len: Int): Int =
    if len <= 0 then -1
    else
        @tailrec def loop(i: Int, acc: Int): Int =
            if i >= len then acc
            else
                val b = src(off + i)
                if b < '0' || b > '9' then -1
                else loop(i + 1, acc * 10 + (b - '0'))
        loop(0, 0)
end parseContentLength
```

Fixed code:
```scala
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
        loop(0, 0)
end parseContentLength
```

**Performance:** Zero alloc -- two int comparisons added to the existing digit loop. The constants 214748364 and 7 are compile-time literals. No branching change in the common case (typical Content-Length values are well under 214748364).

---

## Fix 8: Bare LF as chunk line terminator (CVE-2025-22871)

**File:** `ChunkedBodyDecoder.scala:261-291` (`processReadSize`)

**Vulnerability:** The chunk size parser scans for LF (`\n`) and accepts it as a line terminator regardless of whether CR (`\r`) precedes it. A bare LF (`5\nhello`) is accepted the same as a proper CRLF (`5\r\nhello`). This creates disagreement between proxy and backend about chunk boundaries (CVE-2025-22871).

**Fix:** When LF is found, check that the byte before it is CR. If the LF has no preceding CR, reject by setting `chunkSize = -1` (which will be treated as an invalid/zero-length chunk that completes immediately) and transitioning to `PhaseDone` with an error. Actually, to keep it simpler and avoid adding error state to the decoder, we validate in `parseChunkSizeLine`: the line bytes are accumulated into `sizeLine`, which includes all bytes before LF. If the last byte of `sizeLine` is not CR, the line was terminated by bare LF. We return `-1` (invalid) which currently gets treated as 0 (terminal chunk) by `parseChunkSizeLine`.

Wait -- `parseChunkSizeLine` strips trailing CR and parses hex. If there's no trailing CR, it just parses the hex digits directly, which gives a valid chunk size. The fix must be in `processReadSize` where we can detect that the byte before LF is not CR.

Current code (in `processReadSize`):
```scala
val lfPos = findLf(readPos)
if lfPos < 0 then
    // No complete line yet -- buffer the bytes for next time
    sizeLine.writeBytes(buf, readPos, end - readPos)
    readPos = end
    false
else
    // Copy bytes up to LF into sizeLine
    sizeLine.writeBytes(buf, readPos, lfPos - readPos)
    readPos = lfPos + 1

    // Parse the accumulated size line
    val lineArr = sizeLine.toByteArray
    sizeLine.reset()
    chunkSize = parseChunkSizeLine(lineArr)
```

Fixed code (in `processReadSize`):
```scala
val lfPos = findLf(readPos)
if lfPos < 0 then
    // No complete line yet -- buffer the bytes for next time
    sizeLine.writeBytes(buf, readPos, end - readPos)
    readPos = end
    false
else
    // Copy bytes up to LF into sizeLine
    sizeLine.writeBytes(buf, readPos, lfPos - readPos)
    readPos = lfPos + 1

    // Validate CRLF: the byte before LF must be CR.
    // The CR is the last byte in sizeLine (since we copied up to but not including LF).
    val lineArr = sizeLine.toByteArray
    sizeLine.reset()
    val lineLen = lineArr.length
    if lineLen == 0 || lineArr(lineLen - 1) != CR then
        // Bare LF without CR -- reject (CVE-2025-22871)
        phase = PhaseDone
        true
    else
        chunkSize = parseChunkSizeLine(lineArr)
        dataRead = 0

        if chunkSize == 0 then
            phase = PhaseReadTrailer
        else
            phase = PhaseReadData
        end if
        true
    end if
```

Note: `lineArr` is already being allocated by `sizeLine.toByteArray` in the current code -- this is not a new allocation introduced by the fix. The only addition is an int comparison (`lineLen == 0`) and a byte comparison (`lineArr(lineLen - 1) != CR`).

**Performance:** Zero additional alloc -- the `toByteArray` call already exists. The fix adds one int comparison and one byte read to the existing code path.

---

## Fix 9: Missing CRLF after chunk data

**File:** `ChunkedBodyDecoder.scala:311-347` (`processReadDataCrlf`)

**Vulnerability:** After consuming chunk data, the decoder expects CRLF but accepts bare LF (line 337-340: `else if buf(readPos) == LF then ... readPos += 1; phase = PhaseReadSize; true`) and even no-CRLF-at-all (line 343-345: `else ... phase = PhaseReadSize; true`). This means chunk framing errors are silently ignored, allowing malformed chunked bodies to be parsed as valid.

**Fix:** Require strict CRLF after chunk data. Remove the bare-LF fallback and the no-CRLF fallback. If the byte after chunk data is not CR (or if CR is not followed by LF), the decoder should reject by transitioning to `PhaseDone` (treating it as a framing error).

Current code:
```scala
private def processReadDataCrlf(): Boolean =
    val available = writePos - readPos
    if available < 1 then
        false
    else if sawCr then
        // Previously consumed CR, now looking for LF
        if buf(readPos) == LF then
            readPos += 1
        sawCr = false
        phase = PhaseReadSize
        true
    else if buf(readPos) == CR then
        readPos += 1
        if readPos < writePos && buf(readPos) == LF then
            readPos += 1
            phase = PhaseReadSize
            true
        else if readPos >= writePos then
            // CR consumed, need LF in next read
            sawCr = true
            false
        else
            // CR followed by something else -- treat as end of separator
            phase = PhaseReadSize
            true
        end if
    else if buf(readPos) == LF then
        // Just LF without CR -- tolerate it
        readPos += 1
        phase = PhaseReadSize
        true
    else
        // No CRLF -- continue anyway
        phase = PhaseReadSize
        true
    end if
end processReadDataCrlf
```

Fixed code:
```scala
private def processReadDataCrlf(): Boolean =
    val available = writePos - readPos
    if available < 1 then
        false
    else if sawCr then
        // Previously consumed CR, now looking for LF
        if buf(readPos) == LF then
            readPos += 1
            sawCr = false
            phase = PhaseReadSize
            true
        else
            // CR not followed by LF -- framing error
            phase = PhaseDone
            true
        end if
    else if buf(readPos) == CR then
        readPos += 1
        if readPos < writePos && buf(readPos) == LF then
            readPos += 1
            phase = PhaseReadSize
            true
        else if readPos >= writePos then
            // CR consumed, need LF in next read
            sawCr = true
            false
        else
            // CR followed by something other than LF -- framing error
            phase = PhaseDone
            true
        end if
    else
        // Neither CR nor LF -- framing error (missing CRLF after chunk data)
        phase = PhaseDone
        true
    end if
end processReadDataCrlf
```

**Performance:** Zero alloc -- same byte comparisons, just different branch targets. Removes two fallback branches that were hiding bugs.

---

## Fix 10: Multiple spaces in request line

**File:** `Http1Parser.scala:219-259` (`parseRequestLine`)

**Vulnerability:** The parser finds the first space (sp1) and then searches for the second space (sp2) starting from `sp1 + 1`. With input `"GET  /  HTTP/1.1"`, sp1 is at index 3, and the search for sp2 starts at index 4 which is the second space. The URI becomes `""` (empty, from index 4 to 4). Then `sp2 + 1` points to `/`, and the version check reads garbage. More importantly, the empty path is accepted, and the request is not rejected. RFC 7230 requires exactly one SP between method, request-target, and HTTP-version.

**Fix:** After finding sp1, check that `rawBuf(sp1 + 1)` is not a space. If it is, set `invalid = true`. Similarly, after finding sp2, verify sp2 == the position of the actual space (no double-space before the version).

Current code:
```scala
val sp1 = indexOfByte(rawBuf, start, end, ' ')
if sp1 != -1 then
    // Parse method via byte-level comparison (zero-alloc)
    val ordinal = matchMethod(rawBuf, start, sp1 - start)
    if ordinal != -1 then
        builder.setMethod(ordinal)

        // Find second space (before HTTP version)
        val sp2 = indexOfByte(rawBuf, sp1 + 1, end, ' ')
```

Fixed code:
```scala
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
```

**Performance:** Zero alloc -- two byte reads at already-computed indices (sp1+1, sp2+1), both of which are adjacent to bytes already accessed. Inline in existing parsing flow.

---

## Fix 11: Tab after colon not treated as OWS

**File:** `Http1Parser.scala:311-313` (`skipSpaces`)

**Vulnerability:** RFC 7230 defines OWS (optional whitespace) as `OWS = *( SP / HTAB )`. The parser's `skipSpaces` only skips SP (0x20), not HTAB (0x09). When a header like `Transfer-Encoding:\tchunked` is received, the tab is included as the first character of the value. This means `asciiEqualsIgnoreCase(valSrc, valOff, "chunked")` compares `"\tchunked"` against `"chunked"` and fails -- the parser does not enter chunked mode. If a proxy strips the tab (correctly treating it as OWS), it sees `Transfer-Encoding: chunked` and expects chunked encoding, while kyo-http sees a non-chunked request with Content-Length -- classic TE smuggling.

**Fix:** Expand `skipSpaces` to also skip HTAB (0x09).

Current code:
```scala
@tailrec private def skipSpaces(rawBuf: Array[Byte], from: Int, limit: Int): Int =
    if from < limit && rawBuf(from) == ' ' then skipSpaces(rawBuf, from + 1, limit)
    else from
```

Fixed code:
```scala
/** Skips OWS bytes (SP and HTAB) starting at `from` up to `limit`. RFC 7230: OWS = *( SP / HTAB ). */
@tailrec private def skipSpaces(rawBuf: Array[Byte], from: Int, limit: Int): Int =
    if from < limit then
        val b = rawBuf(from)
        if b == ' ' || b == '\t' then skipSpaces(rawBuf, from + 1, limit)
        else from
    else from
```

**Performance:** Zero alloc -- one additional byte comparison in the existing tail-recursive loop. The byte is already loaded from `rawBuf(from)` into a local; the `||` short-circuits on the common SP case.

---

## Fix 12: Null byte in header name

**File:** `Http1Parser.scala:286-301` (inside `parseHeaders`, header line processing)

**Vulnerability:** Null bytes (0x00) in header names pass through the parser undetected. When a header like `X-Evil\0Header: value` is parsed, the raw bytes are stored including the null. If downstream processing (or a C-based backend receiving a proxied request) treats null as a string terminator, the header name is truncated to `X-Evil`, enabling header injection.

**Fix:** After computing `nameLen` (colonIdx - lineStart), scan the header name bytes for null (0x00). If found, set `invalid = true`. This scan is done inline before `addHeader`.

Current code (after the colon is found):
```scala
if colonIdx != -1 then
    val nameStart = lineStart
    val nameLen   = colonIdx - lineStart

    // Skip ": " (colon + optional space)
    val valStart = skipSpaces(rawBuf, colonIdx + 1, actualLineEnd)
    val valLen   = actualLineEnd - valStart

    builder.addHeader(rawBuf, nameStart, nameLen, rawBuf, valStart, valLen)

    // Detect special headers
    detectSpecialHeader(rawBuf, nameStart, nameLen, rawBuf, valStart, valLen)
```

Fixed code:
```scala
if colonIdx != -1 then
    val nameStart = lineStart
    val nameLen   = colonIdx - lineStart
    // RFC 7230 section 3.2.4: no whitespace between header name and colon
    if nameLen > 0 && (rawBuf(colonIdx - 1) == ' ' || rawBuf(colonIdx - 1) == '\t') then
        invalid = true

    // Reject null bytes in header name
    if containsNull(rawBuf, nameStart, nameLen) then
        invalid = true

    // Skip ": " (colon + optional OWS)
    val valStart = skipSpaces(rawBuf, colonIdx + 1, actualLineEnd)
    val valLen   = actualLineEnd - valStart

    // Reject null bytes in header value
    if containsNull(rawBuf, valStart, valLen) then
        invalid = true

    builder.addHeader(rawBuf, nameStart, nameLen, rawBuf, valStart, valLen)

    // Detect special headers
    detectSpecialHeader(rawBuf, nameStart, nameLen, rawBuf, valStart, valLen)
```

New private method:

```scala
/** Scans buf[off..off+len) for null bytes (0x00). */
@tailrec private def containsNull(buf: Array[Byte], off: Int, remaining: Int): Boolean =
    if remaining <= 0 then false
    else if buf(off) == 0 then true
    else containsNull(buf, off + 1, remaining - 1)
```

Note: Fix 4 (space before colon) and Fix 13 (null in value) are integrated into this same block to avoid scattered checks. See the combined code above.

**Performance:** Zero alloc -- tail-recursive byte scan over header name bytes. Header names are typically 4-20 bytes. This scans the same bytes that `asciiEqualsIgnoreCase` will scan in `detectSpecialHeader`, so they're hot in L1 cache.

---

## Fix 13: Null byte in header value

**File:** `Http1Parser.scala:286-301` (inside `parseHeaders`, header line processing)

**Vulnerability:** Same as Fix 12 but for header values. A null byte in a value like `val\0ue` passes through to `ParsedRequest` where it may cause truncation in downstream systems.

**Fix:** Scan header value bytes for null (0x00) after `skipSpaces`. If found, set `invalid = true`. This is shown integrated in Fix 12's code above -- the `containsNull(rawBuf, valStart, valLen)` call.

Current code:
```scala
val valStart = skipSpaces(rawBuf, colonIdx + 1, actualLineEnd)
val valLen   = actualLineEnd - valStart

builder.addHeader(rawBuf, nameStart, nameLen, rawBuf, valStart, valLen)
```

Fixed code:
```scala
val valStart = skipSpaces(rawBuf, colonIdx + 1, actualLineEnd)
val valLen   = actualLineEnd - valStart

// Reject null bytes in header value
if containsNull(rawBuf, valStart, valLen) then
    invalid = true

builder.addHeader(rawBuf, nameStart, nameLen, rawBuf, valStart, valLen)
```

**Performance:** Zero alloc -- same `containsNull` scan as Fix 12. Header values are typically short (Host, Content-Length, etc.). The bytes are about to be copied by `addHeader` anyway, so scanning them first is essentially free in terms of cache behavior.

---

## Fix 14: Header line without colon accepted (CVE-2019-20444)

**File:** `Http1Parser.scala:288-301` (inside `parseHeaders`)

**Vulnerability:** When `colonIdx == -1` (no colon found in a header line), the parser silently skips the line and continues parsing. This means a request like `GET / HTTP/1.1\r\nHost: h\r\nNOCOLONHERE\r\n\r\n` is accepted with only the `Host` header. Netty CVE-2019-20444 showed that accepting colon-less header lines can lead to header confusion between proxies.

**Fix:** When `colonIdx == -1` and the line is non-empty, set `invalid = true`.

Current code:
```scala
if actualLineEnd > lineStart then
    // Find the colon separator
    val colonIdx = indexOfByte(rawBuf, lineStart, actualLineEnd, ':')
    if colonIdx != -1 then
        val nameStart = lineStart
        val nameLen   = colonIdx - lineStart
        // ... process header ...
    end if
end if
```

Fixed code:
```scala
if actualLineEnd > lineStart then
    // Find the colon separator
    val colonIdx = indexOfByte(rawBuf, lineStart, actualLineEnd, ':')
    if colonIdx != -1 then
        val nameStart = lineStart
        val nameLen   = colonIdx - lineStart
        // ... process header ...
    else
        // CVE-2019-20444: header line without colon is invalid
        invalid = true
    end if
end if
```

**Performance:** Zero alloc -- single boolean write in the existing `else` branch that was previously empty (implicit `end if`). No new comparisons needed.

---

## Summary of Changes by File

### `Http1Parser.scala`

**New fields** (3 `private var`, all primitive):
```scala
private var invalid             = false
private var hasContentLength    = false
private var hasTransferEncoding = false
```

**New methods** (2 `@tailrec private`, zero-alloc):
```scala
@tailrec private def containsBareCr(buf: Array[Byte], i: Int, end: Int): Boolean = ...
@tailrec private def containsNull(buf: Array[Byte], off: Int, remaining: Int): Boolean = ...
```

**Modified methods:**
| Method | Fix # | Change |
|--------|-------|--------|
| `reset()` | all | Reset `invalid`, `hasContentLength`, `hasTransferEncoding` |
| `parse()` | all | Check `invalid` flag before calling `onRequestParsed` |
| `packRequest()` | 6 | Call `containsBareCr` before parsing headers, reset new flags |
| `parseRequestLine()` | 10 | Check for double-space after sp1 and sp2 |
| `parseHeaders()` | 4,5,12,13,14 | Obs-fold check, space-before-colon, null scans, colon-less rejection |
| `skipSpaces()` | 11 | Add HTAB (0x09) to whitespace set |
| `detectSpecialHeader()` | 1,2,3 | Duplicate CL detection, CL+TE conflict detection |
| `parseContentLength()` | 7 | Integer overflow guard |

### `ChunkedBodyDecoder.scala`

**Modified methods:**
| Method | Fix # | Change |
|--------|-------|--------|
| `processReadSize()` | 8 | Reject bare LF (require CR before LF in chunk size line) |
| `processReadDataCrlf()` | 9 | Remove bare-LF and no-CRLF fallbacks, require strict CRLF |

### No new allocations per request

Every fix uses only:
- `Boolean` flag reads/writes (Fix 1-6, 10, 12-14)
- `Int` comparisons against compile-time constants (Fix 7)
- `Byte` reads from the existing buffer at already-computed indices (Fix 4, 5, 6, 8, 9, 10, 11)
- Tail-recursive scans over bytes already in L1 cache (Fix 6, 12, 13)

No `String`, `Array`, `Exception`, or boxed type is created by any fix.

## Consolidated Code: Complete `parseHeaders` Method After All Fixes

For clarity, here is the full `parseHeaders` method with Fixes 4, 5, 12, 13, and 14 integrated:

```scala
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
```

## Consolidated Code: Complete `detectSpecialHeader` Method After All Fixes

```scala
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
```
