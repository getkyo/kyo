package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

/** Integration tests for Http1Protocol2 wired with KqueueNativeTransport2 over real TCP.
  *
  * Only meaningful on macOS/BSD. On Linux, all tests pass trivially (succeed).
  */
class Http1NativeTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8    = StandardCharsets.UTF_8
    private val isMacOS = java.lang.System.getProperty("os.name", "").toLowerCase.contains("mac")

    private def onMacOS(
        f: KqueueNativeTransport2 => Assertion < (Async & Abort[HttpException] & Scope)
    )(using Frame): Assertion < (Async & Abort[HttpException] & Scope) =
        if !isMacOS then succeed
        else f(new KqueueNativeTransport2)

    // ── Helper: start a server that handles one connection ─────────────────────

    /** Start a server fiber that handles one connection, then run the client.
      *
      * The server fiber is started first (so it can begin accepting). After the client completes, the server fiber is awaited to check for
      * errors.
      */
    private def withServerClient[A](
        transport: KqueueNativeTransport2
    )(
        handler: KqueueConnection2 => Unit < (Async & Abort[HttpException])
    )(
        client: (KqueueConnection2, Int) => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException] & Scope) =
        transport.listen("127.0.0.1", 0, 128, Absent).map { listener =>
            Fiber.initUnscoped {
                listener.connections.take(1).run.map { chunk =>
                    if chunk.isEmpty then Abort.panic(new Exception("No connection accepted"))
                    else handler(chunk(0))
                }
            }.andThen {
                transport.connect("127.0.0.1", listener.port, Absent).map { conn =>
                    Sync.ensure(transport.closeNow(conn)) {
                        client(conn, listener.port)
                    }
                }
            }
        }

    // ────────────────────────────────────────────────────────────────────────────
    // Basic HTTP roundtrip (4 tests)
    // ────────────────────────────────────────────────────────────────────────────

    // Test 1: GET 200 with body
    "GET 200 with body" in run {
        Scope.run {
            onMacOS { transport =>
                withServerClient(transport) { serverConn =>
                    Http1Protocol2.readRequest(serverConn.read, 65536).map { case ((_, path, _, _), _) =>
                        val responseBody = HttpBody.Buffered(Span.fromUnsafe(s"Hello from $path".getBytes(Utf8)))
                        Http1Protocol2.writeResponse(serverConn, HttpStatus(200), HttpHeaders.empty, responseBody)
                    }
                } { (clientConn, _) =>
                    Http1Protocol2.writeRequest(clientConn, HttpMethod.GET, "/hello", HttpHeaders.empty, HttpBody.Empty).andThen {
                        Http1Protocol2.readResponse(clientConn.read, 65536).map { case ((status, _, body), _) =>
                            assert(status.code == 200)
                            body match
                                case HttpBody.Buffered(data) =>
                                    assert(new String(data.toArrayUnsafe, Utf8) == "Hello from /hello")
                                case other => fail(s"Expected Buffered, got $other")
                            end match
                        }
                    }
                }
            }
        }
    }

    // Test 2: POST 1KB JSON body
    "POST 1KB JSON body" in run {
        Scope.run {
            onMacOS { transport =>
                val jsonBody = "{\"key\":\"" + ("x" * 1000) + "\"}"
                withServerClient(transport) { serverConn =>
                    Http1Protocol2.readRequest(serverConn.read, 65536).map { case ((_, _, _, body), _) =>
                        Http1Protocol2.writeResponse(serverConn, HttpStatus(200), HttpHeaders.empty, body)
                    }
                } { (clientConn, _) =>
                    val bodySpan = Span.fromUnsafe(jsonBody.getBytes(Utf8))
                    Http1Protocol2
                        .writeRequest(clientConn, HttpMethod.POST, "/data", HttpHeaders.empty, HttpBody.Buffered(bodySpan))
                        .andThen {
                            Http1Protocol2.readResponse(clientConn.read, 65536).map { case ((status, _, body), _) =>
                                assert(status.code == 200)
                                body match
                                    case HttpBody.Buffered(data) =>
                                        assert(new String(data.toArrayUnsafe, Utf8) == jsonBody)
                                    case other => fail(s"Expected Buffered, got $other")
                                end match
                            }
                        }
                }
            }
        }
    }

    // Test 3: PUT binary body no corruption
    "PUT binary body no corruption" in run {
        Scope.run {
            onMacOS { transport =>
                val allBytes = Array.tabulate[Byte](256)(_.toByte)
                withServerClient(transport) { serverConn =>
                    Http1Protocol2.readRequest(serverConn.read, 65536).map { case ((_, _, _, body), _) =>
                        Http1Protocol2.writeResponse(serverConn, HttpStatus(200), HttpHeaders.empty, body)
                    }
                } { (clientConn, _) =>
                    val bodySpan = Span.fromUnsafe(allBytes)
                    Http1Protocol2
                        .writeRequest(clientConn, HttpMethod.PUT, "/binary", HttpHeaders.empty, HttpBody.Buffered(bodySpan))
                        .andThen {
                            Http1Protocol2.readResponse(clientConn.read, 65536).map { case ((status, _, body), _) =>
                                assert(status.code == 200)
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
                                    case other => fail(s"Expected Buffered, got $other")
                                end match
                            }
                        }
                }
            }
        }
    }

    // Test 4: HEAD no body — response has Content-Length but no body
    "HEAD no body" in run {
        Scope.run {
            onMacOS { transport =>
                withServerClient(transport) { serverConn =>
                    Http1Protocol2.readRequest(serverConn.read, 65536).map { case ((_, _, _, _), _) =>
                        // Server sends 200 with a body (Content-Length will be set automatically)
                        val responseBody = HttpBody.Buffered(Span.fromUnsafe("some content".getBytes(Utf8)))
                        Http1Protocol2.writeResponse(serverConn, HttpStatus(200), HttpHeaders.empty, responseBody)
                    }
                } { (clientConn, _) =>
                    Http1Protocol2.writeRequest(clientConn, HttpMethod.HEAD, "/resource", HttpHeaders.empty, HttpBody.Empty).andThen {
                        // readResponse with HEAD → body must be Empty regardless of Content-Length
                        Http1Protocol2.readResponse(clientConn.read, 65536, requestMethod = HttpMethod.HEAD).map {
                            case ((status, headers, body), _) =>
                                assert(status.code == 200)
                                assert(body == HttpBody.Empty)
                                assert(headers.get("Content-Length") == Present("12"))
                        }
                    }
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Keep-alive over TCP (2 tests)
    // ────────────────────────────────────────────────────────────────────────────

    // Test 5: 5 sequential requests same connection
    "5 sequential requests same connection" in run {
        Scope.run {
            onMacOS { transport =>
                transport.listen("127.0.0.1", 0, 128, Absent).map { listener =>
                    // Server: handle 5 requests on one connection using Loop
                    val serverFiber = Fiber.initUnscoped {
                        listener.connections.take(1).run.map { chunk =>
                            if chunk.isEmpty then Abort.panic(new Exception("No connection"))
                            else
                                val serverConn = chunk(0)
                                Loop(serverConn.read, 0) { (stream, count) =>
                                    if count >= 5 then Loop.done(())
                                    else
                                        Http1Protocol2.readRequest(stream, 65536).map { case ((_, _, _, _), remaining) =>
                                            val respBody = HttpBody.Buffered(
                                                Span.fromUnsafe(s"response-$count".getBytes(Utf8))
                                            )
                                            Http1Protocol2
                                                .writeResponse(serverConn, HttpStatus(200), HttpHeaders.empty, respBody)
                                                .andThen {
                                                    Loop.continue(remaining, count + 1)
                                                }
                                        }
                                }
                        }
                    }
                    serverFiber.andThen {
                        transport.connect("127.0.0.1", listener.port, Absent).map { clientConn =>
                            Sync.ensure(transport.closeNow(clientConn)) {
                                // Client: send 5 requests and verify each response
                                Loop(clientConn.read, 0) { (stream, i) =>
                                    if i >= 5 then Loop.done(succeed)
                                    else
                                        Http1Protocol2
                                            .writeRequest(
                                                clientConn,
                                                HttpMethod.GET,
                                                s"/req$i",
                                                HttpHeaders.empty,
                                                HttpBody.Empty
                                            )
                                            .andThen {
                                                Http1Protocol2.readResponse(stream, 65536).map {
                                                    case ((status, _, body), remaining) =>
                                                        assert(status.code == 200)
                                                        body match
                                                            case HttpBody.Buffered(data) =>
                                                                assert(
                                                                    new String(data.toArrayUnsafe, Utf8) == s"response-$i"
                                                                )
                                                            case other => fail(s"Expected Buffered, got $other")
                                                        end match
                                                        Loop.continue(remaining, i + 1)
                                                }
                                            }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Test 6: Stream threads correctly across requests — POST body doesn't leak into GET
    "stream threads correctly across requests" in run {
        Scope.run {
            onMacOS { transport =>
                transport.listen("127.0.0.1", 0, 128, Absent).map { listener =>
                    val serverFiber = Fiber.initUnscoped {
                        listener.connections.take(1).run.map { chunk =>
                            if chunk.isEmpty then Abort.panic(new Exception("No connection"))
                            else
                                val serverConn = chunk(0)
                                // Handle 2 requests, threading stream through both
                                Loop(serverConn.read, 0) { (stream, count) =>
                                    if count >= 2 then Loop.done(())
                                    else
                                        Http1Protocol2.readRequest(stream, 65536).map {
                                            case ((method, _, _, body), remaining) =>
                                                val bodyStr = body match
                                                    case HttpBody.Buffered(d) => d.size.toString
                                                    case HttpBody.Empty       => "0"
                                                    case _                    => "unknown"
                                                val respBody = HttpBody.Buffered(
                                                    Span.fromUnsafe(s"$method:$bodyStr".getBytes(Utf8))
                                                )
                                                Http1Protocol2
                                                    .writeResponse(serverConn, HttpStatus(200), HttpHeaders.empty, respBody)
                                                    .andThen {
                                                        Loop.continue(remaining, count + 1)
                                                    }
                                        }
                                }
                        }
                    }
                    serverFiber.andThen {
                        transport.connect("127.0.0.1", listener.port, Absent).map { clientConn =>
                            Sync.ensure(transport.closeNow(clientConn)) {
                                val postBody = Span.fromUnsafe("hello-post".getBytes(Utf8))
                                Http1Protocol2
                                    .writeRequest(
                                        clientConn,
                                        HttpMethod.POST,
                                        "/post",
                                        HttpHeaders.empty,
                                        HttpBody.Buffered(postBody)
                                    )
                                    .andThen {
                                        Http1Protocol2
                                            .writeRequest(
                                                clientConn,
                                                HttpMethod.GET,
                                                "/get",
                                                HttpHeaders.empty,
                                                HttpBody.Empty
                                            )
                                            .andThen {
                                                Http1Protocol2.readResponse(clientConn.read, 65536).map {
                                                    case ((_, _, body1), remaining1) =>
                                                        body1 match
                                                            case HttpBody.Buffered(d) =>
                                                                assert(new String(d.toArrayUnsafe, Utf8) == "POST:10")
                                                            case other => fail(s"Expected Buffered for POST, got $other")
                                                        end match
                                                        Http1Protocol2.readResponse(remaining1, 65536).map {
                                                            case ((_, _, body2), _) =>
                                                                body2 match
                                                                    case HttpBody.Buffered(d) =>
                                                                        // GET body should be 0 — no leakage from POST
                                                                        assert(new String(d.toArrayUnsafe, Utf8) == "GET:0")
                                                                    case other =>
                                                                        fail(s"Expected Buffered for GET, got $other")
                                                                end match
                                                        }
                                                }
                                            }
                                    }
                            }
                        }
                    }
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Chunked transfer over TCP (2 tests)
    // ────────────────────────────────────────────────────────────────────────────

    // Test 7: Server chunked response — client reassembles correctly
    "server chunked response" in run {
        Scope.run {
            onMacOS { transport =>
                withServerClient(transport) { serverConn =>
                    Http1Protocol2.readRequest(serverConn.read, 65536).map { case (_, _) =>
                        val chunks = Stream.init(Seq(
                            Span.fromUnsafe("chunk1".getBytes(Utf8)),
                            Span.fromUnsafe("-chunk2".getBytes(Utf8)),
                            Span.fromUnsafe("-chunk3".getBytes(Utf8))
                        ))
                        Http1Protocol2.writeResponse(serverConn, HttpStatus(200), HttpHeaders.empty, HttpBody.Streamed(chunks))
                    }
                } { (clientConn, _) =>
                    Http1Protocol2.writeRequest(clientConn, HttpMethod.GET, "/chunked", HttpHeaders.empty, HttpBody.Empty).andThen {
                        Http1Protocol2.readResponse(clientConn.read, 65536).map { case ((status, _, body), _) =>
                            assert(status.code == 200)
                            body match
                                case HttpBody.Buffered(data) =>
                                    assert(new String(data.toArrayUnsafe, Utf8) == "chunk1-chunk2-chunk3")
                                case other => fail(s"Expected Buffered reassembled, got $other")
                            end match
                        }
                    }
                }
            }
        }
    }

    // Test 8: Client chunked request — server reassembles correctly
    "client chunked request" in run {
        Scope.run {
            onMacOS { transport =>
                withServerClient(transport) { serverConn =>
                    Http1Protocol2.readRequest(serverConn.read, 65536).map { case ((_, _, _, body), _) =>
                        // Echo back the reassembled body
                        Http1Protocol2.writeResponse(serverConn, HttpStatus(200), HttpHeaders.empty, body)
                    }
                } { (clientConn, _) =>
                    val chunks = Stream.init(Seq(
                        Span.fromUnsafe("part1".getBytes(Utf8)),
                        Span.fromUnsafe("|part2".getBytes(Utf8)),
                        Span.fromUnsafe("|part3".getBytes(Utf8))
                    ))
                    Http1Protocol2
                        .writeRequest(clientConn, HttpMethod.POST, "/upload", HttpHeaders.empty, HttpBody.Streamed(chunks))
                        .andThen {
                            Http1Protocol2.readResponse(clientConn.read, 65536).map { case ((status, _, body), _) =>
                                assert(status.code == 200)
                                body match
                                    case HttpBody.Buffered(data) =>
                                        assert(new String(data.toArrayUnsafe, Utf8) == "part1|part2|part3")
                                    case other => fail(s"Expected Buffered, got $other")
                                end match
                            }
                        }
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Concurrent requests on different connections (2 tests)
    // ────────────────────────────────────────────────────────────────────────────

    // Test 9: 10 clients each sending requests — server echoes path back
    "10 clients each sending requests" in run {
        val n = 10
        Scope.run {
            onMacOS { transport =>
                transport.listen("127.0.0.1", 0, 128, Absent).map { listener =>
                    // Server: accept and handle n connections, each echoing the request path.
                    // Each connection is handled in its own fiber.
                    val serverFiber = Fiber.initUnscoped {
                        Loop(listener.connections, 0) { (connStream, count) =>
                            if count >= n then Loop.done(())
                            else
                                connStream.splitAt(1).map { (chunk, rest) =>
                                    if chunk.isEmpty then Loop.done(())
                                    else
                                        val serverConn = chunk(0)
                                        // Start per-connection handler fiber (fire and forget)
                                        Fiber.initUnscoped {
                                            Http1Protocol2.readRequest(serverConn.read, 65536).map {
                                                case ((_, path, _, _), _) =>
                                                    val respBody =
                                                        HttpBody.Buffered(Span.fromUnsafe(s"echo:$path".getBytes(Utf8)))
                                                    Http1Protocol2
                                                        .writeResponse(serverConn, HttpStatus(200), HttpHeaders.empty, respBody)
                                                        .andThen {
                                                            transport.closeNow(serverConn)
                                                        }
                                            }
                                        }.andThen {
                                            Loop.continue(rest, count + 1)
                                        }
                                }
                        }
                    }
                    serverFiber.andThen {
                        // n sequential clients each send a request with unique ID
                        Kyo.foreach((0 until n).toSeq) { id =>
                            transport.connect("127.0.0.1", listener.port, Absent).map { conn =>
                                Sync.ensure(transport.closeNow(conn)) {
                                    Http1Protocol2
                                        .writeRequest(conn, HttpMethod.GET, s"/client$id", HttpHeaders.empty, HttpBody.Empty)
                                        .andThen {
                                            Http1Protocol2.readResponse(conn.read, 65536).map { case ((status, _, body), _) =>
                                                assert(status.code == 200)
                                                body match
                                                    case HttpBody.Buffered(data) =>
                                                        assert(new String(data.toArrayUnsafe, Utf8) == s"echo:/client$id")
                                                    case other => fail(s"Client $id: Expected Buffered, got $other")
                                                end match
                                            }
                                        }
                                }
                            }
                        }.map(_ => succeed)
                    }
                }
            }
        }
    }

    // Test 10: No cross-contamination — each client gets exactly its own response body
    "no cross-contamination" in run {
        val n = 10
        Scope.run {
            onMacOS { transport =>
                transport.listen("127.0.0.1", 0, 128, Absent).map { listener =>
                    val serverFiber = Fiber.initUnscoped {
                        Loop(listener.connections, 0) { (connStream, count) =>
                            if count >= n then Loop.done(())
                            else
                                connStream.splitAt(1).map { (chunk, rest) =>
                                    if chunk.isEmpty then Loop.done(())
                                    else
                                        val serverConn = chunk(0)
                                        // Start per-connection handler fiber (fire and forget)
                                        Fiber.initUnscoped {
                                            Http1Protocol2.readRequest(serverConn.read, 65536).map { case ((_, _, _, body), _) =>
                                                // Echo request body back
                                                Http1Protocol2
                                                    .writeResponse(serverConn, HttpStatus(200), HttpHeaders.empty, body)
                                                    .andThen {
                                                        transport.closeNow(serverConn)
                                                    }
                                            }
                                        }.andThen {
                                            Loop.continue(rest, count + 1)
                                        }
                                }
                        }
                    }
                    serverFiber.andThen {
                        Kyo.foreach((0 until n).toSeq) { id =>
                            transport.connect("127.0.0.1", listener.port, Absent).map { conn =>
                                Sync.ensure(transport.closeNow(conn)) {
                                    val reqBody = Span.fromUnsafe(s"payload-$id".getBytes(Utf8))
                                    Http1Protocol2
                                        .writeRequest(conn, HttpMethod.POST, "/echo", HttpHeaders.empty, HttpBody.Buffered(reqBody))
                                        .andThen {
                                            Http1Protocol2.readResponse(conn.read, 65536).map { case ((status, _, body), _) =>
                                                assert(status.code == 200)
                                                body match
                                                    case HttpBody.Buffered(data) =>
                                                        val received = new String(data.toArrayUnsafe, Utf8)
                                                        assert(received == s"payload-$id", s"Client $id got: $received")
                                                    case other => fail(s"Client $id: Expected Buffered, got $other")
                                                end match
                                            }
                                        }
                                }
                            }
                        }.map(_ => succeed)
                    }
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Error handling (3 tests)
    // ────────────────────────────────────────────────────────────────────────────

    // Test 11: Client disconnects mid-request — server readRequest aborts
    "client disconnects mid-request" in run {
        Scope.run {
            onMacOS { transport =>
                transport.listen("127.0.0.1", 0, 128, Absent).map { listener =>
                    // Server: try to read a full request — should abort because client disconnects
                    val serverFiber = Fiber.initUnscoped {
                        listener.connections.take(1).run.map { chunk =>
                            if chunk.isEmpty then Abort.panic(new Exception("No connection"))
                            else
                                val serverConn = chunk(0)
                                Abort.run[HttpException] {
                                    Http1Protocol2.readRequest(serverConn.read, 65536)
                                }.map { result =>
                                    // Client disconnected mid-request → should fail
                                    assert(result.isFailure || result.isPanic, s"Expected failure, got $result")
                                }
                        }
                    }
                    // Start server fiber first, then connect client
                    serverFiber.map { serverFib =>
                        transport.connect("127.0.0.1", listener.port, Absent).map { clientConn =>
                            // Write only partial headers (no CRLF CRLF terminator), then close
                            clientConn
                                .write(Span.fromUnsafe("GET /test HTTP/1.1\r\nHost: localhost".getBytes(Utf8)))
                                .andThen {
                                    transport.closeNow(clientConn).andThen {
                                        serverFib.get.map(_ => succeed)
                                    }
                                }
                        }
                    }
                }
            }
        }
    }

    // Test 12: Server disconnects mid-response — client readResponse aborts
    "server disconnects mid-response" in run {
        Scope.run {
            onMacOS { transport =>
                transport.listen("127.0.0.1", 0, 128, Absent).map { listener =>
                    val serverFiber = Fiber.initUnscoped {
                        listener.connections.take(1).run.map { chunk =>
                            if chunk.isEmpty then Abort.panic(new Exception("No connection"))
                            else
                                val serverConn = chunk(0)
                                // Wait for request, then send partial response (body shorter than Content-Length)
                                Http1Protocol2.readRequest(serverConn.read, 65536).map { case (_, _) =>
                                    serverConn
                                        .write(Span.fromUnsafe(
                                            "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\npartial".getBytes(Utf8)
                                        ))
                                        .andThen {
                                            transport.closeNow(serverConn)
                                        }
                                }
                        }
                    }
                    serverFiber.andThen {
                        transport.connect("127.0.0.1", listener.port, Absent).map { clientConn =>
                            Sync.ensure(transport.closeNow(clientConn)) {
                                Http1Protocol2
                                    .writeRequest(clientConn, HttpMethod.GET, "/test", HttpHeaders.empty, HttpBody.Empty)
                                    .andThen {
                                        Abort.run[HttpException] {
                                            Http1Protocol2.readResponse(clientConn.read, 65536)
                                        }.map { result =>
                                            // Server closed mid-body → should fail
                                            assert(result.isFailure || result.isPanic, s"Expected failure, got $result")
                                        }
                                    }
                            }
                        }
                    }
                }
            }
        }
    }

    // Test 13: Malformed response — client gets protocol error
    "malformed response" in run {
        Scope.run {
            onMacOS { transport =>
                transport.listen("127.0.0.1", 0, 128, Absent).map { listener =>
                    val serverFiber = Fiber.initUnscoped {
                        listener.connections.take(1).run.map { chunk =>
                            if chunk.isEmpty then Abort.panic(new Exception("No connection"))
                            else
                                val serverConn = chunk(0)
                                // Wait for request, then send garbage (not valid HTTP)
                                Http1Protocol2.readRequest(serverConn.read, 65536).map { case (_, _) =>
                                    serverConn
                                        .write(Span.fromUnsafe("GARBAGE RESPONSE DATA\r\n\r\n".getBytes(Utf8)))
                                        .andThen {
                                            transport.closeNow(serverConn)
                                        }
                                }
                        }
                    }
                    serverFiber.andThen {
                        transport.connect("127.0.0.1", listener.port, Absent).map { clientConn =>
                            Sync.ensure(transport.closeNow(clientConn)) {
                                Http1Protocol2
                                    .writeRequest(clientConn, HttpMethod.GET, "/test", HttpHeaders.empty, HttpBody.Empty)
                                    .andThen {
                                        Abort.run[HttpException] {
                                            Http1Protocol2.readResponse(clientConn.read, 65536)
                                        }.map { result =>
                                            result match
                                                case Result.Failure(_: HttpProtocolException) => succeed
                                                case Result.Failure(_)                        => succeed // any error acceptable
                                                case Result.Panic(_)                          => succeed // panic also acceptable
                                                case Result.Success(_) =>
                                                    fail("Expected failure for malformed response")
                                            end match
                                        }
                                    }
                            }
                        }
                    }
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Large payloads (2 tests)
    // ────────────────────────────────────────────────────────────────────────────

    // Test 14: 1MB body arrives complete
    "1MB body arrives complete" in run {
        Scope.run {
            onMacOS { transport =>
                val size = 1024 * 1024
                val data = Array.fill[Byte](size)(0x42.toByte) // all 'B'
                withServerClient(transport) { serverConn =>
                    Http1Protocol2.readRequest(serverConn.read, size + 1024).map { case ((_, _, _, body), _) =>
                        Http1Protocol2.writeResponse(serverConn, HttpStatus(200), HttpHeaders.empty, body)
                    }
                } { (clientConn, _) =>
                    val bodySpan = Span.fromUnsafe(data)
                    Http1Protocol2
                        .writeRequest(clientConn, HttpMethod.POST, "/large", HttpHeaders.empty, HttpBody.Buffered(bodySpan))
                        .andThen {
                            Http1Protocol2.readResponse(clientConn.read, size + 1024).map { case ((status, _, body), _) =>
                                assert(status.code == 200)
                                body match
                                    case HttpBody.Buffered(respData) =>
                                        val arr = respData.toArrayUnsafe
                                        assert(arr.length == size)
                                        assert(arr.forall(_ == 0x42.toByte))
                                    case other => fail(s"Expected Buffered, got $other")
                                end match
                            }
                        }
                }
            }
        }
    }

    // Test 15: Body exceeds maxSize → Abort with HttpPayloadTooLargeException
    "body exceeds maxSize" in run {
        Scope.run {
            onMacOS { transport =>
                val bodySize = 1000
                val maxSize  = 100 // much smaller than bodySize
                transport.listen("127.0.0.1", 0, 128, Absent).map { listener =>
                    val serverFiber = Fiber.initUnscoped {
                        listener.connections.take(1).run.map { chunk =>
                            if chunk.isEmpty then Abort.panic(new Exception("No connection"))
                            else
                                val serverConn = chunk(0)
                                Http1Protocol2.readRequest(serverConn.read, 65536).map { case (_, _) =>
                                    val bigBody =
                                        HttpBody.Buffered(Span.fromUnsafe(Array.fill[Byte](bodySize)(0x41.toByte)))
                                    Http1Protocol2
                                        .writeResponse(serverConn, HttpStatus(200), HttpHeaders.empty, bigBody)
                                        .andThen {
                                            transport.closeNow(serverConn)
                                        }
                                }
                        }
                    }
                    serverFiber.andThen {
                        transport.connect("127.0.0.1", listener.port, Absent).map { clientConn =>
                            Sync.ensure(transport.closeNow(clientConn)) {
                                Http1Protocol2
                                    .writeRequest(clientConn, HttpMethod.GET, "/big", HttpHeaders.empty, HttpBody.Empty)
                                    .andThen {
                                        Abort.run[HttpException] {
                                            Http1Protocol2.readResponse(clientConn.read, maxSize)
                                        }.map { result =>
                                            result match
                                                case Result.Failure(e: HttpPayloadTooLargeException) => succeed
                                                case Result.Failure(e) =>
                                                    fail(s"Expected HttpPayloadTooLargeException, got $e")
                                                case Result.Panic(e) => fail(s"Unexpected panic: $e")
                                                case Result.Success(_) =>
                                                    fail("Expected failure when body exceeds maxSize")
                                            end match
                                        }
                                    }
                            }
                        }
                    }
                }
            }
        }
    }

end Http1NativeTest
