package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

class Http1ProtocolTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8 = StandardCharsets.UTF_8

    /** Mock TransportStream backed by a byte array for reads and a ByteArrayOutputStream for writes. */
    class MockStream(input: Array[Byte]) extends TransportStream:
        private var readPos                               = 0
        private val output: java.io.ByteArrayOutputStream = new java.io.ByteArrayOutputStream()

        def read(buf: Array[Byte])(using Frame): Int < Async =
            Sync.defer {
                if readPos >= input.length then -1
                else
                    val available = math.min(buf.length, input.length - readPos)
                    java.lang.System.arraycopy(input, readPos, buf, 0, available)
                    readPos += available
                    available
            }

        def write(data: Span[Byte])(using Frame): Unit < Async =
            Sync.defer {
                val arr = data.toArrayUnsafe
                output.write(arr)
            }

        def written: Array[Byte]  = output.toByteArray
        def writtenString: String = output.toString(Utf8)
    end MockStream

    private def mockStream(s: String): MockStream = new MockStream(s.getBytes(Utf8))

    // ── Request line parsing ────────────────────────────────────

    "parseRequestLine" - {
        "GET /path" in run {
            Sync.defer {
                val result = Http1Protocol.parseRequestLine("GET /path HTTP/1.1")
                assert(result == Result.Success((HttpMethod.GET, "/path")))
            }
        }

        "POST /submit" in run {
            Sync.defer {
                val result = Http1Protocol.parseRequestLine("POST /submit HTTP/1.1")
                assert(result == Result.Success((HttpMethod.POST, "/submit")))
            }
        }

        "preserves query string" in run {
            Sync.defer {
                val result = Http1Protocol.parseRequestLine("GET /path?q=1&b=2 HTTP/1.1")
                assert(result == Result.Success((HttpMethod.GET, "/path?q=1&b=2")))
            }
        }

        "all standard methods" in run {
            Async.defer {
                val methods = Seq("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
                methods.foreach { m =>
                    val result = Http1Protocol.parseRequestLine(s"$m /test HTTP/1.1")
                    assert(result.isSuccess, s"$m should parse successfully")
                }
                succeed
            }
        }

        "empty string fails" in run {
            Sync.defer {
                val result = Http1Protocol.parseRequestLine("")
                assert(result.isFailure)
            }
        }

        "malformed fails" in run {
            Sync.defer {
                val result = Http1Protocol.parseRequestLine("INVALID")
                assert(result.isFailure)
            }
        }

        "wrong version fails" in run {
            Sync.defer {
                val result = Http1Protocol.parseRequestLine("GET /path HTTP/2.0")
                assert(result.isFailure)
            }
        }
    }

    // ── Status line parsing ─────────────────────────────────────

    "parseStatusLine" - {
        "200 OK" in run {
            Sync.defer {
                val result = Http1Protocol.parseStatusLine("HTTP/1.1 200 OK")
                assert(result.map(_.code) == Result.Success(200))
            }
        }

        "404 Not Found" in run {
            Sync.defer {
                val result = Http1Protocol.parseStatusLine("HTTP/1.1 404 Not Found")
                assert(result.map(_.code) == Result.Success(404))
            }
        }

        "301 Moved Permanently" in run {
            Sync.defer {
                val result = Http1Protocol.parseStatusLine("HTTP/1.1 301 Moved Permanently")
                assert(result.map(_.code) == Result.Success(301))
            }
        }

        "500 Internal Server Error" in run {
            Sync.defer {
                val result = Http1Protocol.parseStatusLine("HTTP/1.1 500 Internal Server Error")
                assert(result.map(_.code) == Result.Success(500))
            }
        }

        "204 No Content" in run {
            Sync.defer {
                val result = Http1Protocol.parseStatusLine("HTTP/1.1 204 No Content")
                assert(result.map(_.code) == Result.Success(204))
            }
        }

        "custom reason" in run {
            Sync.defer {
                val result = Http1Protocol.parseStatusLine("HTTP/1.1 200 Custom")
                assert(result.map(_.code) == Result.Success(200))
            }
        }

        "missing reason" in run {
            Sync.defer {
                val result = Http1Protocol.parseStatusLine("HTTP/1.1 200")
                assert(result.map(_.code) == Result.Success(200))
            }
        }

        "malformed code" in run {
            Sync.defer {
                val result = Http1Protocol.parseStatusLine("HTTP/1.1 abc")
                assert(result.isFailure)
            }
        }

        "garbage" in run {
            Sync.defer {
                val result = Http1Protocol.parseStatusLine("garbage")
                assert(result.isFailure)
            }
        }
    }

    // ── Header parsing ──────────────────────────────────────────

    "parseHeaders" - {
        "single header" in run {
            Sync.defer {
                val headers = Http1Protocol.parseHeaders(
                    Array("Content-Type: application/json"),
                    startIndex = 0
                )
                assert(headers.get("Content-Type") == Present("application/json"))
            }
        }

        "multiple same-name headers" in run {
            Sync.defer {
                val headers = Http1Protocol.parseHeaders(
                    Array("Set-Cookie: a=1", "Set-Cookie: b=2"),
                    startIndex = 0
                )
                val all = headers.getAll("Set-Cookie")
                assert(all.size == 2)
            }
        }

        "no value" in run {
            Sync.defer {
                val headers = Http1Protocol.parseHeaders(Array("X-Empty:"), startIndex = 0)
                assert(headers.get("X-Empty") == Present(""))
            }
        }

        "trims whitespace" in run {
            Sync.defer {
                val headers = Http1Protocol.parseHeaders(Array("X-Test:   spaced   "), startIndex = 0)
                assert(headers.get("X-Test") == Present("spaced"))
            }
        }

        "empty block" in run {
            Sync.defer {
                val headers = Http1Protocol.parseHeaders(Array.empty[String], startIndex = 0)
                assert(headers.get("anything") == Absent)
            }
        }
    }

    // ── Chunked encoding ────────────────────────────────────────

    "encodeChunk" in run {
        Sync.defer {
            val data   = Span.fromUnsafe("hello".getBytes(Utf8))
            val result = new String(Http1Protocol.encodeChunk(data).toArrayUnsafe, Utf8)
            assert(result == "5\r\nhello\r\n")
        }
    }

    "parseChunkHeader" - {
        "simple" in run {
            Sync.defer {
                assert(Http1Protocol.parseChunkHeader("5") == Result.Success(5))
            }
        }

        "hex" in run {
            Sync.defer {
                assert(Http1Protocol.parseChunkHeader("ff") == Result.Success(255))
            }
        }

        "with extension" in run {
            Sync.defer {
                assert(Http1Protocol.parseChunkHeader("5;ext=val") == Result.Success(5))
            }
        }

        "zero" in run {
            Sync.defer {
                assert(Http1Protocol.parseChunkHeader("0") == Result.Success(0))
            }
        }
    }

    // ── Keep-alive ──────────────────────────────────────────────

    "isKeepAlive" - {
        "default is true for HTTP/1.1" in {
            assert(Http1Protocol.isKeepAlive(HttpHeaders.empty) == true)
        }

        "Connection: close is false" in {
            assert(Http1Protocol.isKeepAlive(HttpHeaders.empty.add("Connection", "close")) == false)
        }

        "Connection: keep-alive is true" in {
            assert(Http1Protocol.isKeepAlive(HttpHeaders.empty.add("Connection", "keep-alive")) == true)
        }
    }

    // ── Serialization roundtrip ─────────────────────────────────

    "writeRequestHead → readRequest roundtrip" in run {
        val stream = mockStream("")
        Http1Protocol.writeRequestHead(
            stream,
            HttpMethod.POST,
            "/submit",
            HttpHeaders.empty
                .add("Host", "example.com")
                .add("Content-Length", "0")
        ).andThen {
            // Now read back what was written
            val written  = stream.writtenString
            val readBack = new MockStream(stream.written ++ "\r\n".getBytes(Utf8))
            Http1Protocol.readRequest(readBack, 65536).map { (method, path, headers, body) =>
                assert(method == HttpMethod.POST)
                assert(path == "/submit")
                assert(headers.get("Host") == Present("example.com"))
            }
        }
    }

    "writeResponseHead → readResponse roundtrip" in run {
        val stream = mockStream("")
        Http1Protocol.writeResponseHead(
            stream,
            HttpStatus(200),
            HttpHeaders.empty
                .add("Content-Length", "0")
        ).andThen {
            val readBack = new MockStream(stream.written ++ "\r\n".getBytes(Utf8))
            Http1Protocol.readResponse(readBack, 65536).map { (status, headers, body) =>
                assert(status.code == 200)
            }
        }
    }

    "writeBody → read body roundtrip" in run {
        val bodyBytes = "hello world".getBytes(Utf8)
        val request   = s"GET /test HTTP/1.1\r\nContent-Length: ${bodyBytes.length}\r\n\r\n"
        val input     = request.getBytes(Utf8) ++ bodyBytes
        val stream    = new MockStream(input)
        Http1Protocol.readRequest(stream, 65536).map { (method, path, headers, body) =>
            assert(method == HttpMethod.GET)
            assert(path == "/test")
            body match
                case HttpBody.Buffered(data) =>
                    assert(new String(data.toArrayUnsafe, Utf8) == "hello world")
                case other =>
                    fail(s"Expected Buffered body, got $other")
            end match
        }
    }

    "1MB body roundtrip" in run {
        val bigBody = new Array[Byte](1024 * 1024)
        java.util.Arrays.fill(bigBody, 42.toByte)
        val request = s"POST /big HTTP/1.1\r\nContent-Length: ${bigBody.length}\r\n\r\n"
        val input   = request.getBytes(Utf8) ++ bigBody
        val stream  = new MockStream(input)
        Http1Protocol.readRequest(stream, 2 * 1024 * 1024).map { (_, _, _, body) =>
            body match
                case HttpBody.Buffered(data) =>
                    assert(data.size == 1024 * 1024)
                    assert(data.toArrayUnsafe.forall(_ == 42.toByte))
                case other =>
                    fail(s"Expected Buffered, got $other")
        }
    }

    "binary body (all byte values)" in run {
        val allBytes = Array.tabulate[Byte](256)(_.toByte)
        val request  = s"POST /bin HTTP/1.1\r\nContent-Length: ${allBytes.length}\r\n\r\n"
        val input    = request.getBytes(Utf8) ++ allBytes
        val stream   = new MockStream(input)
        Http1Protocol.readRequest(stream, 65536).map { (_, _, _, body) =>
            body match
                case HttpBody.Buffered(data) =>
                    val arr = data.toArrayUnsafe
                    assert(arr.length == 256)
                    var i = 0
                    while i < 256 do
                        assert(arr(i) == i.toByte, s"byte $i mismatch")
                        i += 1
                    end while
                    succeed
                case other =>
                    fail(s"Expected Buffered, got $other")
        }
    }

    // ── Edge cases ──────────────────────────────────────────────

    "empty body (GET)" in run {
        val input  = "GET /test HTTP/1.1\r\n\r\n".getBytes(Utf8)
        val stream = new MockStream(input)
        Http1Protocol.readRequest(stream, 65536).map { (_, _, _, body) =>
            assert(body == HttpBody.Empty)
        }
    }

    "0-length Content-Length" in run {
        val input  = "POST /test HTTP/1.1\r\nContent-Length: 0\r\n\r\n".getBytes(Utf8)
        val stream = new MockStream(input)
        Http1Protocol.readRequest(stream, 65536).map { (_, _, _, body) =>
            assert(body == HttpBody.Empty)
        }
    }

    "body exceeds maxSize" in run {
        val bigBody = new Array[Byte](1000)
        val request = s"POST /test HTTP/1.1\r\nContent-Length: ${bigBody.length}\r\n\r\n"
        val input   = request.getBytes(Utf8) ++ bigBody
        val stream  = new MockStream(input)
        Abort.run[HttpException] {
            Http1Protocol.readRequest(stream, 100).map { _ =>
                fail("Should have failed")
            }
        }.map { result =>
            assert(result.isFailure)
        }
    }

    "204 No Content has no body" in run {
        val input  = "HTTP/1.1 204 No Content\r\n\r\n".getBytes(Utf8)
        val stream = new MockStream(input)
        Http1Protocol.readResponse(stream, 65536).map { (status, _, body) =>
            assert(status.code == 204)
            assert(body == HttpBody.Empty)
        }
    }

    "304 Not Modified has no body" in run {
        val input  = "HTTP/1.1 304 Not Modified\r\n\r\n".getBytes(Utf8)
        val stream = new MockStream(input)
        Http1Protocol.readResponse(stream, 65536).map { (status, _, body) =>
            assert(status.code == 304)
            assert(body == HttpBody.Empty)
        }
    }

    "connection closed before headers" in run {
        val stream = new MockStream(Array.empty[Byte])
        Abort.run[HttpException] {
            Http1Protocol.readRequest(stream, 65536)
        }.map { result =>
            assert(result.isFailure)
        }
    }

    // ── Missing tests from plan ──────────────────────────────

    "POST with chunked body" in run {
        val chunked = "3\r\nabc\r\n4\r\ndefg\r\n0\r\n\r\n"
        val request = s"POST /upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"
        val input   = request.getBytes(Utf8) ++ chunked.getBytes(Utf8)
        val stream  = new MockStream(input)
        Http1Protocol.readRequest(stream, 65536).map { (method, path, headers, body) =>
            assert(method == HttpMethod.POST)
            body match
                case HttpBody.Streamed(_) => succeed
                case other                => fail(s"Expected Streamed, got $other")
        }
    }

    "chunked wins over Content-Length when both present" in run {
        val chunked = "5\r\nhello\r\n0\r\n\r\n"
        val request = s"POST /test HTTP/1.1\r\nContent-Length: 99\r\nTransfer-Encoding: chunked\r\n\r\n"
        val input   = request.getBytes(Utf8) ++ chunked.getBytes(Utf8)
        val stream  = new MockStream(input)
        Http1Protocol.readRequest(stream, 65536).map { (_, _, _, body) =>
            body match
                case HttpBody.Streamed(_) => succeed
                case other                => fail(s"Expected Streamed (chunked wins), got $other")
        }
    }

    "200 response with chunked body" in run {
        val chunked  = "5\r\nhello\r\n0\r\n\r\n"
        val response = s"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
        val input    = response.getBytes(Utf8) ++ chunked.getBytes(Utf8)
        val stream   = new MockStream(input)
        Http1Protocol.readResponse(stream, 65536).map { (status, _, body) =>
            assert(status.code == 200)
            body match
                case HttpBody.Streamed(_) => succeed
                case other                => fail(s"Expected Streamed, got $other")
        }
    }

    "case-insensitive header lookup" in run {
        Sync.defer {
            val headers = Http1Protocol.parseHeaders(
                Array("Content-Type: text/plain", "X-Custom: value"),
                startIndex = 0
            )
            assert(headers.get("content-type") == Present("text/plain"))
            assert(headers.get("CONTENT-TYPE") == Present("text/plain"))
            assert(headers.get("x-custom") == Present("value"))
        }
    }

    "unicode headers preserved" in run {
        val stream       = mockStream("")
        val unicodeValue = "café-résumé"
        Http1Protocol.writeRequestHead(
            stream,
            HttpMethod.GET,
            "/test",
            HttpHeaders.empty.add("X-Unicode", unicodeValue).add("Content-Length", "0")
        ).andThen {
            val readBack = new MockStream(stream.written ++ "\r\n".getBytes(Utf8))
            Http1Protocol.readRequest(readBack, 65536).map { (_, _, headers, _) =>
                assert(headers.get("X-Unicode") == Present(unicodeValue))
            }
        }
    }

    "multiple Content-Length same value accepted" in run {
        val input  = "GET /test HTTP/1.1\r\nContent-Length: 5\r\nContent-Length: 5\r\n\r\nhello".getBytes(Utf8)
        val stream = new MockStream(input)
        Http1Protocol.readRequest(stream, 65536).map { (_, _, _, body) =>
            body match
                case HttpBody.Buffered(data) =>
                    assert(new String(data.toArrayUnsafe, Utf8) == "hello")
                case other =>
                    fail(s"Expected Buffered, got $other")
        }
    }

    "invalid Content-Length aborts" in run {
        val input  = "POST /test HTTP/1.1\r\nContent-Length: abc\r\n\r\n".getBytes(Utf8)
        val stream = new MockStream(input)
        Abort.run[HttpException] {
            Http1Protocol.readRequest(stream, 65536)
        }.map { result =>
            assert(result.isFailure)
        }
    }

end Http1ProtocolTest
