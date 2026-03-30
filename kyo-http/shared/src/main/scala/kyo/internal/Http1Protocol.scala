package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

/** HTTP/1.1 text-based wire format with chunked transfer encoding.
  *
  * Pure parsing functions are exposed as private[internal] for unit testing. I/O methods (readRequest, readResponse, etc.) operate on
  * TransportStream.
  */
object Http1Protocol extends Protocol:

    private val Utf8 = StandardCharsets.UTF_8

    // ── Protocol implementation ──────────────────────────────────

    override def buffered(stream: TransportStream): TransportStream =
        new BufferedStream(stream)

    def readRequest(stream: TransportStream, maxSize: Int)(using
        Frame
    )
        : (HttpMethod, String, HttpHeaders, HttpBody) < (Async & Abort[HttpException]) =
        val bs = stream match
            case b: BufferedStream => b
            case _                 => new BufferedStream(stream)
        readHeaderBlock(bs, maxSize).map { case (headerBytes, leftover) =>
            val headerStr = new String(headerBytes.toArrayUnsafe, Utf8)
            val lines     = headerStr.split("\r\n")
            if lines.isEmpty then Abort.fail(HttpProtocolException("Empty request"))
            else
                Abort.get(parseRequestLine(lines(0))).map { (method, path) =>
                    val headers = parseHeaders(lines, startIndex = 1)
                    bs.pushBack(leftover)
                    readBody(bs, headers, maxSize).map { body =>
                        (method, path, headers, body)
                    }
                }
            end if
        }
    end readRequest

    def readResponse(stream: TransportStream, maxSize: Int, requestMethod: HttpMethod = HttpMethod.GET)(using
        Frame
    )
        : (HttpStatus, HttpHeaders, HttpBody) < (Async & Abort[HttpException]) =
        val buffered = stream match
            case b: BufferedStream => b
            case _                 => new BufferedStream(stream)
        readHeaderBlock(buffered, maxSize).map { case (headerBytes, leftover) =>
            val headerStr = new String(headerBytes.toArrayUnsafe, Utf8)
            val lines     = headerStr.split("\r\n")
            if lines.isEmpty then Abort.fail(HttpProtocolException("Empty response"))
            else
                Abort.get(parseStatusLine(lines(0))).map { status =>
                    val headers = parseHeaders(lines, startIndex = 1)
                    buffered.pushBack(leftover)
                    if status.code == 204 || status.code == 304 || (status.code >= 100 && status.code < 200) || requestMethod == HttpMethod.HEAD
                    then
                        (status, headers, HttpBody.Empty)
                    else
                        readBody(buffered, headers, maxSize).map { body =>
                            (status, headers, body)
                        }
                    end if
                }
            end if
        }
    end readResponse

    def writeResponseHead(stream: TransportStream, status: HttpStatus, headers: HttpHeaders)(using
        Frame
    )
        : Unit < Async =
        val sb = new StringBuilder(256)
        discard(sb.append("HTTP/1.1 ").append(status.code).append(' ').append(reasonPhrase(status)).append("\r\n"))
        headers.foreach { (name, value) =>
            discard(sb.append(name).append(": ").append(value).append("\r\n"))
        }
        discard(sb.append("\r\n"))
        stream.write(Span.fromUnsafe(sb.toString.getBytes(Utf8)))
    end writeResponseHead

    def writeRequestHead(stream: TransportStream, method: HttpMethod, path: String, headers: HttpHeaders)(using
        Frame
    )
        : Unit < Async =
        val sb = new StringBuilder(256)
        discard(sb.append(method.name).append(' ').append(path).append(" HTTP/1.1\r\n"))
        headers.foreach { (name, value) =>
            discard(sb.append(name).append(": ").append(value).append("\r\n"))
        }
        discard(sb.append("\r\n"))
        stream.write(Span.fromUnsafe(sb.toString.getBytes(Utf8)))
    end writeRequestHead

    def writeBody(stream: TransportStream, data: Span[Byte])(using
        Frame
    )
        : Unit < Async =
        if data.isEmpty then Kyo.unit
        else stream.write(data)

    def writeStreamingBody(stream: TransportStream, body: Stream[Span[Byte], Async])(using
        Frame
    )
        : Unit < Async =
        body.foreachChunk { chunk =>
            Loop.indexed { i =>
                if i >= chunk.size then Loop.done(())
                else
                    val span = chunk(i)
                    if span.isEmpty then Loop.continue
                    else stream.write(encodeChunk(span)).andThen(Loop.continue)
            }
        }.andThen {
            stream.write(Span.fromUnsafe("0\r\n\r\n".getBytes(Utf8)))
        }

    def isKeepAlive(headers: HttpHeaders): Boolean =
        headers.get("Connection") match
            case Present(v) =>
                !v.toLowerCase.contains("close")
            case Absent => true // HTTP/1.1 default is keep-alive

    // ── Pure parsing functions (unit testable) ──────────────────

    private[internal] def parseRequestLine(line: String)(using Frame): Result[HttpException, (HttpMethod, String)] =
        val sp1 = line.indexOf(' ')
        if sp1 < 0 then Result.fail(HttpProtocolException(s"Malformed request line: $line"))
        else
            val sp2 = line.indexOf(' ', sp1 + 1)
            if sp2 < 0 then Result.fail(HttpProtocolException(s"Malformed request line: $line"))
            else
                val methodStr = line.substring(0, sp1)
                val path      = line.substring(sp1 + 1, sp2)
                val version   = line.substring(sp2 + 1)
                if !version.startsWith("HTTP/1.") then
                    Result.fail(HttpProtocolException(s"Unsupported HTTP version: $version"))
                else
                    Result.Success((HttpMethod.unsafe(methodStr), path))
                end if
            end if
        end if
    end parseRequestLine

    private[internal] def parseStatusLine(line: String)(using Frame): Result[HttpException, HttpStatus] =
        if !line.startsWith("HTTP/") then Result.fail(HttpProtocolException(s"Malformed status line: $line"))
        else
            val sp1 = line.indexOf(' ')
            if sp1 < 0 then Result.fail(HttpProtocolException(s"Malformed status line: $line"))
            else
                val sp2     = line.indexOf(' ', sp1 + 1)
                val codeStr = if sp2 < 0 then line.substring(sp1 + 1) else line.substring(sp1 + 1, sp2)
                Result.catching[NumberFormatException] {
                    HttpStatus(codeStr.toInt)
                }.mapFailure(e => HttpProtocolException(s"Invalid status code: $codeStr"))
            end if

    private[internal] def parseHeaders(lines: Array[String], startIndex: Int): HttpHeaders =
        val builder = ChunkBuilder.init[String]
        var i       = startIndex
        while i < lines.length do
            val line = lines(i)
            if line.nonEmpty then
                val colonIdx = line.indexOf(':')
                if colonIdx > 0 then
                    val name  = line.substring(0, colonIdx)
                    val value = line.substring(colonIdx + 1).trim
                    discard(builder += name)
                    discard(builder += value)
                end if
            end if
            i += 1
        end while
        HttpHeaders.fromChunk(builder.result())
    end parseHeaders

    private[internal] def encodeChunk(data: Span[Byte]): Span[Byte] =
        val sizeHex = java.lang.Integer.toHexString(data.size)
        val header  = (sizeHex + "\r\n").getBytes(Utf8)
        val result  = new Array[Byte](header.length + data.size + 2)
        java.lang.System.arraycopy(header, 0, result, 0, header.length)
        discard(data.copyToArray(result, header.length))
        result(result.length - 2) = '\r'.toByte
        result(result.length - 1) = '\n'.toByte
        Span.fromUnsafe(result)
    end encodeChunk

    private[internal] def parseChunkHeader(line: String)(using Frame): Result[HttpException, Int] =
        val semiIdx = line.indexOf(';')
        val sizeStr = if semiIdx < 0 then line.trim else line.substring(0, semiIdx).trim
        Result.catching[NumberFormatException] {
            java.lang.Integer.parseInt(sizeStr, 16)
        }.mapFailure(e => HttpProtocolException(s"Invalid chunk size: $sizeStr"))
    end parseChunkHeader

    // ── Internal I/O helpers ────────────────────────────────────

    /** Read bytes until \r\n\r\n. Returns (header bytes, leftover body bytes). The leftover bytes are any data read past the \r\n\r\n
      * boundary.
      */
    /** Max header block size (separate from content length). */
    private val MaxHeaderSize = 65536

    private def readHeaderBlock(stream: TransportStream, maxSize: Int)(using
        Frame
    )
        : (Span[Byte], Span[Byte]) < (Async & Abort[HttpException]) =
        val buf   = new Array[Byte](4096)
        val accum = new java.io.ByteArrayOutputStream(1024)
        val sep   = "\r\n\r\n".getBytes(Utf8)

        Loop.foreach {
            stream.read(buf).map { n =>
                if n == 0 then Loop.continue // NIO spurious wakeup, retry
                else if n < 0 then
                    if accum.size() == 0 then Abort.fail(HttpConnectionClosedException())
                    else Loop.done(())
                else
                    accum.write(buf, 0, n)
                    if accum.size() > MaxHeaderSize then
                        Abort.fail(HttpProtocolException("Headers exceed max size"))
                    else
                        val bytes   = accum.toByteArray
                        val crlfIdx = indexOf(bytes, sep)
                        if crlfIdx >= 0 then Loop.done(())
                        else Loop.continue
                    end if
            }
        }.andThen {
            val bytes   = accum.toByteArray
            val crlfIdx = indexOf(bytes, sep)
            val all     = Span.fromUnsafe(bytes)
            if crlfIdx >= 0 then
                val headerBytes = all.slice(0, crlfIdx)
                val leftover    = all.slice(crlfIdx + 4, bytes.length) // skip \r\n\r\n
                (headerBytes, leftover)
            else
                (all, Span.empty[Byte])
            end if
        }
    end readHeaderBlock

    /** A TransportStream with pushBack support. Leftover bytes from header parsing are pushed back and re-read before the underlying
      * stream. Survives across keep-alive iterations.
      */
    class BufferedStream(underlying: TransportStream) extends TransportStream:
        private var buffer: Span[Byte] = Span.empty[Byte]
        private var bufPos: Int        = 0

        def pushBack(data: Span[Byte]): Unit =
            if data.nonEmpty then
                buffer = data
                bufPos = 0

        def read(buf: Array[Byte])(using Frame): Int < Async =
            if bufPos < buffer.size then
                Sync.defer {
                    val available = math.min(buf.length, buffer.size - bufPos)
                    discard(buffer.slice(bufPos, bufPos + available).copyToArray(buf))
                    bufPos += available
                    if bufPos >= buffer.size then
                        buffer = Span.empty[Byte]
                        bufPos = 0
                    available
                }
            else
                underlying.read(buf)

        def write(data: Span[Byte])(using Frame): Unit < Async =
            underlying.write(data)
    end BufferedStream

    /** A TransportStream that first yields prefix bytes, then delegates to the underlying stream. */
    private class PrefixedStream(prefix: Span[Byte], underlying: TransportStream) extends TransportStream:
        private var prefixPos = 0

        def read(buf: Array[Byte])(using Frame): Int < Async =
            if prefixPos < prefix.size then
                Sync.defer {
                    val available = math.min(buf.length, prefix.size - prefixPos)
                    discard(prefix.slice(prefixPos, prefixPos + available).copyToArray(buf))
                    prefixPos += available
                    available
                }
            else
                underlying.read(buf)

        def write(data: Span[Byte])(using Frame): Unit < Async =
            underlying.write(data)
    end PrefixedStream

    /** Determine body from headers and read it. */
    private def readBody(stream: TransportStream, headers: HttpHeaders, maxSize: Int)(using
        Frame
    )
        : HttpBody < (Async & Abort[HttpException]) =
        val isChunked = headers.get("Transfer-Encoding") match
            case Present(v) => v.toLowerCase.contains("chunked")
            case Absent     => false

        if isChunked then Sync.defer(HttpBody.Streamed(readChunkedStream(stream)))
        else
            headers.get("Content-Length") match
                case Present(lenStr) =>
                    Abort.get(
                        Result.catching[NumberFormatException](lenStr.trim.toInt)
                            .mapFailure(_ => HttpProtocolException(s"Invalid Content-Length: $lenStr"))
                    ).map { len =>
                        if len <= 0 then Sync.defer(HttpBody.Empty)
                        else readExactly(stream, len, maxSize).map(HttpBody.Buffered(_))
                    }
                case Absent => Sync.defer(HttpBody.Empty)
        end if
    end readBody

    /** Read exactly `len` bytes. */
    private def readExactly(stream: TransportStream, len: Int, maxSize: Int)(using
        Frame
    )
        : Span[Byte] < (Async & Abort[HttpException]) =
        if len > maxSize then Abort.fail(HttpPayloadTooLargeException(len, maxSize))
        else
            val result = new Array[Byte](len)
            var offset = 0
            Loop.foreach {
                if offset >= len then Loop.done(())
                else
                    val remaining = len - offset
                    val readBuf   = new Array[Byte](math.min(remaining, 8192))
                    stream.read(readBuf).map { n =>
                        if n == 0 then Loop.continue // NIO spurious wakeup
                        else if n < 0 then Abort.fail(HttpProtocolException(s"Unexpected EOF, read $offset of $len bytes"))
                        else
                            java.lang.System.arraycopy(readBuf, 0, result, offset, n)
                            offset += n
                            Loop.continue
                    }
            }.andThen(Span.fromUnsafe(result))

    /** Create a Stream that reads chunked transfer encoding. */
    private def readChunkedStream(stream: TransportStream)(using Frame): Stream[Span[Byte], Async] =
        Stream.unfold((), chunkSize = 1) { _ =>
            readLine(stream).map { line =>
                parseChunkHeader(line) match
                    case Result.Success(size) =>
                        if size == 0 then
                            readLine(stream).andThen(Maybe.empty)
                        else
                            Abort.run[HttpException](readExactly(stream, size, Int.MaxValue)).map {
                                case Result.Success(data) =>
                                    readLine(stream).andThen(Maybe((data, ())))
                                case Result.Error(_) =>
                                    // Chunk read failed or panicked — terminate stream.
                                    Maybe.empty
                            }
                    case Result.Error(_) =>
                        // Malformed chunk header — terminate stream.
                        Maybe.empty
                end match
            }
        }

    /** Read a single line (up to \r\n). */
    private def readLine(stream: TransportStream)(using Frame): String < Async =
        val accum  = new java.io.ByteArrayOutputStream(128)
        val buf    = new Array[Byte](1)
        var prevCr = false
        Loop.foreach {
            stream.read(buf).map { n =>
                if n < 0 then
                    Loop.done(()) // EOF
                else if n == 0 then
                    Loop.continue // no data yet, retry
                else
                    val b = buf(0)
                    if prevCr && b == '\n'.toByte then Loop.done(())
                    else
                        prevCr = b == '\r'.toByte
                        if !prevCr then accum.write(b.toInt)
                        Loop.continue
                    end if
                end if
            }
        }.andThen(new String(accum.toByteArray, Utf8))
    end readLine

    /** Find index of needle in haystack. Returns -1 if not found. */
    private def indexOf(haystack: Array[Byte], needle: Array[Byte]): Int =
        val limit = haystack.length - needle.length
        var i     = 0
        while i <= limit do
            var j     = 0
            var found = true
            while j < needle.length && found do
                if haystack(i + j) != needle(j) then found = false
                j += 1
            if found then return i
            i += 1
        end while
        -1
    end indexOf

    private def reasonPhrase(status: HttpStatus): String =
        status.code match
            case 100 => "Continue"
            case 101 => "Switching Protocols"
            case 200 => "OK"
            case 201 => "Created"
            case 204 => "No Content"
            case 301 => "Moved Permanently"
            case 302 => "Found"
            case 304 => "Not Modified"
            case 307 => "Temporary Redirect"
            case 308 => "Permanent Redirect"
            case 400 => "Bad Request"
            case 401 => "Unauthorized"
            case 403 => "Forbidden"
            case 404 => "Not Found"
            case 405 => "Method Not Allowed"
            case 408 => "Request Timeout"
            case 409 => "Conflict"
            case 413 => "Content Too Large"
            case 415 => "Unsupported Media Type"
            case 422 => "Unprocessable Content"
            case 429 => "Too Many Requests"
            case 500 => "Internal Server Error"
            case 502 => "Bad Gateway"
            case 503 => "Service Unavailable"
            case 504 => "Gateway Timeout"
            case _   => "Unknown"

end Http1Protocol
