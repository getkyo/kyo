package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

/** HTTP/1.1 protocol implementation using ByteStream's stream-threading API.
  *
  * Unlike Http1Protocol (which uses a mutable BufferedStream), all read operations here take a `Stream[Span[Byte], Async]` and return
  * `(result, remainingStream)` tuples. This allows callers to thread the stream through multiple reads without mutable state.
  *
  * Pure parsing functions are shared with Http1Protocol (copied here as private[internal] for testing).
  */
object Http1Protocol2:

    private val Utf8          = StandardCharsets.UTF_8
    private val MaxHeaderSize = 65536

    // ── Public API ──────────────────────────────────────────────

    def readRequest(src: Stream[Span[Byte], Async], maxSize: Int)(using
        Frame
    )
        : ((HttpMethod, String, HttpHeaders, HttpBody), Stream[Span[Byte], Async]) < (Async & Abort[HttpException]) =
        ByteStream.readUntil(src, ByteStream.CRLF_CRLF, MaxHeaderSize).map { case (headerBytes, remaining) =>
            val headerStr = new String(headerBytes.toArrayUnsafe, Utf8)
            val lines     = headerStr.split("\r\n")
            if lines.isEmpty then Abort.fail(HttpProtocolException("Empty request"))
            else
                Abort.get(parseRequestLine(lines(0))).map { case (method, path) =>
                    val headers = parseHeaders(lines, startIndex = 1)
                    readBody(remaining, headers, maxSize).map { case (body, remaining2) =>
                        ((method, path, headers, body), remaining2)
                    }
                }
            end if
        }
    end readRequest

    /** Read a request head and streaming chunked body for use by the server when a streaming route is matched.
      *
      * Returns the parsed method/path/headers along with: - body as `HttpBody.Streamed` backed by a lazy chunked stream - remainingStream
      * backed by a Promise fulfilled when the body stream is fully consumed
      *
      * For non-chunked bodies, falls back to `readRequest` semantics (buffered body).
      */
    def readRequestStreaming(src: Stream[Span[Byte], Async], maxSize: Int)(using
        Frame
    )
        : ((HttpMethod, String, HttpHeaders, HttpBody), Stream[Span[Byte], Async]) < (Async & Abort[HttpException]) =
        ByteStream.readUntil(src, ByteStream.CRLF_CRLF, MaxHeaderSize).map { case (headerBytes, remaining) =>
            val headerStr = new String(headerBytes.toArrayUnsafe, Utf8)
            val lines     = headerStr.split("\r\n")
            if lines.isEmpty then Abort.fail(HttpProtocolException("Empty request"))
            else
                Abort.get(parseRequestLine(lines(0))).map { case (method, path) =>
                    val headers = parseHeaders(lines, startIndex = 1)
                    val isChunked = headers.get("Transfer-Encoding") match
                        case Present(v) => v.toLowerCase.contains("chunked")
                        case Absent     => false
                    if isChunked then
                        readChunkedBodyStreaming(remaining).map { case (body, remaining2) =>
                            ((method, path, headers, body), remaining2)
                        }
                    else
                        readBody(remaining, headers, maxSize).map { case (body, remaining2) =>
                            ((method, path, headers, body), remaining2)
                        }
                    end if
                }
            end if
        }
    end readRequestStreaming

    def readResponse(src: Stream[Span[Byte], Async], maxSize: Int, requestMethod: HttpMethod = HttpMethod.GET)(using
        Frame
    )
        : ((HttpStatus, HttpHeaders, HttpBody), Stream[Span[Byte], Async]) < (Async & Abort[HttpException]) =
        readResponseImpl(src, maxSize, requestMethod, streaming = false)

    /** Read response with chunked bodies returned as HttpBody.Streamed (lazy stream). Used by the client for streaming response routes
      * (SSE, NDJSON, byte streams).
      */
    def readResponseStreaming(src: Stream[Span[Byte], Async], maxSize: Int, requestMethod: HttpMethod = HttpMethod.GET)(using
        Frame
    )
        : ((HttpStatus, HttpHeaders, HttpBody), Stream[Span[Byte], Async]) < (Async & Abort[HttpException]) =
        readResponseImpl(src, maxSize, requestMethod, streaming = true)

    private def readResponseImpl(
        src: Stream[Span[Byte], Async],
        maxSize: Int,
        requestMethod: HttpMethod,
        streaming: Boolean
    )(using
        Frame
    )
        : ((HttpStatus, HttpHeaders, HttpBody), Stream[Span[Byte], Async]) < (Async & Abort[HttpException]) =
        ByteStream.readUntil(src, ByteStream.CRLF_CRLF, MaxHeaderSize).map { case (headerBytes, remaining) =>
            val headerStr = new String(headerBytes.toArrayUnsafe, Utf8)
            val lines     = headerStr.split("\r\n")
            if lines.isEmpty then Abort.fail(HttpProtocolException("Empty response"))
            else
                Abort.get(parseStatusLine(lines(0))).map { status =>
                    val headers = parseHeaders(lines, startIndex = 1)
                    if status.code == 204 || status.code == 304 || (status.code >= 100 && status.code < 200) || requestMethod == HttpMethod.HEAD
                    then
                        ((status, headers, HttpBody.Empty), remaining)
                    else
                        val isChunked = headers.get("Transfer-Encoding") match
                            case Present(v) => v.toLowerCase.contains("chunked")
                            case Absent     => false
                        if streaming && isChunked then
                            readChunkedBodyStreaming(remaining).map { case (body, remaining2) =>
                                ((status, headers, body), remaining2)
                            }
                        else
                            readBody(remaining, headers, maxSize).map { case (body, remaining2) =>
                                ((status, headers, body), remaining2)
                            }
                        end if
                    end if
                }
            end if
        }
    end readResponseImpl

    def writeRequest(stream: TransportStream2, method: HttpMethod, path: String, headers: HttpHeaders, body: HttpBody)(using
        Frame
    )
        : Unit < Async =
        val finalHeaders = addBodyHeaders(headers, body)
        val sb           = new StringBuilder(256)
        discard(sb.append(method.name).append(' ').append(path).append(" HTTP/1.1\r\n"))
        finalHeaders.foreach { (name, value) =>
            discard(sb.append(name).append(": ").append(value).append("\r\n"))
        }
        discard(sb.append("\r\n"))
        stream.write(Span.fromUnsafe(sb.toString.getBytes(Utf8))).andThen {
            writeBodyPayload(stream, body)
        }
    end writeRequest

    def writeResponse(stream: TransportStream2, status: HttpStatus, headers: HttpHeaders, body: HttpBody)(using
        Frame
    )
        : Unit < Async =
        val finalHeaders = addBodyHeaders(headers, body)
        val sb           = new StringBuilder(256)
        discard(sb.append("HTTP/1.1 ").append(status.code).append(' ').append(reasonPhrase(status)).append("\r\n"))
        finalHeaders.foreach { (name, value) =>
            discard(sb.append(name).append(": ").append(value).append("\r\n"))
        }
        discard(sb.append("\r\n"))
        stream.write(Span.fromUnsafe(sb.toString.getBytes(Utf8))).andThen {
            writeBodyPayload(stream, body)
        }
    end writeResponse

    /** Write a streaming body using chunked transfer encoding. */
    def writeStreamingBody(stream: TransportStream2, body: Stream[Span[Byte], Async])(using Frame): Unit < Async =
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
    end writeStreamingBody

    def isKeepAlive(headers: HttpHeaders): Boolean =
        headers.get("Connection") match
            case Present(v) => !v.toLowerCase.contains("close")
            case Absent     => true // HTTP/1.1 default is keep-alive

    // ── Pure parsing functions (private[internal] for testing) ──

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

    private[internal] def reasonPhrase(status: HttpStatus): String =
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

    // ── Internal I/O helpers ────────────────────────────────────

    /** Determine body from headers and read it, returning (body, remainingStream). */
    private def readBody(stream: Stream[Span[Byte], Async], headers: HttpHeaders, maxSize: Int)(using
        Frame
    )
        : (HttpBody, Stream[Span[Byte], Async]) < (Async & Abort[HttpException]) =
        val isChunked = headers.get("Transfer-Encoding") match
            case Present(v) => v.toLowerCase.contains("chunked")
            case Absent     => false

        if isChunked then readChunkedBody(stream)
        else
            headers.get("Content-Length") match
                case Present(lenStr) =>
                    Abort.get(
                        Result.catching[NumberFormatException](lenStr.trim.toInt)
                            .mapFailure(_ => HttpProtocolException(s"Invalid Content-Length: $lenStr"))
                    ).map { len =>
                        if len > maxSize then Abort.fail(HttpPayloadTooLargeException(len, maxSize))
                        else if len <= 0 then (HttpBody.Empty, stream)
                        else
                            ByteStream.readExact(stream, len).map { case (data, remaining) =>
                                (HttpBody.Buffered(data), remaining)
                            }
                    }
                case Absent => (HttpBody.Empty, stream)
        end if
    end readBody

    /** Read a chunked body lazily as a stream.
      *
      * Returns (HttpBody.Streamed(lazyStream), remainingStream) where lazyStream yields chunks as they arrive. The remainingStream is
      * backed by a Promise that is completed with the post-body stream when the final 0-size chunk is read. This allows the server
      * keep-alive loop to continue reading subsequent requests after the body stream is fully consumed.
      */
    private def readChunkedBodyStreaming(stream: Stream[Span[Byte], Async])(using
        Frame
    )
        : (HttpBody, Stream[Span[Byte], Async]) < (Async & Abort[HttpException]) =
        Promise.init[Stream[Span[Byte], Async], Any].map { remainingPromise =>
            // Helper to complete the remaining promise and signal end of stream.
            def finishWith(after: Stream[Span[Byte], Async])(using Frame): Maybe[(Span[Byte], Stream[Span[Byte], Async])] < Async =
                remainingPromise.completeDiscard(Result.succeed(after)).andThen(Maybe.empty)

            val lazyStream: Stream[Span[Byte], Async] =
                Stream.unfold(stream, chunkSize = 1) { src =>
                    // All HttpException errors are absorbed here — stream terminates on error
                    Abort.run[HttpException](ByteStream.readLine(src)).map {
                        case Result.Failure(_) | Result.Panic(_) => finishWith(Stream.empty[Span[Byte]])
                        case Result.Success((lineBytes, src2)) =>
                            val line = new String(lineBytes.toArrayUnsafe, Utf8)
                            Abort.run[HttpException](Abort.get(parseChunkHeader(line))).map {
                                case Result.Failure(_) | Result.Panic(_) => finishWith(Stream.empty[Span[Byte]])
                                case Result.Success(size) =>
                                    if size == 0 then
                                        // Final chunk — consume trailing CRLF and complete remaining promise
                                        Abort.run[HttpException](ByteStream.readLine(src2)).map {
                                            case Result.Success((_, src3)) => finishWith(src3)
                                            case _                         => finishWith(Stream.empty[Span[Byte]])
                                        }
                                    else
                                        Abort.run[HttpException](ByteStream.readExact(src2, size)).map {
                                            case Result.Failure(_) | Result.Panic(_) => finishWith(Stream.empty[Span[Byte]])
                                            case Result.Success((data, src3)) =>
                                                Abort.run[HttpException](ByteStream.readLine(src3)).map {
                                                    case Result.Failure(_) | Result.Panic(_) =>
                                                        finishWith(Stream.empty[Span[Byte]])
                                                    case Result.Success((_, src4)) =>
                                                        Maybe((data, src4))
                                                }
                                        }
                                    end if
                            }
                    }
                }
            // remainingStream is backed by the promise: available once lazyStream is fully consumed
            val remainingStream: Stream[Span[Byte], Async] =
                Stream.unwrap(remainingPromise.get.map(s => s))
            (HttpBody.Streamed(lazyStream), remainingStream)
        }
    end readChunkedBodyStreaming

    /** Read a complete chunked body, reassembling all chunks into a single Span. Returns (buffered body, remaining stream). */
    private def readChunkedBody(stream: Stream[Span[Byte], Async])(using
        Frame
    )
        : (HttpBody, Stream[Span[Byte], Async]) < (Async & Abort[HttpException]) =
        Loop(stream, Chunk.empty[Span[Byte]]) { (src, accum) =>
            // Read the chunk size line
            ByteStream.readLine(src).map { case (lineBytes, src2) =>
                val line = new String(lineBytes.toArrayUnsafe, Utf8)
                Abort.get(parseChunkHeader(line)).map { size =>
                    if size == 0 then
                        // Final chunk — read the trailing CRLF (empty line) and done
                        ByteStream.readLine(src2).map { case (_, src3) =>
                            val combined = Span.concat(accum.toSeq*)
                            Loop.done((HttpBody.Buffered(combined), src3))
                        }
                    else
                        // Read exactly `size` bytes for the chunk data
                        ByteStream.readExact(src2, size).map { case (data, src3) =>
                            // Read the trailing CRLF after chunk data
                            ByteStream.readLine(src3).map { case (_, src4) =>
                                Loop.continue(src4, accum.append(data))
                            }
                        }
                    end if
                }
            }
        }
    end readChunkedBody

    /** Return headers augmented with the appropriate body framing header. */
    private def addBodyHeaders(headers: HttpHeaders, body: HttpBody): HttpHeaders =
        body match
            case HttpBody.Empty          => headers
            case HttpBody.Buffered(data) => headers.set("Content-Length", data.size.toString)
            case HttpBody.Streamed(_)    => headers.set("Transfer-Encoding", "chunked")

    /** Write the body payload to the stream (headers already sent). */
    private def writeBodyPayload(stream: TransportStream2, body: HttpBody)(using Frame): Unit < Async =
        body match
            case HttpBody.Empty            => Kyo.unit
            case HttpBody.Buffered(data)   => stream.write(data)
            case HttpBody.Streamed(chunks) => writeStreamingBody(stream, chunks)

end Http1Protocol2
