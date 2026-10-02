package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.codec.*
import kyo.internal.http1.*
import kyo.internal.util.*

class Http1ClientConnectionTest extends kyo.BaseHttpTest:

    import AllowUnsafe.embrace.danger

    /** Helper: create a channel pair and client connection. */
    private def mkConnection(): (Channel.Unsafe[Span[Byte]], Channel.Unsafe[Span[Byte]], Http1ClientConnection) =
        val inbound  = Channel.Unsafe.init[Span[Byte]](16)
        val outbound = Channel.Unsafe.init[Span[Byte]](16)
        val conn     = Http1ClientConnection.init(inbound, outbound)
        (inbound, outbound, conn)
    end mkConnection

    /** Helper: collect all bytes written to outbound channel into a string. */
    private def collectOutbound(outbound: Channel.Unsafe[Span[Byte]]): String =
        val sb   = new StringBuilder
        var done = false
        while !done do
            outbound.poll() match
                case Result.Success(Present(span)) =>
                    sb.append(new String(span.toArray, StandardCharsets.US_ASCII))
                case _ =>
                    done = true
        end while
        sb.toString
    end collectOutbound

    /** Helper: send a request and immediately provide a response, returning the parsed response.
      *
      * Since all data is pre-offered to channels, the parser completes synchronously inside `send()`. We use `sendAndAwait` to pre-stage
      * the response before calling send.
      */
    private def sendAndAwait(
        conn: Http1ClientConnection,
        inbound: Channel.Unsafe[Span[Byte]],
        method: HttpMethod,
        path: String,
        headers: HttpHeaders,
        body: Span[Byte],
        responseBytes: String
    )(using kyo.test.AssertScope): ParsedResponse =
        // Pre-stage response bytes in inbound channel BEFORE sending the request.
        // When send() starts the parser, it will find data already available and parse synchronously.
        discard(inbound.offer(Span.fromUnsafe(responseBytes.getBytes(StandardCharsets.US_ASCII))))
        val fiber = conn.send(method, path, headers, body)
        // The parser should have completed synchronously since data was pre-staged
        val poll = fiber.poll()
        poll match
            case Present(result) =>
                result.getOrThrow.asInstanceOf[ParsedResponse]
            case Absent =>
                fail("Expected response to be parsed synchronously, but promise is still pending")
        end match
    end sendAndAwait

    "Http1ResponseParser" - {

        "parse simple 200 response" in {
            val channel  = Channel.Unsafe.init[Span[Byte]](16)
            val response = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello"
            discard(channel.offer(Span.fromUnsafe(response.getBytes(StandardCharsets.US_ASCII))))

            var result: ParsedResponse = null.asInstanceOf[ParsedResponse]
            var bodyResult: Span[Byte] = Span.empty[Byte]
            val parser                 = new Http1ResponseParser(
                channel,
                onResponseParsed = (resp, body) =>
                    result = resp
                    bodyResult = body
            )
            parser.start()

            assert(result != null, "Response should have been parsed")
            assert(result.statusCode == 200)
            assert(result.contentLength == 5)
            assert(!result.isChunked)
            assert(result.isKeepAlive)
            // Body bytes from same chunk should be extracted
            assert(bodyResult.size == 5)
            assert(new String(bodyResult.toArray, StandardCharsets.US_ASCII) == "hello")
        }

        "parse 404 response" in {
            val channel  = Channel.Unsafe.init[Span[Byte]](16)
            val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
            discard(channel.offer(Span.fromUnsafe(response.getBytes(StandardCharsets.US_ASCII))))

            var result: ParsedResponse = null.asInstanceOf[ParsedResponse]
            val parser                 = new Http1ResponseParser(
                channel,
                onResponseParsed = (resp, _) => result = resp
            )
            parser.start()

            assert(result != null)
            assert(result.statusCode == 404)
            assert(result.contentLength == 0)
        }

        "parse response with multiple headers" in {
            val channel  = Channel.Unsafe.init[Span[Byte]](16)
            val response =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "X-Request-Id: abc123\r\n" +
                    "Content-Length: 0\r\n" +
                    "\r\n"
            discard(channel.offer(Span.fromUnsafe(response.getBytes(StandardCharsets.US_ASCII))))

            var result: ParsedResponse = null.asInstanceOf[ParsedResponse]
            val parser                 = new Http1ResponseParser(
                channel,
                onResponseParsed = (resp, _) => result = resp
            )
            parser.start()

            assert(result != null)
            assert(result.statusCode == 200)
            val headers = result.headers
            assert(headers.get("Content-Type") == Present("text/plain"))
            assert(headers.get("X-Request-Id") == Present("abc123"))
            assert(headers.get("Content-Length") == Present("0"))
        }

        "parse chunked response" in {
            val channel  = Channel.Unsafe.init[Span[Byte]](16)
            val response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
            discard(channel.offer(Span.fromUnsafe(response.getBytes(StandardCharsets.US_ASCII))))

            var result: ParsedResponse = null.asInstanceOf[ParsedResponse]
            val parser                 = new Http1ResponseParser(
                channel,
                onResponseParsed = (resp, _) => result = resp
            )
            parser.start()

            assert(result != null)
            assert(result.isChunked)
            assert(result.contentLength == -1)
        }

        "parse Connection: close" in {
            val channel  = Channel.Unsafe.init[Span[Byte]](16)
            val response = "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
            discard(channel.offer(Span.fromUnsafe(response.getBytes(StandardCharsets.US_ASCII))))

            var result: ParsedResponse = null.asInstanceOf[ParsedResponse]
            val parser                 = new Http1ResponseParser(
                channel,
                onResponseParsed = (resp, _) => result = resp
            )
            parser.start()

            assert(result != null)
            assert(!result.isKeepAlive)
        }

        "parse 500 response" in {
            val channel  = Channel.Unsafe.init[Span[Byte]](16)
            val response = "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n"
            discard(channel.offer(Span.fromUnsafe(response.getBytes(StandardCharsets.US_ASCII))))

            var result: ParsedResponse = null.asInstanceOf[ParsedResponse]
            val parser                 = new Http1ResponseParser(
                channel,
                onResponseParsed = (resp, _) => result = resp
            )
            parser.start()

            assert(result != null)
            assert(result.statusCode == 500)
        }

        "incremental data - small chunks" in {
            val channel      = Channel.Unsafe.init[Span[Byte]](64)
            val fullResponse = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
            val bytes        = fullResponse.getBytes(StandardCharsets.US_ASCII)
            val chunkSize    = 8
            val chunks       = (0 until bytes.length by chunkSize).map { start =>
                val end = math.min(start + chunkSize, bytes.length)
                bytes.slice(start, end)
            }
            chunks.foreach(chunk => discard(channel.offer(Span.fromUnsafe(chunk))))

            var result: ParsedResponse = null.asInstanceOf[ParsedResponse]
            val parser                 = new Http1ResponseParser(
                channel,
                onResponseParsed = (resp, _) => result = resp
            )
            parser.start()

            assert(result != null, "Response should have been parsed from incremental chunks")
            assert(result.statusCode == 200)
            assert(result.contentLength == 0)
        }

        "channel closed fails with HttpConnectionClosedException" in {
            val channel                                                             = Channel.Unsafe.init[Span[Byte]](16)
            var failure: Maybe[Result.Error[Http1ClientConnection.ResponseFailure]] = Absent
            val parser                                                              = new Http1ResponseParser(
                channel,
                onFailure = f => failure = Present(f)
            )
            discard(channel.close())
            parser.start()

            assertValue(failure, Present(Result.fail(HttpConnectionClosedException(HttpConnectionClosedException.Phase.BeforeHead))))
        }

        "header exceeds max size" in {
            val smallMax     = 64
            val channel      = Channel.Unsafe.init[Span[Byte]](16)
            val longResponse = "HTTP/1.1 200 OK\r\nX-Big: " + "x" * 200 + "\r\n\r\n"
            discard(channel.offer(Span.fromUnsafe(longResponse.getBytes(StandardCharsets.US_ASCII))))

            var failure: Maybe[Result.Error[Http1ClientConnection.ResponseFailure]] = Absent
            var parsed: ParsedResponse                                              = null.asInstanceOf[ParsedResponse]
            val parser                                                              = new Http1ResponseParser(
                channel,
                maxHeaderSize = smallMax,
                onResponseParsed = (resp, _) => parsed = resp,
                onFailure = f => failure = Present(f)
            )
            parser.start()

            assertValue(failure, Present(Result.fail(HttpProtocolException("the response head exceeds 64 bytes"))))
            assert(parsed == null, "Parser should not have produced a response for oversized headers")
        }
    }

    "Http1ClientConnection" - {

        "send GET and receive 200 response" in {
            val (inbound, outbound, conn) = mkConnection()

            // Pre-stage response on inbound before sending request
            val responseBytes = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello"
            val resp          = sendAndAwait(
                conn,
                inbound,
                HttpMethod.GET,
                "/hello",
                HttpHeaders.empty.add("Host", "localhost"),
                Span.empty[Byte],
                responseBytes
            )

            // Verify request was serialized to outbound
            val requestStr = collectOutbound(outbound)
            assert(requestStr.startsWith("GET /hello HTTP/1.1\r\n"), s"Expected GET request line, got: $requestStr")
            assert(requestStr.contains("Host: localhost\r\n"), s"Expected Host header, got: $requestStr")
            assert(requestStr.endsWith("\r\n\r\n"), s"Expected header terminator, got: $requestStr")

            // Verify parsed response
            assert(resp.statusCode == 200)
            assert(resp.contentLength == 5)
            assert(resp.headers.get("Content-Length") == Present("5"))

            // Body bytes available
            val bodySpan = conn.lastBodySpan
            assert(bodySpan.size == 5)
            assert(new String(bodySpan.toArray, StandardCharsets.US_ASCII) == "hello")
        }

        "send POST with body" in {
            val (inbound, outbound, conn) = mkConnection()
            val body                      = "Hello World".getBytes(StandardCharsets.UTF_8)

            val resp = sendAndAwait(
                conn,
                inbound,
                HttpMethod.POST,
                "/echo",
                HttpHeaders.empty.add("Host", "localhost").add("Content-Length", body.length.toString),
                Span.fromUnsafe(body),
                "HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\nHello World"
            )

            // Verify request was serialized — headers chunk then body chunk
            val requestStr = collectOutbound(outbound)
            assert(requestStr.startsWith("POST /echo HTTP/1.1\r\n"), s"Expected POST request line, got: $requestStr")
            assert(requestStr.contains("Content-Length: 11\r\n"), s"Expected Content-Length header, got: $requestStr")
            assert(requestStr.contains("Host: localhost\r\n"), s"Expected Host header, got: $requestStr")
            // Body should follow the headers
            assert(requestStr.endsWith("Hello World"), s"Expected body at end, got: $requestStr")

            assert(resp.statusCode == 200)
            assert(resp.contentLength == 11)
        }

        "response headers parsed correctly" in {
            val (inbound, outbound, conn) = mkConnection()

            val resp = sendAndAwait(
                conn,
                inbound,
                HttpMethod.GET,
                "/",
                HttpHeaders.empty.add("Host", "localhost"),
                Span.empty[Byte],
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nX-Request-Id: req-456\r\nCache-Control: no-cache\r\nContent-Length: 2\r\n\r\n{}"
            )

            assert(resp.statusCode == 200)
            val headers = resp.headers
            assert(headers.get("Content-Type") == Present("application/json"))
            assert(headers.get("X-Request-Id") == Present("req-456"))
            assert(headers.get("Cache-Control") == Present("no-cache"))
            assert(headers.get("Content-Length") == Present("2"))
        }

        "response with body" in {
            val (inbound, outbound, conn) = mkConnection()

            val bodyContent = "response body content"
            val resp        = sendAndAwait(
                conn,
                inbound,
                HttpMethod.GET,
                "/data",
                HttpHeaders.empty.add("Host", "localhost"),
                Span.empty[Byte],
                s"HTTP/1.1 200 OK\r\nContent-Length: ${bodyContent.length}\r\n\r\n$bodyContent"
            )

            assert(resp.statusCode == 200)
            assert(resp.contentLength == bodyContent.length)

            // Body bytes from same chunk
            val bodySpan = conn.lastBodySpan
            assert(bodySpan.size == bodyContent.length)
            assert(new String(bodySpan.toArray, StandardCharsets.US_ASCII) == bodyContent)
        }

        "sequential requests on same connection" in {
            val (inbound, outbound, conn) = mkConnection()

            // First request
            val resp1 = sendAndAwait(
                conn,
                inbound,
                HttpMethod.GET,
                "/first",
                HttpHeaders.empty.add("Host", "localhost"),
                Span.empty[Byte],
                "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nfirst"
            )
            val request1Str = collectOutbound(outbound)
            assert(request1Str.contains("GET /first HTTP/1.1"), s"Expected first request, got: $request1Str")
            assert(resp1.statusCode == 200)
            assert(new String(conn.lastBodySpan.toArray, StandardCharsets.US_ASCII) == "first")

            // Second request (reuse same connection)
            val resp2 = sendAndAwait(
                conn,
                inbound,
                HttpMethod.GET,
                "/second",
                HttpHeaders.empty.add("Host", "localhost"),
                Span.empty[Byte],
                "HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nsecond"
            )
            val request2Str = collectOutbound(outbound)
            assert(request2Str.contains("GET /second HTTP/1.1"), s"Expected second request, got: $request2Str")
            assert(resp2.statusCode == 200)
            assert(new String(conn.lastBodySpan.toArray, StandardCharsets.US_ASCII) == "second")
        }

        "connection close detection" in {
            val (inbound, outbound, conn) = mkConnection()

            val resp = sendAndAwait(
                conn,
                inbound,
                HttpMethod.GET,
                "/",
                HttpHeaders.empty.add("Host", "localhost"),
                Span.empty[Byte],
                "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 4\r\n\r\ndone"
            )

            assert(resp.statusCode == 200)
            assert(!resp.isKeepAlive, "Connection: close should set isKeepAlive to false")
        }

        "PUT method serialization" in {
            val (inbound, outbound, conn) = mkConnection()
            val body                      = """{"key":"value"}""".getBytes(StandardCharsets.UTF_8)

            val resp = sendAndAwait(
                conn,
                inbound,
                HttpMethod.PUT,
                "/resource",
                HttpHeaders.empty
                    .add("Host", "localhost")
                    .add("Content-Type", "application/json")
                    .add("Content-Length", body.length.toString),
                Span.fromUnsafe(body),
                "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n"
            )

            val requestStr = collectOutbound(outbound)
            assert(requestStr.startsWith("PUT /resource HTTP/1.1\r\n"), s"Expected PUT request line, got: $requestStr")
            assert(requestStr.contains("Content-Type: application/json\r\n"), s"Expected Content-Type, got: $requestStr")
            assert(resp.statusCode == 204)
        }

        "DELETE method serialization" in {
            val (inbound, outbound, conn) = mkConnection()

            val resp = sendAndAwait(
                conn,
                inbound,
                HttpMethod.DELETE,
                "/resource/42",
                HttpHeaders.empty.add("Host", "localhost"),
                Span.empty[Byte],
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
            )

            val requestStr = collectOutbound(outbound)
            assert(requestStr.startsWith("DELETE /resource/42 HTTP/1.1\r\n"), s"Expected DELETE request line, got: $requestStr")
            assert(resp.statusCode == 200)
        }

        "empty body GET has no body chunk" in {
            val (inbound, outbound, conn) = mkConnection()

            val resp = sendAndAwait(
                conn,
                inbound,
                HttpMethod.GET,
                "/",
                HttpHeaders.empty.add("Host", "localhost"),
                Span.empty[Byte],
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
            )

            // Collect what was written — should be only headers, no body chunk
            val requestStr = collectOutbound(outbound)
            assert(requestStr.endsWith("\r\n\r\n"), s"GET without body should end with CRLFCRLF, got: $requestStr")
            // No extra content after header terminator
            val afterHeaders = requestStr.substring(requestStr.indexOf("\r\n\r\n") + 4)
            assert(afterHeaders.isEmpty, s"Expected no body after headers, got: '$afterHeaders'")
            assert(resp.statusCode == 200)
        }
    }

    /** Sends a GET over a connection whose inbound channel already holds `reads`, one span per read, and returns how the response
      * promise completed: the status code and the body bytes that shared the head's read, or the failure.
      */
    private def outcome(maxHeaderSize: Int, reads: String*): Maybe[Result[Any, (Int, String)]] =
        val inbound  = Channel.Unsafe.init[Span[Byte]](16)
        val outbound = Channel.Unsafe.init[Span[Byte]](16)
        val conn     = Http1ClientConnection.init(inbound, outbound, maxHeaderSize)
        reads.foreach(r => discard(inbound.offer(Span.fromUnsafe(r.getBytes(StandardCharsets.US_ASCII)))))
        conn.send(HttpMethod.GET, "/", HttpHeaders.empty, Span.empty[Byte]).poll().map(
            _.map { r =>
                // The fiber's value is the ParsedResponse itself, as in sendAndAwait above.
                val resp = r.asInstanceOf[ParsedResponse]
                (resp.statusCode, new String(conn.lastBodySpan.toArray, StandardCharsets.US_ASCII))
            }
        )
    end outcome

    /** Asserts whole-value equality and, on a mismatch, reports the observed value on one line. */
    private def assertValue[A](actual: A, expected: A)(using CanEqual[A, A], Frame, kyo.test.AssertScope): Unit =
        assert(actual == expected, s"observed: ${actual.toString.replaceAll("\\s+", " ").take(4000)}")

    /** A response head of exactly `size` bytes, terminator included. */
    private def headOf(size: Int, contentLength: Int): String =
        val fixed = s"HTTP/1.1 200 OK\r\nContent-Length: $contentLength\r\nX-Pad: \r\n\r\n".length
        s"HTTP/1.1 200 OK\r\nContent-Length: $contentLength\r\nX-Pad: ${"p" * (size - fixed)}\r\n\r\n"
    end headOf

    "the head limit" - {

        "a head of exactly maxHeaderSize bytes in one read is accepted" in {
            val head = headOf(64, 0)
            assert(head.length == 64)
            assertValue(outcome(64, head), Present(Result.succeed((200, ""))))
        }

        "a head one byte over maxHeaderSize in one read fails with HttpProtocolException" in {
            val head = headOf(65, 0)
            assert(head.length == 65)
            assertValue(outcome(64, head), Present(Result.fail(HttpProtocolException("the response head exceeds 64 bytes"))))
        }

        "a head of exactly maxHeaderSize bytes whose terminator straddles two reads is accepted" in {
            val head = headOf(64, 0)
            assertValue(outcome(64, head.take(62), head.drop(62)), Present(Result.succeed((200, ""))))
        }

        "a head one byte over maxHeaderSize whose terminator straddles two reads fails with HttpProtocolException" in {
            val head = headOf(65, 0)
            assertValue(
                outcome(64, head.take(63), head.drop(63)),
                Present(Result.fail(HttpProtocolException("the response head exceeds 64 bytes")))
            )
        }

        "a head of exactly maxHeaderSize bytes followed in the same read by a larger body is accepted with the body" in {
            val body = "b" * 200
            assertValue(outcome(64, headOf(64, 200) + body), Present(Result.succeed((200, body))))
        }

        "a head one byte over maxHeaderSize followed in the same read by a body fails with HttpProtocolException" in {
            assertValue(
                outcome(64, headOf(65, 200) + ("b" * 200)),
                Present(Result.fail(HttpProtocolException("the response head exceeds 64 bytes")))
            )
        }
    }

    "close with a response outstanding completes the response with HttpConnectionClosedException" in {
        val inbound  = Channel.Unsafe.init[Span[Byte]](16)
        val outbound = Channel.Unsafe.init[Span[Byte]](16)
        val conn     = Http1ClientConnection.init(inbound, outbound)
        val fiber    = conn.send(HttpMethod.GET, "/", HttpHeaders.empty, Span.empty[Byte])
        // ParsedResponse has no CanEqual; reference equality is right for it, and no response is expected here.
        given CanEqual[ParsedResponse < Any, ParsedResponse < Any] = CanEqual.derived
        assertValue(fiber.poll(), Absent)
        conn.close()
        assertValue(fiber.poll(), Present(Result.fail(HttpConnectionClosedException(HttpConnectionClosedException.Phase.BeforeHead))))
    }

    "an interim response in its own read is skipped for the final response (RFC 9110 section 15.2)" in {
        assertValue(
            outcome(
                65536,
                "HTTP/1.1 103 Early Hints\r\nLink: </s.css>; rel=preload\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello"
            ),
            Present(Result.succeed((200, "hello")))
        )
    }

    "thousands of interim responses in one read are skipped for the final response" in {
        val interim = "HTTP/1.1 103 Early Hints\r\n\r\n" * 10000
        assertValue(
            outcome(65536, interim + "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello"),
            Present(Result.succeed((200, "hello")))
        )
    }

    "a 101 is the final response of an upgrade, not an interim one" in {
        assertValue(
            outcome(65536, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"),
            Present(Result.succeed((101, "")))
        )
    }

end Http1ClientConnectionTest
