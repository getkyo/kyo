package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.codec.*
import kyo.internal.http1.*
import kyo.internal.util.*

class Http1ClientConnectionTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

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
    ): ParsedResponse =
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
            val parser = new Http1ResponseParser(
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
            succeed
        }

        "parse 404 response" in {
            val channel  = Channel.Unsafe.init[Span[Byte]](16)
            val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
            discard(channel.offer(Span.fromUnsafe(response.getBytes(StandardCharsets.US_ASCII))))

            var result: ParsedResponse = null.asInstanceOf[ParsedResponse]
            val parser = new Http1ResponseParser(
                channel,
                onResponseParsed = (resp, _) => result = resp
            )
            parser.start()

            assert(result != null)
            assert(result.statusCode == 404)
            assert(result.contentLength == 0)
            succeed
        }

        "parse response with multiple headers" in {
            val channel = Channel.Unsafe.init[Span[Byte]](16)
            val response =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "X-Request-Id: abc123\r\n" +
                    "Content-Length: 0\r\n" +
                    "\r\n"
            discard(channel.offer(Span.fromUnsafe(response.getBytes(StandardCharsets.US_ASCII))))

            var result: ParsedResponse = null.asInstanceOf[ParsedResponse]
            val parser = new Http1ResponseParser(
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
            succeed
        }

        "parse chunked response" in {
            val channel  = Channel.Unsafe.init[Span[Byte]](16)
            val response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
            discard(channel.offer(Span.fromUnsafe(response.getBytes(StandardCharsets.US_ASCII))))

            var result: ParsedResponse = null.asInstanceOf[ParsedResponse]
            val parser = new Http1ResponseParser(
                channel,
                onResponseParsed = (resp, _) => result = resp
            )
            parser.start()

            assert(result != null)
            assert(result.isChunked)
            assert(result.contentLength == -1)
            succeed
        }

        "parse Connection: close" in {
            val channel  = Channel.Unsafe.init[Span[Byte]](16)
            val response = "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
            discard(channel.offer(Span.fromUnsafe(response.getBytes(StandardCharsets.US_ASCII))))

            var result: ParsedResponse = null.asInstanceOf[ParsedResponse]
            val parser = new Http1ResponseParser(
                channel,
                onResponseParsed = (resp, _) => result = resp
            )
            parser.start()

            assert(result != null)
            assert(!result.isKeepAlive)
            succeed
        }

        "parse 500 response" in {
            val channel  = Channel.Unsafe.init[Span[Byte]](16)
            val response = "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n"
            discard(channel.offer(Span.fromUnsafe(response.getBytes(StandardCharsets.US_ASCII))))

            var result: ParsedResponse = null.asInstanceOf[ParsedResponse]
            val parser = new Http1ResponseParser(
                channel,
                onResponseParsed = (resp, _) => result = resp
            )
            parser.start()

            assert(result != null)
            assert(result.statusCode == 500)
            succeed
        }

        "incremental data - small chunks" in {
            val channel      = Channel.Unsafe.init[Span[Byte]](64)
            val fullResponse = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
            val bytes        = fullResponse.getBytes(StandardCharsets.US_ASCII)
            val chunkSize    = 8
            val chunks = (0 until bytes.length by chunkSize).map { start =>
                val end = math.min(start + chunkSize, bytes.length)
                bytes.slice(start, end)
            }
            chunks.foreach(chunk => discard(channel.offer(Span.fromUnsafe(chunk))))

            var result: ParsedResponse = null.asInstanceOf[ParsedResponse]
            val parser = new Http1ResponseParser(
                channel,
                onResponseParsed = (resp, _) => result = resp
            )
            parser.start()

            assert(result != null, "Response should have been parsed from incremental chunks")
            assert(result.statusCode == 200)
            assert(result.contentLength == 0)
            succeed
        }

        "channel closed triggers onClosed" in {
            val channel      = Channel.Unsafe.init[Span[Byte]](16)
            var closedCalled = false
            val parser = new Http1ResponseParser(
                channel,
                onClosed = () => closedCalled = true
            )
            discard(channel.close())
            parser.start()

            assert(closedCalled, "onClosed should have been called when channel is closed")
            succeed
        }

        "header exceeds max size" in {
            val smallMax     = 64
            val channel      = Channel.Unsafe.init[Span[Byte]](16)
            val longResponse = "HTTP/1.1 200 OK\r\nX-Big: " + "x" * 200 + "\r\n\r\n"
            discard(channel.offer(Span.fromUnsafe(longResponse.getBytes(StandardCharsets.US_ASCII))))

            var closedCalled           = false
            var parsed: ParsedResponse = null.asInstanceOf[ParsedResponse]
            val parser = new Http1ResponseParser(
                channel,
                maxHeaderSize = smallMax,
                onResponseParsed = (resp, _) => parsed = resp,
                onClosed = () => closedCalled = true
            )
            parser.start()

            assert(closedCalled, "Parser should have called onClosed for oversized headers")
            assert(parsed == null, "Parser should not have produced a response for oversized headers")
            succeed
        }
    }

    "Http1ClientConnection" - {

        "send GET and receive 200 response" in {
            val (inbound, outbound, conn) = mkConnection()

            // Pre-stage response on inbound before sending request
            val responseBytes = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello"
            val resp = sendAndAwait(
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
            succeed
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
            succeed
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
            succeed
        }

        "response with body" in {
            val (inbound, outbound, conn) = mkConnection()

            val bodyContent = "response body content"
            val resp = sendAndAwait(
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
            succeed
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
            succeed
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
            succeed
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
            succeed
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
            succeed
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
            succeed
        }
    }

end Http1ClientConnectionTest
